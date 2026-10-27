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

## Teams module (rebellion-teams, Phase 3)

ADR-0005 made schema ownership modular: modules ship `MigrationSpec` sets and
call `RebellionDatabase.applyModuleMigrations("teams", ...)` at enable — the
call waits (bounded) for the Core bootstrap, then registers, checksum-verifies
and applies the set on the persistence executor. Failure is fatal for the
module only (module health FAILED, plugin disables itself); Core keeps
running. This is the first database-backed feature module and the reference
pattern for every later module.

ADR-0006 defines the team system:

- **Authority**: the database. `UNIQUE(rb_teams.name_norm)` stops duplicate
  names; `UNIQUE(rb_team_members.player)` enforces one team per player;
  `UNIQUE(rb_team_invites(team_id, invitee))` bounds invites. Every mutation
  is one transaction that re-reads ranks from live rows, so no interleaving
  can outrun the rank checks. Racing callers receive typed outcomes
  (`NAME_TAKEN`, `ALREADY_IN_TEAM`, …), never half-states.
- **Hot path**: `TeamCache` holds per-online-player snapshots (team, rank,
  friendly-fire, ally set) plus a team→players index. Join loads it, every
  mutation refreshes the affected snapshots (composed into the caller's
  future), quit evicts. Damage and chat events never touch the database.
- **Friendly fire**: `FriendlyFirePolicy` is a pure decision matrix
  (unit-tested); the Bukkit adapter resolves the attacking player through
  the chain player → projectile shooter → tamed pet owner → area-effect-cloud
  source → TNT igniter (all verified against paper-api 26.3). Unresolvable
  sources stay allowed.
- **Chat**: `/chat` switches global/team/proximity; preferences persist in
  `rb_chat_settings`. Team chat routes to online teammates through Paper's
  `AsyncChatEvent` viewer set; proximity filters same-world players by
  horizontal radius from the event's own recipient snapshot (never a global
  player scan). Non-global channels are rate limited per player. Global chat
  is untouched vanilla behavior. Voice chat is out of scope by design.
- **Commands**: `/team` resolves membership-action targets from online
  Player objects only — names are never authority. Rank grants are code
  rules; permissions gate the command surface, ranks gate the actions.

## Wards module (rebellion-wards, Phase 4)

ADR-0007. A no-claim watch-ward system: information only, structurally
incapable of protection.

**Pipeline** (performance shape mandated by master prompt §5.2):

- `WardMoveListener` (MONITOR) — block-coordinate compare; meaningful moves
  do one O(1) `MovementFeed` mark (~50 ns measured). World changes reset
  that player's detection state; quits drop feed + pairs.
- `WardSweepTask` (async, `detection.sweep-interval-ticks`) — drains dirty
  players, adds players with inside tracks (dwell commits need time, not
  motion), runs one cell query + one pure detector pass per player, zero
  database reads; alerts batch into one main-thread hop.
- `WardDetector` — pure, clock-injected state machine: enter/leave cycles,
  per-(ward, player) cooldowns with a NEVER sentinel, dwell warning delay,
  masking suppression, decoy labeling. Output type `AlertDecision` makes
  protection side effects unrepresentable.
- `WardSpatialIndex` — per-world uniform grid; wards register in every
  covered cell at insert; queries read one cell; COW sets give the sweep
  stable snapshots; `compute`-based cell cleanup is race-safe.
- `AlertRouter` — channel routing (team/owner/both, owner fallback) and
  calibrated rendering; every interpolated value escaped against
  MiniMessage injection (tested on the parsed component).

**Persistence**: `rb_wards` (module key `wards`) with UNIQUE
`(owner, name_norm)` + UNIQUE `(world, x, y, z)` race backstops and
in-transaction cap checks; the in-memory mirror (index + anchor map)
updates only after commit, rebuilds via `loadAll` at enable.

