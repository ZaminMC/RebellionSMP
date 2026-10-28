# ADR-0013 — Dimension-artifact registry: three dimension-keyed slots, instance-stamped possession, idempotent Rebellion event

Date: 2026-10-28 · Status: accepted (persistence layer implemented + tested;
the controller slice — presentation, item flows, purge, commands — landed in
ADR-0015 / session 12; player-level flows remain interactive gates)

## Context

Master prompt §5.5 fixes the product contract for the three dimension
artifacts: **exactly three unique artifacts exist, one anchored to each
dimension** — Overworld (life/dominion), Nether (fire/destruction/war), End
(void/knowledge/dimensional influence). Their identity, holder, dimension of
origin, state and event history are persistent; there must never be duplicate
valid copies. Artifacts change hands through valid gameplay (dropped, stolen,
captured, returned, lost), a loss must be recoverable, and after a configurable
return delay plus an event check the artifact reappears at its protected
origin. When one player controls all three, the configured **Rebellion Event**
fires once — with a durable event ID and an idempotent transition that cannot
be tripped three times by duplicate inventory events or a copied artifact.
Overpowering actions may spend the three artifacts; after spending or a
recoverable loss the artifacts transition through "returning" back to their
protected realms so the SMP never has a permanent winner. Every stage must be
crash recoverable, and the whole contract must be "a state machine, database
constraints, unique artifact IDs and audit entries — not lore text or an
in-memory boolean."

This slice is the serverless groundwork the session-10 roadmap reserved:
"Phase 6 groundwork — the three dimension-artifact state machines (ADR-0013,
ADR-0009 style) now that every module convention is proven twice over." Like
ADR-0009 before it, it deliberately contains **no plugin wiring, listeners,
commands or physical item flows** — the module ships no plugin descriptor and
is exercised through the tested service surface. Nothing here depends on a
live server.

## Decision

### 1. One registry table, dimension-keyed, seeded — never minted by gameplay

`rb_artifacts_registry` holds **exactly one row per dimension artifact**. The
stable identity is the `artifact_key` (`OVERWORLD`, `NETHER`, `END`) — the
row's PRIMARY KEY. A server-issued `artifact_id` UUID (UNIQUE) is the PDC
identity the physical item family carries, and the mutable lifecycle snapshot
rides the same row. Rows are created **only** by `ArtifactService.seed()` at
module load — an idempotent INSERT-if-absent per key, with the configured
origin anchor — and by nothing else. There is no gameplay mint path at all:
capture, find, transfer, loss, return and spending are all pure UPDATEs, so
"exactly three" is structural, the same way ADR-0009's row count was. A
corrupted table that gains or loses rows is detected, not papered over
(`count() != 3` fails the load).

The origin anchor (`origin_world/origin_x/origin_y/origin_z`) is
**config-owned data, not gameplay state**: `seed()` re-syncs it from
`config.yml` on every load (world names, coordinates — the operator calibrates
the protected realms when the map is imported). State, holder, instance and
stamps are gameplay-owned and never touched by the sync. This split keeps the
ledger self-describing for forensics ("reappeared at the realm recorded on the
row") while an operator can still recalibrate a realm without surgery.

### 2. Possession is instance-stamped; every hand-change supersedes the stamp

The physical artifact item carries the row's live `instance` UUID (the same
PDC discipline as the dragon `egg_instance`). Every possession-changing
transition — capture from the origin, find of a lost artifact, transfer
between players, voluntary return — is a compare-and-set that **presents the
live instance and supersedes it with a fresh one**:

- a cloned or stale copy presents an instance the row no longer carries →
  `INSTANCE_STALE`, no state change (a copied artifact can never move a row,
  which is what makes the Rebellion trigger copy-proof — see §4);
- two racing presentations of the same live instance → exactly one winner,
  the loser resolves to `INSTANCE_STALE` after the winner's commit;
- a superseded physical copy is inert forever — there are never two valid
  copies of one artifact.

The origin item itself carries the live instance while the row is `ORIGIN`
(the discoverable state): capturing it consumes that instance and stamps the
discoverer's copy fresh. In `RETURNING` and `SPENT` the column keeps the
superseding value, so any leftover physical copy is inert while the artifact
is in transit or spent.

### 3. Five states, all transitions compare-and-set, everything crash-recoverable

```
                       seed (INSERT-if-absent, dimension-keyed; config anchor)
  (nothing) ─────────────────────────────────────────────► ORIGIN
                                capture (CAS on state + live instance)   │
                                                              │           │
  ORIGIN ◄── completeReturn ── RETURNING ◄── beginReturn ──┐  │           │
    ▲                            ▲  ▲      (delay elapsed, │  │           │
    │                            │  │       no active event)│  │           │
    │                            │  └── voluntary return ──┼──┤           │
    │                            │      (CAS on holder+instance)          │
    │                            └─────────────────────────┘  │           │
    │                                spendArtifacts (event holder holds    │
    │                                all three; one transaction)          ▼
    │                                                         ┌── HELD ──┤
    │                            admin route (stuck states)  │           │ transfer (CAS on
    │                            LOST/RETURNING → HELD ──────┤           │ holder+instance)
    │                                                         │           │
    │                                              mark lost  ▼           │
    │                              LOST ◄─────────────────────┘           │
    │                                │ find (CAS on live instance,        │
    │                                │       fresh stamp for finder)      │
    └────────────────────────────────┴─ … the cycle repeats; no terminal
                                         state, no permanent winner …
```

