# Architecture

This document describes the architecture **as built**. Anything not described
here does not exist yet; the roadmap lives in `MASTER_LEDGER.md` and the master
prompt.

## Module graph (implemented)

```
rebellion-api   (plugin JAR: RebellionAPI, load: STARTUP)
    └── rebellion-core (plugin JAR: RebellionCore, load: STARTUP, depend: [RebellionAPI],
                        softdepend: [PlaceholderAPI])
```

Planned feature modules attach as siblings below Core via
`depend: [RebellionAPI, RebellionCore]` — see the master prompt §6 for the
target layout. Modules never import each other's implementation classes;
capabilities cross module boundaries through Bukkit's `ServicesManager`.

## Runtime identity contract

`RebellionAPI` is itself a small Bukkit plugin whose JAR carries the shared
contract classes (`net.zaminmc.rebellion.api.*`). Feature modules declare
`depend: [RebellionAPI]` so the server's plugin classloader guarantees exactly
one runtime identity of each API type. No module shades the API.

API surface today:

- `net.zaminmc.rebellion.api.database` — `RebellionDatabase`
  (async query/update/transaction contract), `DatabaseBackend`,
  `TransactionContext`, `RowMapper`, `ResultSetWork`.
- `net.zaminmc.rebellion.api.module` — `ModuleRegistry`,
  `ModuleDescriptor`, `ModuleHealth`, `ModuleState`.
- `net.zaminmc.rebellion.api.result` — `RebellionException` +
  `FailureCode` (explicit failure taxonomy; no null-encoding of failures).
- `net.zaminmc.rebellion.api` — `ServiceLookup`,
  `ServiceUnavailableException`.

## Core lifecycle (RebellionCorePlugin)

Startup order is strict; every step fails closed by disabling Core with an
actionable message (a `BootstrapException`):

1. **Load + validate** `config.yml`, `messages.yml`, `database.yml`.
   YAML syntax errors and validation issues disable Core before anything else
   starts. Messages fall back to bundled defaults per missing key (reported,
   non-fatal); invalid MiniMessage templates (strict mode — unclosed tags) are
   rejected.
2. **DiagnosticsService** — incident IDs (`INC-YYYYMMDD-HHMMSS-nnnnnn`),
   bounded in-memory incident ring, rotated report files under
   `plugins/RebellionCore/diagnostics/`. All content single-line sanitized;
   credentials never enter reports by construction (only backend names/booleans
   are passed in).
3. **DatabaseService** — constructed, then `bootstrap()` starts **off the main
   thread**: HikariCP pool init, SQLite pragmas (WAL journal mode,
   `foreign_keys=ON` per connection), and schema migrations. `bootstrap()`
   completes a `ready()` future; feature modules bound-wait via
   `awaitReady(timeout)` during their own enable phase.
4. **ServicesManager registration** — `ModuleRegistry` and `RebellionDatabase`.
5. **Command** `/rebellion status|modules|diagnose|reload` +
   tab completion.
6. **Optional PlaceholderAPI expansion** (`rebellion`), registered only when
   PlaceholderAPI is present; pure in-memory reads, never SQL.

Shutdown (`onDisable`) is ordered: module state flipped to DISABLED →
PAPI expansion unregistered → executor drained (10 s bound; leftovers are
reported with an incident ID) → pool closed → services unregistered.

## Configuration model

- All user-facing files are written on first boot from bundled resources; local
  edits are preserved (`YamlFileIO.loadOrCreate` never overwrites).
- Loaders produce immutable record snapshots plus a `List<ValidationIssue>`
  (path + message). Issues are aggregated, not first-wins, so admins fix
  everything in one pass.
- `schema-version` on every file; unknown keys are retained (forward
  compatibility), invalid values fail startup with the offending path.
- `database.yml` supports `${ENV_NAME}` / `${ENV_NAME:fallback}` expansion for
  credentials (`EnvExpansion`).
- Reload (`/rebellion reload`): parse and validate the candidate fully, swap
   the active immutable snapshots atomically (`AtomicReference` in
   `CoreState`); on any error keep the previous snapshots and print issues.
   `database.yml` is validated but intentionally not re-applied (restart
   required — documented in-game).

## Persistence design

- One HikariCP pool (shaded + relocated into RebellionCore) and one **bounded**
  async executor (default 4 workers, queue 2000) shared by every module.
  Submissions beyond the bound are rejected **with no side effects** and fail
   the returned future with `PERSISTENCE_OVERLOADED` + incident ID.
- All calls return `CompletableFuture`; no JDBC on the main thread, anywhere.
- Transactions run via `transaction(TransactionWork)` with real rollback
  semantics; exceptions propagate to the caller's future.
- SQLite specifics: **pool size is forced to exactly 1** (SQLite has a single
  writer; serializing eliminates `SQLITE_BUSY`). WAL journal mode is set once
  at bootstrap and persists in the file.
- MySQL/MariaDB: driver comes from the server classpath (Paper ships
  MySQL Connector/J), configuration via `database.yml`, pool sizing applies.
- Migrations: `MigrationRunner` — per-module registries, monotonic versions,
  gap detection, checksum verification (configurable strictness), downgrade
  refusal, transactional application on SQLite, and a durable
  `rb_schema_migrations` bookkeeping table. A failed migration fails the
   bootstrap and blocks dependent modules.

## Module health model

`ModuleManager` (Core) is the process-wide `ModuleRegistry`. Modules register
with an immutable `ModuleDescriptor` (`moduleKey`, display name, version), are
`PENDING` until they report, and expose `OPERATIONAL / DEGRADED / FAILED /
PENDING / DISABLED`. Snapshots are immutable copies. `/rebellion modules` and
diagnostic reports render this state; `/rebellion status` shows the healthy
ratio and persistence queue metrics.

## Thread and scheduler boundaries

| Work | Where |
|---|---|
| Config/message YAML parsing at startup | main thread (bounded, startup-only) |
| All JDBC | `Rebellion-Persistence` executor (bounded queue) |
| Repeating tasks | module-owned schedulers with documented intervals (feature modules) |
| World/player mutation from async results | server scheduler, with existence re-checks (feature modules) |

No per-player threads exist. Long-lived caches key by UUID only.

## Failure isolation

- Core failure disables Core only; the server and other plugins continue.
- Feature modules (future) must treat `ServiceLookup` misses and
  `RebellionDatabase` unavailability as fail-closed states.
- The persistence queue is the single backpressure point: saturation rejects
  work deterministically instead of queueing unbounded or blocking the
  caller.
