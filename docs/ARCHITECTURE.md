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

### Messages (ADR-0012)

- One shared `MessageCatalog` lives in rebellion-api — the only module every
  feature module compiles against — and owns extraction, admin-edit merging,
  strict MiniMessage validation and rendering. `CoreMessages` is a thin
  adapter over it (its public surface is unchanged).
- Every module ships a bundled `messages.yml`, extracted into its data folder
  on first boot and never overwritten afterwards. Player feedback, listing
  lines, alerts, chat channel formats and the anchor-break notice all render
  through the module's catalog; dynamic values arrive as placeholders
  (`Placeholder.unparsed`/`component`), never as concatenated MiniMessage.
- Key scheme: `team.<action>.<outcome>` / `ward.<action>.<outcome>` (enum
  kebab-case); shared texts (`not-in-team`, `common.unavailable`) hoisted to
  the top level. Per-module coverage tests pin every rendered key.
- Load semantics (boot + module reloads): per-key fallback to bundled
  defaults with precise warnings; unreadable file = enable failure. Core's
  `/rebellion reload` keeps reject-whole-candidate parse semantics.
- MiniMessage escaping: literal `<` is `\<`; there is no `\>` escape
  (verified against adventure-text-minimessage 5.2.0).

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

## Dragons module (rebellion-dragons, Phase 5 — controller slice, boot-verified)

ADR-0008 (spike + live experiments, executed 2026-10-28) + ADR-0009
(lineage registry) + ADR-0014 (gateway observation). The module now
ships as the sixth installable plugin (`DragonsPlugin`, `plugin.yml`,
boot-verified on Paper 26.3 build 226 alongside the whole family):
API probe/spike test, gateway-backed lineage persistence (schema v1+v2),
the production controller surface, and the committed `DragonExperiments`
harness (`src/experiments`, never released). Every controller recipe is
an ADR-0008 measurement (mount platform `setAware(false)`+HOVER = 0.00
drift; `setPodium`+`FLY_TO_PORTAL` closed-loop steering at ~16 blocks/s
with the change-phase event as guard; `kill()` death timeline with the
199-tick corpse window as the death presentation; heal clamp arithmetic
behind the pearl cooldown CAS; `DragonFireball` launch via the
acceleration vector with zero block damage; chunk unload/reload
preserving UUID/health/phase). The client-side ride feel and the
vanilla first-victory emission (player-gated End fight,
bytecode-proven in ADR-0008) remain the documented interactive gates —
riding ships disabled-by-config until the ride-feel gate passes.

**Controller wiring** (all entity/world operations on the main thread;
all mutations through the async service CAS):

- `GatewayObserver` (ADR-0014) — boss-victory observation: baseline
  annulus scan (async chunk loads + snapshots, off-thread block sweep)
  → cheap count poll → discovery scan → pure `GatewayDiff` decision →
  `issueFromGateway` → stamped-egg delivery. Fail-closed on ambiguity,
  scan miss, and uncredited kills; pets excluded even when the vanilla
  fight adopts them.
- `PetController` — hatch (spawn → CAS → consume-or-rollback), call /
  recall (loaded entity teleports; unloaded resolves through the
  persisted location via async chunk load; vanished entities reconcile
  through the death CAS), mount/dismiss (HOVER platform; ride config
  gate), closed-loop steer task, crosshair-targeted budgeted fireballs,
  pearl feeding (CAS before consumption), water weakness, egg build /
  delivery.
- Listeners — `BossVictoryListener` (battle-gate + pet exclusion),
  `PetLifecycleListener` (direct kill, owner PvP death with the death
  presentation after the CAS commits, EntityRemoveEvent non-UNLOAD
  reconciliation, mount bookkeeping), `EggProtectionListener`
  (damage/combust/despawn/hopper/dispense/portal cancelled for live
  stamps; stale clones stay unprotected by design),
  `PetInteractListener` (pearl feed, water weakness, friendly-hit
  cancellation via UUID-tracked fireballs, phase guard).
- Tasks — 30 s persistence sweep (snapshots + orphan reconciliation +
  budget hygiene) with a final flush in `onDisable` while Core's
  database is still up; the steer task registers only while
  `ride.enabled` (restart-only switch).