**Module integration**: RebellionTeams is soft-depended through the API
`TeamAffiliationService` (cache-only lookups registered by teams over
`TeamCache`); without it every non-owner is an outsider and alerts route
to owners. Map markers publish through the API `MapMarkerProvider` with
`map-markers.publish-wards` defaulting to false; the Phase 10 web-map
adapter must re-check the category before rendering anything public.

**Threading**: event marks on main; sweep on async scheduler; persistence
on Core's bounded executor; in-memory mirror mutations happen on the
persistence callback path after commit. Detection state is ephemeral —
restart re-detects insiders as fresh entries; idle pairs prune after
`max(4 × cooldown, 1 h)`.

Measured pipeline costs live in `docs/PERFORMANCE.md` (no TPS claims —
no live server in this environment; live profiling is the Phase 11 gate).

## Dragons module (rebellion-dragons, Phase 5 — persistence layer only)

ADR-0008 (spike) + ADR-0009 (lineage registry). The module currently
ships exactly what the master prompt's phase ordering allows before the
live-server proofs: the API probe/spike test and the gateway-backed
lineage persistence layer. **No controller, listeners, commands or egg-item
protection exist yet** — those are gated on the ADR-0008 live experiment
set on a real Paper 26.3 server (mount feel, steering, death timing,
vanilla gateway/egg emission, pearl healing).

**Authority**: `LineageService` — transactional registry + in-memory
mirror (by id / by egg instance / by entity / by gateway), same
strategy as teams/wards (database is the durable authority; mirror
updates only after commit; `loadAll` rebuilds at enable).

**Mint path** (the only insert): `issueFromGateway(world, gateway x/y/z,
victor)` — idempotent per observed vanilla gateway anchor
(`UNIQUE (origin_world, gateway_x, gateway_y, gateway_z)`), count-checked
inside the transaction against the effective cap
(`dragons.max-lineages`, default 20, hard ceiling 20 clamped at config
load AND at runtime — the End gateway ring is finite). Slot ordinals
allocate as `MAX(slot)+1` with a UNIQUE backstop and bounded fresh-
transaction retry.

**Transfers, not mints** (all pure UPDATEs):

- `hatch` — CAS on the live `egg_instance` and `state = 'EGG_HELD'`;
  binds a fresh `entity_uuid` (UNIQUE) and the hatch-time health
  snapshot. Recovery eggs cannot hatch directly — they exit only via
  claim (recorded claimant) or admin route, both re-issuing the
  instance, so pre-park clones always lose.
- `processDeath` — CAS on the bound `entity_uuid`; valid killer receives
  the re-issued egg, no valid killer parks in `EGG_RECOVERY` with the
  last owner as claimant. Simultaneous owner-death + dragon-death and
  duplicate events resolve to exactly one egg (`EGG_RETURN_NOOP`).
- `markEggUndeliverable` / `claimRecoveredEgg` / `adminRouteEgg` — the
  protected recovery mailbox flows; claim is claimant-gated and does not
  reveal the claimant; every route is audited with the acting admin.
- `retire` — audited, terminal, keeps the slot and the gateway consumed
  (the backing gateway does not respawn).

**Audit**: every real mutation appends one `rb_dragons_audit` row in the
same transaction (ISSUE, HATCH, DEATH_RETURN, PARK_RECOVERY, CLAIM,
ADMIN_ROUTE, RETIRE); idempotent no-ops write nothing. `auditTrail` is
the admin forensics read.

**Persistence**: `rb_dragons_lineages` + `rb_dragons_audit` (module key
`dragons`, migration v1, portable SQLite/MySQL DDL) — see
`docs/DATA_MODEL.md` for column semantics and `docs/STATE_MACHINES.md`
for the transition table.

**Config**: `DragonsConfig` — deliberately minimal (`dragons.max-lineages`
only); ride/fireball/pearl tuning values arrive with the controller phase
so no number ships unproven. Plugin wiring (plugin.yml, boot, service
registration) lands with the controller; `config.yml` ships now to
document the contract.
