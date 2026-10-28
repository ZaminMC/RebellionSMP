# ADR-0009 — Dragon lineage registry: 20 gateway-backed slots, egg-issuance idempotency, transfer-not-mint

Date: 2026-10-28 · Status: accepted (persistence layer implemented + tested; schema v2 added the controller snapshot columns in session 10 — pearl-heal and snapshot CAS rows extend the transition table; the gateway observation decision is ADR-0014)

## Context

Master prompt §5.6 fixes the product contract for pet dragons: the limit is
**20 obtainable dragon lineages across the entire server** — the number of
eligible vanilla End gateways — not per-world, not per-portal, and not an
open-ended pet system. New lineages are minted only by a *legitimate Ender
Dragon boss victory validated by the vanilla gateway it opened*; every later
ownership change is a **transfer** of an existing lineage, never a mint. Eggs
must be indestructible-by-gameplay (routed into a protected recovery state
when delivery fails), hatching must be atomic against concurrent clicks,
cloned items, reconnects and duplicate events, and simultaneous owner-death +
dragon-death must never produce two eggs. Retiring a lineage is an explicit,
audited, confirmed admin action.

ADR-0008 explicitly blessed this slice as serverless work that does not
depend on the live-server proofs: *"The 20-lineage registry schema (ADR-0009,
planned) is persistence-only work that does not depend on live behavior
proofs."* Nothing in this ADR implements entity control; the controller,
listeners and commands stay gated on the ADR-0008 experiment set.

## Decision

### 1. One registry table, mint-on-gateway, slot ordinals

`rb_dragons_lineages` holds **one row per lineage ever minted**. Rows are
created only by the gateway-issuance flow. Every row carries:

- an immutable server-issued `lineage_id` (UUID, PRIMARY KEY);
- a `slot` ordinal (1, 2, …) — `UNIQUE`, allocated as `MAX(slot)+1` inside
  the mint transaction — giving operators a stable human handle
  ("lineage #7") that never shifts when ownership changes;
- the **observed vanilla gateway anchor** `origin_world, gateway_x/y/z` —
  `UNIQUE`. This is the gateway-validity guard: vanilla owns gateway
  emission (ADR-0008: no `generateGateway()` API exists), so the registry
  *observes* the gateway a victory opened and keys issuance on it. A gateway
  position can mint exactly one lineage, ever;
- the current lifecycle `state`, current `owner` (egg holder or dragon
  owner), the live `egg_instance` UUID (the PDC instance stamp the physical
  egg item must carry), and the bound `entity_uuid` of the living pet
  (`UNIQUE`, cleared on death-cycle).

Portable types only (CHAR(36)/INT/DOUBLE/BIGINT/VARCHAR), no backend-specific
syntax — same rules as ADR-0002/0006/0007.

### 2. The cap is count-based, double-enforced, and retirement never frees slots

`DragonsConfig.max-lineages` defaults to 20, validated at load to
`1..LineagePolicy.HARD_CEILING` (20, the vanilla gateway ring size).
Exceeding the ceiling is a **loud config error**, never a silent stretch. The
service *also* re-checks the hard ceiling inside the mint transaction
(defense in depth: a directly-constructed config cannot smuggle a larger
cap). The mint check is `COUNT(*) >= cap` **including RETIRED rows**: a
retired lineage keeps consuming its slot and its gateway anchor, because the
physical gateway that backed it does not respawn. Transfers and hatch cycles
never touch the row count, so they can never exceed the cap by construction.

### 3. Issuance idempotency = the gateway UNIQUE backstop

Two racing victory events for the same gateway (duplicate listeners, event
retries, double observations) hit `UNIQUE (origin_world, gateway_x, gateway_y,
gateway_z)`; the loser is resolved to a typed `ISSUED_ALREADY` idempotent
no-op that returns the existing lineage. Different gateways racing for the
last free slot settle through the in-transaction count check. At the cap the
outcome is `CAP_REACHED` — vanilla may keep its XP/reward policy, the
registry mints nothing (master prompt §5.6).

### 4. Hatching is a compare-and-set on egg identity

The physical egg item is stamped (rebellion-items PDC identity) with
`(lineage_id, egg_instance)`. `hatch()` runs
`UPDATE … SET state='HATCHED', entity_uuid=? … WHERE lineage_id=? AND
egg_instance=? AND state = 'EGG_HELD'` inside one transaction. Exactly one
contender wins the affected-row race:

- two concurrent clicks with the same egg → one HATCHED, loser gets
  `HATCH_CONFLICT` and no second entity;