- Placeholders — `DragonsPlaceholders` contributes `dragon_owned`,
  `dragon_id`, `dragon_health`, `dragon_count`, `dragon_cap` (mirror
  reads only; viewer's own lineage only).

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
`dragons`, migrations v1+v2, portable SQLite/MySQL DDL) — v2 adds the
controller snapshot columns (`loc_world/loc_x/loc_y/loc_z`,
`last_persist_utc`) additively; live-proven upgrading a v0 database to
v2 in one boot. See `docs/DATA_MODEL.md` for column semantics and
`docs/STATE_MACHINES.md` for the transition table (including the
pearl-heal and snapshot CAS rows).

**Config**: `DragonsConfig` — the full controller contract (§
CONFIGURATION.md): measured defaults from ADR-0008, the §5.6 fireball
budget (30/60 s, hard-validated ceiling 120), water weakness bounded
1.0..4.0, call range/cooldown, hatch offsets, scan bounds and window
for the ADR-0014 observer, and `ride.enabled: false` until the
ride-feel interactive gate passes. `DragonsConfigTest` pins the shipped
file and the `defaults()` factory to each other.

## Artifacts module (rebellion-artifacts, Phase 6 groundwork — persistence layer only, ADR-0013)

The three dimension-artifact state machines (master prompt §5.5). This
slice is deliberately the ADR-0009 precedent: a serverless persistence
layer with **no plugin wiring** — the module ships no `plugin.yml`, so it
is not installable as a server plugin yet; the release pipeline builds and
tests it and reports it under "not yet installable". The encounter
structure, physical item flows, catastrophe effects, placeholders
(`%rebellion_artifact_*`, master prompt §10) and commands are
controller-phase work.

**Authority**: `ArtifactService` — transactional registry + in-memory
mirror (by dimension key / by live instance, presentable states only),
same strategy as teams/wards/dragons (database is the durable authority;
mirror updates only after commit; `loadAll` rebuilds at enable).

**Seed path** (the only insert): `loadAll()` seeds exactly one row per
dimension (`OVERWORLD`, `NETHER`, `END`) with an INSERT-if-absent whose PK
backstop makes re-seeding a no-op, ensures the singleton guard row, and
re-syncs the config-owned origin anchors from `config.yml`. There is no
gameplay mint path anywhere; a table that does not hold exactly three rows
fails the load loudly.

**Possession is instance-stamped** (the anti-clone discipline): the
physical artifact item carries the row's live `instance` UUID, and every
possession change — `capture` (from the protected origin), `found` (of a
lost artifact), `transfer` (between players), `returnToOrigin` (voluntary)
— compare-and-sets on the presented stamp and supersedes it. Cloned, stale
and forged copies resolve to `INSTANCE_STALE`; a copy can never move a row
(never two valid copies).

**Loss is recoverable**: `markLost` parks a held artifact in `LOST` with
the last rightful holder kept for forensics (idempotent: `LOST_ALREADY`);
the return pipeline (`beginReturn` gated on the configured delay + the
active-event check, `completeReturn` restoring a fresh discoverable origin
instance) is two individually idempotent CAS steps, so a crashed sweep at
any cadence cannot double-apply. SPENT rows ride the same pipeline home —
the lineage is never permanently invalidated and the SMP has no permanent
winner.

**Rebellion event**: `triggerRebellion` requires all three rows HELD by
one player (default scope; `TEAM` is a loud unimplemented config error)
and mints a durable occurrence through the singleton guard CAS — duplicate
inventory events, retried listeners and racing observers all resolve to
`EVENT_ALREADY_ACTIVE` carrying the same occurrence. `spendArtifacts` is
the overpowering action's persistence half: the trigger holder's three
CASes share one transaction (all-or-none, `SPENT_CONFLICT` on any miss).
`completeRebellion` closes the occurrence and releases the guard; a new
full possession afterwards mints a NEW durable event id.

**Admin recovery**: `adminRoute` recovers stuck states only
(`LOST`/`RETURNING` → `HELD`, fresh instance, audited). An ORIGIN artifact
is refused — the protected realm must be conquered through its encounter,
never bypassed by an admin grant; an HELD artifact is not the admin's to
move.

**Audit**: every real mutation appends `rb_artifacts_audit` rows in the
same transaction (CAPTURE, TRANSFER, LOST, FOUND, RETURN_SUBMIT,
RETURN_BEGIN, RETURN_COMPLETE, ADMIN_ROUTE, REBELLION_TRIGGER, SPEND,
REBELLION_COMPLETE — one per affected artifact); idempotent no-ops write
nothing; the seed writes nothing.

