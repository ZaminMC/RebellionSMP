# State machines

Only the states that exist today are documented. Feature state machines
(artifacts, Rebellion event, prison flow, ritual claims) will
be added here as the owning modules implement them, with transition tables and
crash-recovery annotations.

## Module health (implemented: rebellion-core ModuleManager)

```
        register()                    update(OPERATIONAL)
PENDING ────────────► (feature enable) ───────────► OPERATIONAL
   │                                                  │
   │        update(DEGRADED)                          │ update(DEGRADED/FAILED)
   ▼                                                  ▼
DEGRADED ◄──────────────────────────────────────── DEGRADED
   │  any update() is possible until unregister; updates to unknown
   │  keys are ignored (no ghost entries)
   ▼  onDisable / unregister
DISABLED (terminal for that registration)
```

| From | Event | To | Persisted? | Recovery |
|---|---|---|---|---|
| — | `register(descriptor)` | PENDING | in-memory only | registration is idempotent per key |
| PENDING | module enable success | OPERATIONAL | no | restart re-runs module enable |
| OPERATIONAL | non-fatal degradation (e.g. optional integration missing) | DEGRADED | no | module self-heals or admin fixes + reload |
| any | module failure | FAILED | no | actionable detail exposed in `/rebellion modules` + reports |
| any | server/module disable | DISABLED | no | terminal |

Invariants: states are per registration; duplicate registration keeps the
first descriptor; snapshots are immutable copies; updates from other threads
are safe (concurrent map).

## Database bootstrap (implemented: rebellion-core DatabaseService)

```
constructed ──bootstrap()──► INITIALIZING ──ok──► READY ──shutdown()──► CLOSED
                                   │
                                   └─failure──► FAILED (module enable refuses;
                                                 subsequent modules fail closed)
```

- Feature modules block bounded on `awaitReady(startup-timeout-seconds)`.
- A failure produces an incident and blocks dependent modules — Core never
  silently serves a broken schema.
- `shutdown()` drains the bounded queue (≤ 10 s), reports un-flushed work with
  an incident ID, then closes the pool.

## Persistence submission (implemented: rebellion-core DatabaseService)

```
submit ──queue has room──► queued ──worker──► executed ──► future complete
   │
   └─queue saturated (or shutdown)──► REJECTED: future fails with
                                      PERSISTENCE_OVERLOADED + incident ID,
                                      no SQL side effects executed
```

Idempotency note: rejection happens before any database access, so a caller
retrying after an overload can never double-apply the operation.

## Dragon lineage lifecycle (implemented: rebellion-dragons LineageService, ADR-0009)

One state machine per lineage row (`rb_dragons_lineages.state`). Every
transition is a single compare-and-set UPDATE inside one transaction,
pinned on the expected prior state plus the row's live egg/entity
identity — never a blind write, never an INSERT (rows are minted only by
gateway issuance).

```
                      issue (gateway victory; cap check; UNIQUE gateway)
  (nothing) ────────────────────────────────────────────────► EGG_HELD ──┐
                                        │ undeliverable delivery         │
                                        ▼                                │
                                  EGG_RECOVERY ◄──── death w/o valid killer
                                   │   │ claim (recorded claimant only) │
                                   │   └── admin route (any player) ──► EGG_HELD
      hatch (CAS on egg_instance,  │
      binds fresh entity_uuid)     │   [recovery exits ONLY via claim/route;
   EGG_HELD ────────────────► HATCHED    both re-issue the egg instance]
                                   │
                                   │ death of owner/dragon (CAS on entity_uuid)
                                   ▼
                                 EGG_HELD (valid killer) or EGG_RECOVERY
                                   │ … egg cycles repeat forever …

      admin retire (any state, audited)
   any ─────────────────────────► RETIRED (terminal; slot + gateway stay consumed)
```

| From | Event | To | Guard (CAS predicate) | Idempotency |
|---|---|---|---|---|
| — | gateway victory | EGG_HELD | `UNIQUE (origin_world, gateway_x/y/z)` + in-tx count < cap | repeat/retry → `ISSUED_ALREADY` (no-op) |
| EGG_HELD | delivery failed | EGG_RECOVERY | `state = 'EGG_HELD'` | already parked → `RECOVERY_ENTERED` (no-op) |
| EGG_RECOVERY | claim | EGG_HELD | `state = 'EGG_RECOVERY' AND owner = claimant` | already yours → `CLAIMED` (no-op); wrong claimant → refused |
| EGG_HELD/EGG_RECOVERY | admin route | EGG_HELD | `state IN ('EGG_HELD','EGG_RECOVERY')` | refused when not an egg |
| EGG_HELD | hatch | HATCHED | `egg_instance = presented AND state = 'EGG_HELD'` | clone/stale/already-hatched → `HATCH_CONFLICT`, no second entity |
| HATCHED | owner/dragon death w/ killer | EGG_HELD | `entity_uuid = presented AND state = 'HATCHED'` | duplicate/simultaneous → `EGG_RETURN_NOOP`, exactly one egg |
| HATCHED | death w/o valid killer | EGG_RECOVERY | same, owner kept as claimant | same |
| any ≠ RETIRED | admin retire | RETIRED | `state <> 'RETIRED'` | double retire refused |

