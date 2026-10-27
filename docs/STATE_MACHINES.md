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