**Persistence**: `rb_artifacts_registry` + `rb_artifacts_audit` +
`rb_artifacts_events` + `rb_artifacts_event_guard` (module key
`artifacts`, migration v1, portable SQLite/MySQL DDL). See
`docs/DATA_MODEL.md` for column semantics and `docs/STATE_MACHINES.md`
for the transition tables.

**Config**: `ArtifactsConfig` — per-dimension origin anchors (config-owned
world-setup data), the return delay, the rebellion-event toggle and
trigger scope (§ CONFIGURATION.md). `ArtifactsConfigTest` pins the shipped
file and the `defaults()` factory to each other.

### Artifacts controller (ADR-0015, session 12 — the seventh installable jar)

The controller slice puts the ADR-0013 state machines in front of players.
`ArtifactsPlugin` follows the proven wiring: config + messages load
(fail-closed), Core services, migrations v1+v2 (fail-closed), seed + mirror,
then listeners → sweep task → commands → placeholders → module health.
POSTWORLD (origin anchors are world coordinates); the sweep cadence is
config-owned (`sweep.interval-seconds`, 5..600 s).

**Protected-origin presentation** (`OriginPresentation`): while a row is
ORIGIN, the anchor shows a tagged ItemDisplay (visual, custom name from the
catalog) + Interaction (click target) pair. The pair is presentation, not
state — the sweep reconciles loaded anchor chunks to exactly one pair per
ORIGIN row (none otherwise), and a missing half converges to a fresh pair
(proven live: killing one half, then both, converged to exactly 1+1 within
one sweep). Chunk discipline: unloaded anchors (e.g. an empty server) wait
— presentations exist where players can see them; `getChunkAtAsync`
requests the chunk, the next sweep services it. Capture (right-click,
`OriginListener`): row must be ORIGIN, inventory must have room, and the
`capture` CAS is the sole authority — raced or cloned clicks lose
structurally. Protection: damage on the pair is cancelled; Paper 26.3's
`EntityRemoveEvent` is monitoring-only and `/kill` on a non-living display
bypasses damage events (measured live) — the backstop is the self-healing
reconcile: nothing can *permanently* delete the discoverable origin because
the row, not the entity, is the authority.

**Item flows** (`ArtifactFlowListener`, ADR-0015 §2): player pickup of a
live-stamped stack → `transfer` (or `found` when LOST; same-holder pickup
is a no-op); container takes (chest theft) through `InventoryClickEvent`
→ `transfer`; item-entity removal (DESPAWN/DEATH/EXPLODE/OUT_OF_WORLD/
DISCARD) → `markLost` (idempotent). Hopper suction is cancelled (a
container hop is not a loss) and live-stamped crafting ingredients are
cancelled. Chest storage is legal — the row stays HELD; destruction is
valid gameplay — LOST is recoverable, the deliberate opposite of the
dragons egg policy.

**Rebellion observer** (`RebellionObserver`): after any HELD transition a
dumb mirror probe — all three held by one player → `triggerRebellion` (the
guard CAS collapses duplicates). Global broadcast on trigger, join notice
while ACTIVE, config-owned reminder cadence and occurrence duration; the
sweep completes the occurrence after the duration, admin can force.

**Purge** (`PurgeExecutor`, ADR-0015 §4): config-disabled by default;
runtime gates = ACTIVE occurrence + trigger holder + telegraph elapsed +
literal `confirm`; the warning broadcast leads the kill loop by
`warning-seconds`; the once-per-occurrence stamp (schema v2
`purge_executed_utc`, CAS NULL→now) commits before any player is touched;
target set = ONLINE (STORED is a loud unimplemented config error), holder
spared per config. Kill = plain `setHealth(0)` per target — no attribute
stacking.

**Commands/placeholders**: `/artifact status|return confirm|spend confirm|
purge confirm` (eligibility service-side; confirm keywords are literals —
no free text executes), `/artifactadmin list|info|route|audit|complete
confirm|events`; `%rebellion_artifact_state_<key>%`,
`%rebellion_artifact_holder_<key>%`, `%rebellion_artifact_event_active%`
(mirror reads only; origin coordinates are never exposed).
