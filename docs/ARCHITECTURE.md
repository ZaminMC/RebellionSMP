# Architecture

This document describes the architecture **as built**. Anything not described
here does not exist yet; the roadmap lives in `MASTER_LEDGER.md` and the master
prompt.

## Module graph (implemented)

```
rebellion-api    (plugin JAR: RebellionAPI, load: STARTUP)
    └── rebellion-core (plugin JAR: RebellionCore, load: STARTUP,
                        depend: [RebellionAPI], softdepend: [PlaceholderAPI])
            └── rebellion-items (plugin JAR: RebellionItems, load: POSTWORLD,
                                 depend: [RebellionAPI, RebellionCore])
```

Further feature modules attach as siblings of `rebellion-items` below Core via
`depend: [RebellionAPI, RebellionCore]` — see the master prompt §6 for the
target layout. Modules never import each other's implementation classes;
capabilities cross module boundaries through Bukkit's `ServicesManager`
(e.g. RebellionItems registers its `AnnotateService` there for other modules
to resolve).

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
   PlaceholderAPI is present. The expansion delegates to `CorePlaceholders`,
   which answers the four core parameters and routes everything else through
   every `PlaceholderContributor` registered in the ServicesManager (the
   module-contribution contract from the master prompt §10). Pure in-memory
   reads, never SQL.

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

## Item identity + menu engine (rebellion-items)

- **Rarity registry** (`RarityRegistry`): data-driven from `rarities.yml`;
  explicit per-material overrides; every unmapped material (including ones
  from future Minecraft updates) safely resolves to COMMON. Server-free:
  keyed on material names, validated against the real registry at runtime via
  an injected validator.
- **Item identity** (`ItemIdentity`): versioned PDC contract under the
  `rebellion` namespace (`schema`, `rarity`, `item_id`, `instance_id`,
  `talisman_tier`). Forged values fail closed: unknown schemas, non-canonical
  instance UUIDs (round-trip checked — `UUID.fromString` alone is too
  lenient), malformed ids and unknown tiers are all rejected. The
  Bukkit-facing `AnnotateService` is a thin adapter over this contract.
- **Lore annotation** (`LorePlanner` + `AnnotateService`): marker-based
  de-duplication (invisible sentinel) so re-annotation never duplicates
  lines; human lore is never silently deleted; display names, enchants,
  custom model data, other plugins' PDC entries and amounts are untouched.
  Flows are config-gated (`custom-only` / `managed-items` / `all-items`;
  join normalization and pickup are opt-in). Stacks with a future/unknown
  schema are skipped (fail closed).
- **Menu engine** (`MenuLoader`/`MenuRegistry`/`MenuHost`/`MenuEvaluator`):
  DeluxeMenus-shaped subset. Registry in `config.yml` (`gui_menus.<id>.file`),
  one file per menu under `menus/`. Validation is aggregated with precise
  paths (unknown materials/actions/requirements are diagnostics, never
  guesses); atomic reload keeps the previous menus when a candidate is
  invalid; requirement evaluation strictly precedes actions; every click and
  drag on a menu inventory is cancelled (no shift-click/number-key/drag/
  cursor loopholes); `[console]` actions are double-gated (config flag +
  `rebellion.items.admin.menuconsole`); sounds use registry keys, not the
  deprecated constants; menu visibility is not authorization (per-menu
  permission re-checked server-side at open and click time).

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