Invariants (each is an executed test in `LineageServiceIT`, real SQLite):

- Row count only grows via issuance → the cap (default/hard 20) is
  structurally unreachable through transfers or hatches; retirement never
  frees a slot.
- Two eggs from one death is impossible: the winner's transition REPLACES
  the egg identity; every loser affects zero rows and resolves to a typed
  no-op carrying the settled state.
- A physical egg copy only matters while its `egg_instance` is the live
  stamp of a HELD egg; hatched, retired and superseded instances stop
  resolving (`byEggInstance` gates on egg states).
- Crash safety: each transition commits atomically with its audit row; a
  crash mid-flight leaves either the pre- or post-transition state, both
  of which are valid re-entry points (events re-delivered after restart
  classify as stale/no-op through the CAS).
- Restart recovery: `loadAll()` rebuilds the by-id/by-egg/by-entity/
  by-gateway mirror from the committed rows; identity stamps round-trip
  (proven by the restart round-trip test).

### Controller-side additions (session 10, schema v2)

Two new CAS transitions extend the same table without changing the state
machine above — both are HATCHED-only updates pinned on the bound
`entity_uuid`:

| Statement | Guard (CAS predicate) | Idempotency |
|---|---|---|
| pearl heal (`CAS_PEARL_HEAL`) | `entity_uuid = presented AND state = 'HATCHED' AND last_pearl_heal_utc <= now - cooldown` | event retry inside the cooldown → 0 rows → typed `PEARL_COOLDOWN` refused before the pearl is consumed |
| persistence snapshot (`CAS_SNAPSHOT`) | `entity_uuid = presented AND state = 'HATCHED'` | superseded/dead binding → 0 rows → benign `SNAPSHOT_STALE` no-op |

Entity-side reconciliation (no schema states; the row stays HATCHED while
the entity exists on disk, per the live-proven chunk persistence, ADR-0008
E7):

- **Loaded entity** → the 30 s sweep writes health + position snapshots.
- **Chunk unloaded** → normal (entity persists; the persisted location
  resolves it for `/dragon call` via async chunk load).
- **Chunk loaded but entity gone** (plugin removal, offline wipe, void) →
  `EntityRemoveEvent(cause != UNLOAD)` immediately, and the sweep as the
  backstop, run the no-killer death CAS → the lineage parks in
  `EGG_RECOVERY` with the last owner as claimant. A living dragon is never
  orphaned by a restart or an unload/reload cycle.
- **Boss victory** (entity with a live `DragonBattle`, never a bound pet)
  → the ADR-0014 observation window: mint per emerged gateway, fail-closed
  on ambiguity/miss/no-credited-killer.

## Dimension-artifact lifecycle (implemented: rebellion-artifacts ArtifactService, ADR-0013)

One state machine per artifact row (`rb_artifacts_registry.state`), plus the
Rebellion-event occurrence machine. Every artifact transition is a single
compare-and-set UPDATE inside one transaction, pinned on the expected prior
state plus the row's live instance stamp (and holder, where the transition
is holder-initiated) — never a blind write, never an INSERT (rows are seeded
only by the idempotent load-time seed).

```
                    seed (INSERT-if-absent, dimension-keyed; config anchor sync)
  (nothing) ─────────────────────────────────────────────► ORIGIN
                                capture (CAS on state + live instance)  │
  ORIGIN ◄── completeReturn ── RETURNING ◄── beginReturn ──┐            │
    ▲                            ▲  ▲      (delay elapsed, │            │
    │                            │  │       no active event)│           │
    │                            │  └── voluntary return ──┼──┐         │
    │                            │      (CAS holder+instance)  │        │
    │                            └───────────────────────────┘│        │
    │                            spendArtifacts (trigger holder   │    ▼
    │                            holds all three; one tx)   ┌── HELD ◄─┘
    │                                  │                    │   │ transfer (CAS
    │                                  ▼                    │   │ holder+instance)
    │                                 SPENT      admin route│   │
    │                                  │          (stuck ──►HELD│   │
    │                                  │          states)  │   │   │
    │                              beginReturn ◄──────────┴─ LOST ◄─┘
    │                                │       mark lost          │
    │                                │                          find (CAS on
    └────────────────────────────────┴── … cycle repeats; no terminal state,
                                          no admin retirement, no permanent
                                          winner …
```