- a *cloned* egg (same instance stamp) after the first hatch → finds
  `state='HATCHED'` → `HATCH_CONFLICT`;
- a stale egg from an earlier death-cycle → its instance stamp no longer
  matches the live `egg_instance` (each re-issue mints a fresh one) →
  `HATCH_CONFLICT`.

Hatch deliberately accepts **only EGG_HELD**. An egg parked in
`EGG_RECOVERY` must exit through the claimant-gated claim flow or the
audited admin route, and both re-issue a fresh `egg_instance` — so a
pre-park cloned copy can never be hatched straight out of the mailbox.

The command layer consumes the physical item only after the service reports
success, so a failed hatch never destroys an egg.

### 5. Death processing is idempotent via compare-and-set on the entity

`returnToEgg()` runs `UPDATE … SET state='EGG_*', entity_uuid=NULL,
egg_instance=<fresh> … WHERE lineage_id=? AND entity_uuid=?`. Both the
owner-death path and the dragon-death path (and any duplicated event
delivery) carry the same bound `entity_uuid`; the first transaction wins and
clears it, every later event affects zero rows and resolves to the typed
no-op `EGG_RETURN_NOOP`. Two eggs from one death is structurally impossible:
the winning transition *replaces* the HATCHED row's egg identity with a
fresh instance rather than inserting anything. A valid attributed killer
receives the egg (`EGG_HELD`); no valid killer, or an undeliverable
inventory, routes the lineage to `EGG_RECOVERY` — the protected mailbox
state — with the last rightful holder recorded as claimant.

### 6. Recovery is a state, not a deletion; retirement is audited and terminal

`EGG_RECOVERY` keeps the row alive with its egg identity; the *claim* flow
(compare-and-set on state) re-issues to the recorded claimant, and an admin
*route* flow re-issues to any player — both mint a fresh `egg_instance` and
append an audit row. `RETIRED` is terminal: every other transition refuses
it, and only the explicit admin retire flow (command-layer confirmation is
required by the master prompt; the persistence contract is "audited row or
no retire") can set it.

### 7. Every mutation is one transaction + one audit row

`rb_dragons_audit` (id, lineage_id, action, actor, detail, at_utc, indexed by
lineage) receives a row inside the same transaction as each issuance, hatch,
death-cycle, claim, route and retire — crash-safe by commit, queryable for
admin forensics. This satisfies the master prompt's "audited recovery path"
for critical rare assets.

## Lifecycle state machine

```
                     issue (gateway victory, cap check, UNIQUE gateway)
  (nothing) ─────────────────────────────────────────────► EGG_HELD ──┐
                                     │ undeliverable delivery          │
                                     ▼                                 │
                               EGG_RECOVERY ◄── no valid killer ───────┤
                                  │  │ claim (recorded claimant only) │
                                  │  └── admin route (any player) ──► EGG_HELD
     hatch (CAS on egg_instance,  │
     binds fresh entity_uuid)     │  [recovery exits ONLY via claim/route;
  EGG_HELD ──────────────► HATCHED   both re-issue the egg instance]
                                  │   
                                  │   │ death of owner/dragon (CAS on entity_uuid)
                                  │   ▼
                                  │  EGG_HELD (valid killer) or EGG_RECOVERY
                                  │   │ … egg cycles repeat …
     admin retire (any state)     │
  any ──────────────────────► RETIRED (terminal; audited; slot and gateway stay consumed)
```

## Consequences

- The registry is provably capped: row count can only grow through the
  gateway-keyed mint, which is count-checked and UNIQUE-backed; the
  hard ceiling is enforced at config load *and* in the mint path.
- Breeding/minting surface does not exist: no service method inserts a
  lineage row outside `issueFromGateway`, and transfers/hatches are pure
  UPDATEs. §19's "pet transfers do not mint lineages" is structural.
- What this ADR deliberately does **not** decide: how the vanilla gateway is
  observed in the event layer (ADR-0008 experiment 4 gates that), the
  physical egg-item protection listeners (fire/void/explosion/hopper
  routing — controller phase), the death *presentation* animation, fireball
  budgets, pearl healing events, and any ride/flight controls. Those remain
  blocked on the live Paper 26.3 proofs.
- Testing: `LineageServiceIT` against real SQLite through Core's actual
  `DatabaseService` — issuance races, cap races, hatch races, cloned/stale
  eggs, double death delivery, recovery claim/route, retire terminality,
  cap-can't-silently-exceed guard, and a full restart round-trip.