| From | Event | To | Guard (CAS predicate) | Idempotency |
|---|---|---|---|---|
| — | seed (load) | ORIGIN | PK `artifact_key` INSERT-if-absent | re-seed → no-op, ids stable |
| ORIGIN | capture | HELD | `state = 'ORIGIN' AND instance = presented` | stale/clone → `INSTANCE_STALE`; second capture → `INVALID_STATE` |
| HELD | transfer | HELD | `state = 'HELD' AND holder = from AND instance = presented` | stale → `INSTANCE_STALE`; wrong holder → `INVALID_STATE` |
| HELD | mark lost | LOST | `state = 'HELD'` | re-park → `LOST_ALREADY` no-op (retry loops can't wedge) |
| LOST | find | HELD | `state = 'LOST' AND instance = presented` | stale clone → `INSTANCE_STALE` |
| HELD | voluntary return | RETURNING | `state = 'HELD' AND holder = presented AND instance = presented` | stale → `INSTANCE_STALE` |
| LOST/SPENT | begin return (delay elapsed, no active event) | RETURNING | `state IN ('LOST','SPENT') AND state_since_utc <= now − delay` | too early → `INVALID_STATE`; event active → `EVENT_ACTIVE` |
| RETURNING | complete return | ORIGIN | `state = 'RETURNING'` | re-complete → `INVALID_STATE` |
| LOST/RETURNING | admin route | HELD | `state IN ('LOST','RETURNING')` | elsewhere → `INVALID_STATE` |
| HELD (×3) | spend (via active event) | SPENT | `state = 'HELD' AND holder = trigger_holder`, all three in one transaction | retry after success → `SPENT_CONFLICT` (typed, distinguishable) |

Holder semantics are enforced in the domain record and the row: `HELD`/`LOST`
require a holder (LOST keeps the **last rightful holder** for forensics and
the admin route), `ORIGIN`/`RETURNING`/`SPENT` require NULL. Crash safety is
the ADR-0009 property: each transition commits atomically with its audit row,
a crash leaves the pre- or post-transition state — both valid re-entry
points — and events re-delivered after restart classify through the CAS as
stale or no-ops. The return pipeline is driven by a periodic sweep
(controller phase); both of its steps (`beginReturn`, `completeReturn`) are
individually idempotent CAS primitives, so a sweep that dies mid-flight at
any cadence cannot double-apply.

### 4. The Rebellion event: one guard row, one event row, copy-proof qualification

`rb_artifacts_events` stores one row per event **occurrence** (durable
`event_id` UUID, trigger holder, auditable initiation reason, `ACTIVE` →
`COMPLETED`), and `rb_artifacts_event_guard` — a singleton table, PRIMARY KEY
on a constant — stores the currently active event id. The trigger is one
transaction:

1. read the three registry rows — **all three must be `HELD` by the same
   player**, else the typed refusal `NOT_QUALIFIED` (a *copied* artifact
   cannot help: a copy never moved a row to `HELD`, because only a live
   instance stamp wins a possession CAS — §2);
2. CAS the guard `active_event_id: NULL → new event id`; the loser resolves
   to the typed no-op `EVENT_ALREADY_ACTIVE` **carrying the existing event** —
   duplicate inventory events, retried listeners and racing observers all
   collapse into the one occurrence (master prompt: "trigger … once");
3. INSERT the ACTIVE event row; append the audit row.

Spending (`spendArtifacts`) is the overpowering action's persistence half:
the active event's trigger holder — and no one else — CASes all three rows
`HELD → SPENT` **in one transaction** (all-or-none; any miss rolls back with
`SPENT_CONFLICT`). The old physical copies die with the superseded instances.
`completeRebellion` closes the occurrence (event row → `COMPLETED`, guard
cleared, both CAS-guarded), after which the world can reconquer: a *new*
full possession mints a **new** durable event id — the SMP has no permanent
winner, but also no unbounded event spam, because at most one occurrence is
active at a time, structurally.

What is deliberately **not** in this slice: the catastrophe itself
(dimension instability, global warnings, empowered encounters, the optional
world-wide purge power and its ritual/warning/cooldown/confirmation gating),
maximum-health scars (profile-side, Phase 9 surface), the protected-origin
encounter structure, and every physical item flow. The event row + guard
carry exactly the durable state those flows must ride; the config exposes the
trigger toggle and scope key (`HOLDER` — a team-wide scope is rejected loudly
as unimplemented, per §5.5's "separate explicit configuration option" rule).

## Consequences

- Exactly three artifacts is structural: no gameplay path inserts, the seed
  is idempotent per key, and a wrong row count fails the load loudly.
- Duplicate valid copies are structurally impossible: possession requires
  presenting the live instance, and every possession change supersedes it.
- The Rebellion event is trigger-once per occurrence, crash-recoverable and
  copy-proof without any event-layer dedup code — the guard CAS and the
  possession CAS do all the work.
- Testing: `ArtifactServiceIT` against real SQLite through Core's actual
  `DatabaseService` — seeding/idempotency/anchor sync, capture/transfer/find
  races, stale-clone refusals, loss idempotency, return-delay gating, event
  qualification/trigger/complete cycles (including a second occurrence after
  completion), spending all-or-none, the full restart round-trip.
- `ArtifactsConfigTest` pins the operator contract: origin anchor validation
  (world names, coordinate bounds), return-delay bounds, the rebellion-event
  toggle, and the loud `TEAM`-scope refusal.
- The module ships **no plugin descriptor** in this slice (ADR-0009
  precedent): installing it as a server plugin is not a supported state until
  the controller phase lands. The persistence layer is exercised through the
  tested service surface.