| From | Event | To | Guard (CAS predicate) | Idempotency |
|---|---|---|---|---|
| — | seed (load) | ORIGIN | PK `artifact_key` INSERT-if-absent | re-seed → no-op, ids stable |
| ORIGIN | capture | HELD | `state = 'ORIGIN' AND instance = presented` | clone/forged stamp → `INSTANCE_STALE`; re-capture → `INVALID_STATE` |
| HELD | transfer | HELD | `state = 'HELD' AND holder = from AND instance = presented` | stale stamp → `INSTANCE_STALE`; wrong holder → `INVALID_STATE` |
| HELD | mark lost | LOST | `state = 'HELD'` | re-park → `LOST_ALREADY` no-op (retry loops cannot wedge) |
| LOST | find | HELD | `state = 'LOST' AND instance = presented` | forged stamp → `INSTANCE_STALE`; post-find clone → `INVALID_STATE` (not lost anymore) |
| HELD | voluntary return | RETURNING | `state = 'HELD' AND holder AND instance presented` | stale stamp → `INSTANCE_STALE`; stranger → `INVALID_STATE` |
| LOST/SPENT | begin return (delay elapsed, no active event) | RETURNING | `state IN ('LOST','SPENT') AND state_since_utc <= now − delay` | too early → `INVALID_STATE`; event active → `EVENT_ACTIVE`; in-transit → `RETURN_QUEUED` no-op |
| RETURNING | complete return | ORIGIN | `state = 'RETURNING'` | re-arrival → `RETURN_NOOP` no-op |
| LOST/RETURNING | admin route | HELD | `state IN ('LOST','RETURNING')` | ORIGIN/HELD → `INVALID_STATE` (realms are conquered, not granted) |
| HELD ×3 | spend (via active event) | SPENT | `state = 'HELD' AND holder = trigger_holder`, all three in ONE transaction | refused set → rollback, `SPENT_CONFLICT`; retried spend → `SPENT_CONFLICT` ("already spent") |

Holder semantics (enforced in the domain record and every CAS): HELD/LOST
require a holder (LOST keeps the **last rightful holder**); ORIGIN/RETURNING/
SPENT require NULL and clear `holder_since_utc` to 0.

### Rebellion-event occurrence (same module)

```
  trigger (all three HELD by one player; guard CAS NULL → event id)
  (nothing) ─────────────────────► ACTIVE ──── completeRebellion ────► COMPLETED
                                     ▲  duplicate trigger/retry        │
                                     └────── EVENT_ALREADY_ACTIVE ─────┘
                                          (same occurrence, no new row)
```

- **Trigger idempotency is structural**: the singleton guard row
  (`rb_artifacts_event_guard`) is compare-and-set from NULL to the new event
  id; every loser re-reads the settled guard and resolves to
  `EVENT_ALREADY_ACTIVE` carrying the existing occurrence. Duplicate
  inventory events, retried listeners and racing observers cannot mint a
  second row.
- **Copy-proof qualification**: all three rows must be HELD by the same
  player; an artifact copied by another plugin can never satisfy this
  because a copy never wins a possession CAS.
- **Spending is all-or-none** and restricted to the trigger holder; the
  overpowering action rides this transition (catastrophe effects, warning
  gating and health scars are controller-phase work, ADR-0013).
- **No permanent winner**: after `COMPLETED` the guard is clear and a new
  full possession (e.g. after the return sweep) mints a NEW durable event
  id. Crashes mid-occurrence are recoverable: the ACTIVE row + guard
  survive restarts (proven by the restart round-trip test).

### Artifacts event occurrence — controller additions (ADR-0015, session 12)

Two time-driven edges join the occurrence machine (both idempotent, sweep
or admin driven):

- duration elapsed (config `event.duration-minutes`) → sweep calls
  `completeRebellion` (CAS on state ACTIVE) → COMPLETED, guard cleared,
  reconquest possible;
- trigger holder + telegraph elapsed + literal confirm → `executePurge`
  claims `purge_executed_utc` (CAS NULL → now, one audit row in the same
  transaction) → the ONLINE kill set runs at most once per occurrence,
  forever — `PURGE_ALREADY_EXECUTED` is the typed no-op for every retry.

The registry state machine is unchanged by the controller: every
player-facing flow (capture click, pickup, chest take, destruction,
voluntary return, spend) resolves onto the same ADR-0013 CAS transitions
— the Bukkit events are routing, never authority.

## Ritual claim lifecycle (implemented: rebellion-rpg RpgService, ADR-0016)

One state machine per claim row (`rb_rpg_claims.state`). The two-phase
anchor behind master prompt §5.3's "ritual state changes and consumed
ingredients must be committed as one logical operation": the database is
the authority and moves first; the controller may remove physical
ingredients only while a live RESERVED claim exists.

