# State machines

Only the states that exist today are documented. Feature state machines
(dragon lineage, artifacts, Rebellion event, prison flow, ritual claims) will
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