```
 reserveRitual (tx: definition + rate limit + cooldown + single-reservation)
   (nothing) ───────────────────────────────────────► RESERVED
                                                        │
                      controller removes ingredients ───┤ (controller phase)
                      failure -> releaseReservation     │ success
                                                        ▼
   RELEASED ◄── releaseReservation / stale sweep ── RESERVED ── commit* ──► COMMITTED
   (refund path; digest kept)                          (tx: CAS + effect rows
                                                        + audit, all-or-none)
```

| From | Event | To | Guard (CAS predicate) | Idempotency |
|---|---|---|---|---|
| — | `reserveRitual` | RESERVED | rate limit (any-state attempts in window) + cooldown (COMMITTED only) + no open reservation | guard refusal typed; re-reserve after release OK |
| RESERVED | `releaseReservation` | RELEASED | `state = 'RESERVED'` | re-release → `RELEASE_NOOP` |
| RESERVED | stale sweep (`created_utc <= floor`) | RELEASED | `state = 'RESERVED' AND created_utc <= ?` | re-sweep → no-op |
| RESERVED | commit (TRACKING/COUNTER_MASK/KNOWLEDGE) | COMMITTED | `state = 'RESERVED'` (CAS) + session cap for TRACKING | re-delivery → `COMMIT_NOOP` carrying the durable event outcome |
| RESERVED | commit refused: session cap | RELEASED | auto-release **inside** the commit transaction | the one mutated refusal — refund path |

Crash recovery: a crash between reserve and commit leaves a releasable
reservation; the stale sweep releases it with the per-claim digest as the
operator's refund evidence. The failure roll is drawn ahead of the CAS and
parameterizes the commit: a losing roll still commits the claim (cooldown
bought — failure is a cost) but writes no session; `RITUAL_FAILED` is
durable in the event row so re-delivery resolves to the original result.

## Tracking session + mask composition (implemented: rebellion-rpg, ADR-0016)

Sessions have **no swept state column** — expiry is derived:

```
 commitTracking (success roll, tx)          now >= expires_utc (derived)
   (nothing) ────────────────► RESOLVING ──────────────────────────► (inert)
                                 │
                                 │ revokeSession (admin, CAS on NULL stamp)
                                 ▼
                              REVOKED (terminal; reason kept)
```

`resolveTracking(caster)` is the sanctioned read for signal rendering:
for each unrevoked, unexpired session the **effective precision** = the
granted tier (stamped at cast), degraded by exactly one step when an
active `masking` effect covers the target (`rb_rpg_effects`, derived
expiry). COORDINATE→REGION→DIMENSION; a masked DIMENSION track resolves
to nothing. Masks never stack — a second grant renews protection via its
own later expiry. The controller may reveal *at most* the effective tier
(never more precision than configured).

## Encounter lifecycle (implemented: rebellion-rpg RpgService, ADR-0016)

One state machine per encounter row (`rb_rpg_encounters.state`). Every
rule that is state — concurrency cap, encounter cooldown, TTL cleanup,
one-winner rewards — is answered from these rows, never scheduler memory.

```
 beginEncounter (tx: definition + world + cap + cooldown)
   (nothing) ───────────────────────────► ACTIVE ── despawnEncounter (admin) ──► DESPAWNED
                                           │ │
             attachSoul (CAS, idempotent) │ │ completeEncounter (CAS, one winner
                                           │ │   + reward discoveries in the same tx)
                        TTL sweep began+ttl <= now │ │
                                           ▼ ▼
                                       EXPIRED  COMPLETED (resolved_utc + completed_by)
```

| From | Event | To | Guard (CAS predicate) | Idempotency |
|---|---|---|---|---|
| — | `beginEncounter` | ACTIVE | definition enabled + world allowed + ACTIVE count < cap + cooldown elapsed | typed refusals (UNKNOWN/DISABLED/WORLD/CAP/COOLDOWN) |
| ACTIVE | `attachSoul` | ACTIVE | `state = 'ACTIVE' AND soul_entity IS NULL` | re-attach → `SOUL_ATTACH_NOOP` |
| ACTIVE | `completeEncounter` | COMPLETED | `state = 'ACTIVE'` | second helper → `ENCOUNTER_ALREADY_RESOLVED`, **no reward** (unique claims = CAS + discovery PK) |
| ACTIVE | TTL sweep | EXPIRED | `state = 'ACTIVE' AND began_utc <= now − ttl` | re-sweep → no-op; expired rows can no longer complete |
| ACTIVE | `despawnEncounter` (admin) | DESPAWNED | `state = 'ACTIVE'` | unwedge tool; counts as resolved for cooldown |

Crash recovery: every transition is an individually idempotent CAS that
commits with its audit row; a crash leaves the pre- or post-transition
state — both valid re-entry points.
