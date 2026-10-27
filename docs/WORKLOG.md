# Worklog

Chronological work entries. Newest last. Every entry states what was actually
done, the commands run, and honest results. Do not rewrite past entries.

---

## 2026-10-27 — Session 1: Phase 0 + Phase 1 foundation

Branch: `develop` (created from `main` bootstrap state). Author: Abasing.

**Done**
- Bootstrapped the Gradle multi-project build: wrapper 9.8.2 checked in,
  version catalog `gradle/libs.versions.toml`, `FAIL_ON_PROJECT_REPOS`,
  centralized PaperMC repo, Java 25 toolchain, pinned versions
  (paper-api 26.3, HikariCP 7.1.0, sqlite-jdbc 3.49.1.0, JUnit 6.1.3,
  shadow 9.6.1).
- Implemented `rebellion-api`: `RebellionDatabase` async contract,
  `TransactionContext`, `ModuleRegistry` DTOs, `RebellionException` +
  `FailureCode` taxonomy, `ServiceLookup`; plugin JAR with STARTUP load.
- Implemented `rebellion-core`: config loaders (`config.yml`,
  `database.yml`, `messages.yml`) with bundled-resource copying, env-variable
  expansion, aggregated validation issues, atomic reload; `DatabaseService`
  (HikariCP shaded/relocated, bounded executor, backpressure rejection);
  `MigrationRunner` (per-module registries, checksums, downgrade refusal);
  `DiagnosticsService`; `ModuleManager`; `/rebellion` command;
  PlaceholderAPI expansion (guarded registration).
- Tests: 41 JUnit 5 tests across config/messages/db/diagnostics/module
  suites against real SQLite files.

**Commands/results**
- `./gradlew build` — SUCCESS
- `./gradlew test` — 41 tests, all passing (session ended before commit;
  suite was left with compile errors that session 2 fixed — recorded below).

**Left open (for session 2)**
- All work uncommitted (context exhausted).
- Compile errors in `MigrationRunnerTest` / `DatabaseServiceTest` /
  `DiagnosticsServiceTest`; test assertion bugs; docs/, CI, README, AGENTS.md
  all missing.

---

## 2026-10-28 — Session 2: repair, harden, document, first commit set

Branch: `develop`. Author: Abasing.

**Done — test suite repair (all real failures from session 1)**
- `MigrationRunnerTest:114` — stray paren fixed; test compiles.
- `DatabaseServiceTest.boundedQueueRejectsWithoutSideEffects` — the test raced
  task pickup: submissions could land in the queue window before the worker
  executed the blocker, making the rejection count flaky (2 instead of 1).
  Added a `started` latch so follow-up submissions happen only when the
  worker provably executes the blocker. Now deterministic.
- `DatabaseServiceTest` — blocked transaction lambda threw undeclared
  `InterruptedException`; wrapped into `SQLException` per the
  `TransactionWork` contract.
- `DiagnosticsService` — removed hard coupling to `Bukkit.getVersion()`:
  version is now an injected `Supplier<String>` (production supplier guards
  the pre-server window). Tests construct the service with a plain supplier;
  no Bukkit statics in core services.
- `DiagnosticsService` — incident summaries were not sanitized on the
  report/log render path; now flattened at record time (`sanitize`), so
  multi-line summaries can never break report structure.
- `CoreMessagesTest` — two wrong assertions fixed:
  - single-component MiniMessage renders have no children; assert on
    serialized plain text instead.
  - `YamlConfiguration.set(key, null)` removes keys, it cannot store nulls —
    the "invalid template" case was rewritten around the real rejection
    contract.
- `CoreMessages` — new validation contract, verified by direct experiment
  against adventure-text-minimessage 5.2.0 (probe harness, see
  REFERENCE_LEDGER): default MiniMessage throws on nothing; strict mode
  throws on unclosed tags. Validation now uses a strict instance plus a
  catch-all placeholder resolver that never shadows standard tags
  (`has → !standard.has && identifier`); render path stays lenient with real
  resolvers. Bundled `messages.yml` rewritten with explicitly closed tags;
  contract documented in the file header and AGENTS.md.
- `ModuleManagerTest.snapshotIsACopy` — `List.copyOf(...).clear()` always
  throws UOE; rewrote to assert immutability of the snapshot and detachment
  of caller copies.

**Commands/results**
- `./gradlew test` — 41 tests, 0 failures. Repeated 3× with `--rerun-tasks` —
  stable (flakiness check for the queue test specifically).
- `./gradlew build` — SUCCESS; `rebellion-core` shadowJar produced.

**Docs + CI (this session)**
- `AGENTS.md`, `README.md`, `docs/ARCHITECTURE.md`, `docs/MASTER_LEDGER.md`,
  `docs/REFERENCE_LEDGER.md`, `docs/DATA_MODEL.md`,
  `docs/COMPATIBILITY_MATRIX.md`, `docs/CONFIGURATION.md`,
  `docs/COMMANDS_AND_PERMISSIONS.md`, `docs/STATE_MACHINES.md`,
  `docs/TEST_PLAN.md`, `docs/OPERATIONS.md`, `docs/RECOVERY.md`,
  `docs/SECURITY.md`, `docs/LICENSES.md`,
  `docs/DELUXEMENUS_COMPATIBILITY.md`, ADRs 0001–0004,
  `.github/workflows/ci.yml`.

**Next task (session 3)**
- Phase 2 vertical slice: `rebellion-items` module — PDC item identity,
  rarity registry with per-material resolver + COMMON fallback, lore service
  with de-duplication, talisman tier scaffolding, DeluxeMenus-shaped item
  codex menu, config validation, and the first module-bootstrap integration
  with Core's service registry.

---

## 2026-10-28 — Session 2 (continued): Phase 2 first vertical slice — rebellion-items

Branch: `develop`. Author: Abasing.

**Implemented (module `rebellion-items`, plugin JAR RebellionItems)**
- Rarity axis: `Rarity` enum (9 canonical keys), `RarityRegistry` with
  data-driven styles + per-material overrides + safe COMMON fallback for
  unmapped materials (incl. future versions); `RarityLoader` with injected
  material validator (serverless-testable), aggregated precise issues.
- Item identity contract: versioned PDC keys (`rebellion:schema|rarity|
  item_id|instance_id|talisman_tier`); `ItemIdentity.parse` fails closed on
  unknown schemas, malformed ids, unknown tiers and non-canonical instance
  UUIDs. Hardened during testing: `UUID.fromString` accepts padded
  non-canonical forms (probed on JDK 25), so parsing now requires a
  `toString()` round-trip equality check.
- Lore annotation: generic `LorePlanner` (marker-based de-dup, top/bottom
  position, removal pass, human-lore preservation) + `AnnotateService`
  (Bukkit adapter: preserves display name/enchants/CMD/other PDC/amounts;
  skips stacks with unknown schemas — fail closed) + `AnnotationListener`
  (config-gated join normalization and pickup flows using the supported
  `PlayerAttemptPickupItemEvent`; the classic pickup event is deprecated in
  26.3 — verified by reflection probe).
- Menu engine: `MenuLoader` (DeluxeMenus-shaped subset, aggregated
  validation, per-field diagnostics), `MenuRegistry` (gui_menus registry,
  traversal rejection, atomic reload keeping previous menus on invalid
  candidates), `MenuEvaluator` (requirements before actions; console actions
  double-gated), `MenuHost` (InventoryHolder; cancels every click/drag;
  sounds via registry keys not deprecated constants), bundled menus
  (item codex 27 slots, talisman guide 18 slots).
- Module bootstrap: config/messages/rarities/menus loading with bundled
  resource copy, Core ModuleRegistry integration via `RebellionApi`,
  AnnotateService published through the ServicesManager; commands
  `/rebellionitems inspect|annotate|reload|status` and `/rebellionmenu`.

**Commands/results**
- `./gradlew build` — SUCCESS (all three modules).
- `./gradlew test --rerun-tasks` — 62+41 tests, 0 failures.
- Serverless-verification probes (JDK 25): Material/ItemMeta require server
  registries (ExceptionInInitializerError) → Bukkit-facing code kept as
  thin adapters; `UUID.fromString` leniency probe → canonical check added;
  pickup-event deprecation probe → correct event chosen.
- MockBukkit web check: latest supports MC 1.21 only — not usable for 26.3;
  recorded in TEST_PLAN as a documented gap with the manual gate instead.

**Honest gaps (tracked, not hidden)**
- Annotation flows still missing: block drops, crafting/smithing output,
  container-access normalization (config switches exist for none of them;
  join + pickup only). Listed as follow-up in MASTER_LEDGER.
- `open_command` per-menu registration not implemented (parsed/validated
  only); menus open via `/rebellionmenu <id>` — documented deviation.
- No PAPI item placeholders yet (`%rebellion_rarity_<material>%` etc.).
- Live-server behavior (ItemMeta mutation, inventory click safety) is a
  manual Paper-26.3 checklist gate (TEST_PLAN).

**Next task**
- Complete the remaining annotation flows (drops, crafting output,
  container access) behind explicit config switches; add PAPI item
  placeholders; then Phase 3 (teams) planning ADR.

---

## 2026-10-28 — Session 2 (continued): remaining annotation flows + module placeholder routing

Branch: `develop`. Author: Abasing.

**Implemented**
- Annotation flows completed (all opt-in, default off): block drops
  (`BlockDropItemEvent`), crafting output (`CraftItemEvent`), smithing output
  (`PrepareSmithingEvent`), container open (`InventoryOpenEvent`, one
  bounded pass). Every handler visits only the stacks the event carries; no
  scans, no per-tick work.
- Placeholder contributor architecture: `PlaceholderContributor` added to
  RebellionAPI; Core's `RebellionExpansion` refactored to delegate to
  `CorePlaceholders`, which owns the four core parameters and routes unknown
  ones through all ServicesManager-registered contributors (self-excluded to
  avoid recursion). RebellionItems registers `ItemsPlaceholders` providing
  `%rebellion_rarity_<MATERIAL>%` (pure registry read; junk input → null).
- Bug fix found during refactor: `RebellionCorePlugin` registered its
  database-bootstrap failure callback before `CoreState` was assigned — an
  async completion could NPE. Bootstrap now starts after state construction;
  `whenComplete` semantics keep the failure path identical.
- Tests: ItemsPlaceholdersTest (4 tests) + new ItemsConfigTest cases for the
  individual flow switches.

**Commands/results**
- `./gradlew build` — SUCCESS.
- `./gradlew test --rerun-tasks` — 110 tests, 0 failures.

**Next task**
- Custom item registry (`items.yml`: named custom items, talisman families
  with NORMAL/ARTIFACT/RELIC examples), admin inspection views over annotated
  inventories, then Phase 3 (teams) ADR + module scaffold.

---

## 2026-10-28 — Session 2 (final): custom item registry (items.yml)

Branch: `develop`. Author: Abasing.

**Implemented**
- `items.yml` definitions: named custom items with material, rarity,
  optional talisman-tier (normal/artifact/relic — separate axis), MiniMessage
  display name/lore, glint, and `unique` (per-creation instance_id).
  `CustomItemLoader` validates ids/materials/rarities/tiers with aggregated
  precise issues; bundled file ships a three-tier Haste talisman family plus
  a named banner to demonstrate the contract.
- `CustomItemFactory` — the only sanctioned creation path for plugin-created
  custom items (verifies base material exists on this server version,
  fail-closed otherwise; stamps the full identity contract + instance id).
- `/rebellionitems give <defined-id> [player]` — a RESTRICTED mint: defined
  items only (no arbitrary combinations), admin permission, console requires
  an explicit player, every give logged. Tab-completes defined ids.
- Plugin permission surface updated (`rebellion.items.admin.give`).

**Commands/results**
- `./gradlew build` — SUCCESS. `./gradlew test --rerun-tasks` — 119 tests,
  0 failures.

**Next task (for the next session)**
- Admin audit view over inventories (list annotated/unique stacks),
  per-menu open_command registration, then Phase 3 (teams): ADR-0005 team
  schema + rebellion-teams module (atomic membership, invites, friendly-fire
  attribution, chat).

---

## 2026-10-28 — Session 3: Phase 2 closure + Phase 3 (teams)

Branch: `develop`. Author: Abasing.

**Implemented**
- Phase 2 follow-ups: `/rebellionitems audit <player>` (live read-only view of
  identity-tagged stacks across armor/offhand/storage/ender chest with
  server-free InventoryAudit planner) and per-menu `open_command` commands
  via `MenuCommandBridge` (Paper `Server#getCommandMap`; duplicate refusal,
  collision refusal, clean reload/disable; registry rejects duplicate
  open_commands across menus atomically). 7a19f18.
- ADR-0005: module-owned late migrations (`MigrationSpec`,
  `RebellionDatabase.applyModuleMigrations`); `MigrationRunner.applyModule`
  with idempotent registration, checksum verification, downgrade refusal;
  DatabaseService gates on the bootstrap future and applies on the bounded
  executor. +3 DatabaseServiceTest cases.
- ADR-0006 + rebellion-teams module: teams schema v1 (portable DDL, UUID
  keys, UNIQUE backstops, cascade disband, surviving history),
  TeamService (atomic mutations with in-transaction rank checks + history row,
  typed outcome mapping, cooldowns + invite rate limits persisted, cache
  refresh composed into caller futures), TeamCache hot path,
  FriendlyFirePolicy matrix + attribution adapter (player/projectile/pet/
  cloud/TNT verified against paper-api 26.3), chat channels
  (global/team/proximity) over AsyncChatEvent with rate gate, /team + /chat
  commands (online-only target resolution), teams placeholders via the new
  player-aware `resolveFor` contributor default method. dcd4363.
- Test suite: TeamServiceIT (16 real-SQLite integration tests incl. the
  double-accept race, joins-while-full, rank matrix, disband cascade,
  cooldowns via fixed clocks, cache-mirrors-authority),
  FriendlyFirePolicyTest (9), ChatRouter/TeamCache/TeamNames/TeamsConfig
  tests. Service fixes found by the tests: cache refreshes composed into
  returned futures; re-invites bypass the queue cap. 5bc622f.

**Commands/results**
- `./gradlew build` — SUCCESS. `./gradlew test --rerun-tasks` — 173 tests,
  0 failures, stable (core 44, items 88, teams 41).

**Next task (for the next session)**
- Phase 4 (wards): ADR-0007 ward schema + rebellion-wards module (spatial
  index, calibrated distances — km display disabled until configured —
  enter/leave state tracking, alert cooldowns, countermeasures config).
- Optional polish: teams command regression test via a command-layer harness;
  verify /team history pagination; live-server smoke checklist entry in
  docs/TEST_PLAN.md.

---

## Session 4 — Phase 4: wards (no-claim watch wards, calibrated distance, intel)

**Scope (master prompt §18 Phase 4 + §5.2)**: no-claim wards, calibrated
distance service with the kilometer gate, outsider alerts with spam
protection, countermeasures (dwell, masking, decoys, channels), optional
map-marker interface, measured lookup costs, and a documented
wards-do-not-protect-land statement.

**Work**
- API contracts: `TeamAffiliationService` (cache-only team lookups for
  sibling modules; teams registers the adapter over TeamCache) and
  `MapMarker`/`MapMarkerProvider` (privacy-gated web-map surface).
  9a96976.
- rebellion-wards module: anchor-bound `Ward` model with no protection
  surface; `WardsConfig` validation with the kilometer map shipping EMPTY;
  `DistanceService` (horizontal-only, cross-world = empty, km gate);
  per-world uniform-grid `WardSpatialIndex` (one-cell queries, COW
  snapshots, race-safe cell cleanup); pure `WardDetector` state machine
  (enter/leave, per-pair cooldowns, dwell, masking, decoys);
  `MovementFeed` sampling + async sweep; `AlertRouter` channels with owner
  fallback; anchor break/explosion listeners (the no-claim escape hatch);
  `rb_wards` schema v1 with UNIQUE(owner, name_norm) + UNIQUE(world, x, y,
  z) backstops and in-transaction caps; /ward + /wardadmin; own-network-only
  placeholders; privacy-gated marker provider. 9a96976, 3c1f69c.
- Test suite: 83 tests. WardDetectorTest (21) under fixed clocks,
  WardServiceIT (14, real SQLite) with concurrent name/anchor/cap races,
  spatial-index no-miss property vs linear scan (500 wards / 3000 probes,
  fixed seed), calibration-gate formatting, alert routing + rendered
  component carries no click events, feed/masking/placeholder/marker
  semantics. Tests caught two production defects: the cooldown sentinel
  (first alert suppressed within 300 s of epoch 0) and the km integer
  formatting threshold; removed a misleading cross-world-prone overload.
  3c1f69c.
- Docs: ADR-0007, PERFORMANCE.md with measured numbers (feed mark ~50 ns,
  sweep slice 273-671 ns/player at 200-2000 wards), DATA_MODEL/
  COMMANDS_AND_PERMISSIONS/CONFIGURATION/ARCHITECTURE wards sections,
  MASTER_LEDGER updated (incl. fixing the stale Phase 3 roadmap row).

**Commands/results**
- `./gradlew build` — SUCCESS. `./gradlew test --rerun-tasks` — 261 tests,
  0 failures, stable (core 44, items 88, teams 46, wards 83).
- `./gradlew :rebellion-wards:wardBenchmark` — measured numbers recorded in
  docs/PERFORMANCE.md.

**Next task (for the next session)**
- Phase 5 (dragons): minimal 26.3 controller spike FIRST (passenger/
  flight/death behavior proof) + test report before any production dragon
  controls; then the 20-lineage registry, gateway issuance flow, unique
  egg minting.
- Optional polish: live-server smoke checklist entries for teams + wards in
  docs/TEST_PLAN.md; command-layer regression harness shared by modules.

---

## 2026-10-28 — Session 5: Phase 5 continued — lineage registry persistence layer

Branch: `develop`. Author: Abasing. Baseline: develop @ dbd69e3, 261 tests
green (verified with `./gradlew test --rerun-tasks` before any change).

**Context**
- Master prompt re-read from remote (unchanged, 615 lines). ADR-0008
  explicitly blesses the 20-lineage registry schema as persistence-only
  serverless work that does not depend on the live-server proofs; the
  live experiment set (mount feel, steering, death timing, gateway/egg
  emission, pearl healing) remains the gate for production dragon
  controls and was NOT attempted in this environment (no live server).

**Done**
- ADR-0009: dragon lineage registry design — gateway-backed 20-slot
  registry (UNIQUE origin_world+gateway_x/y/z as the issuance
  idempotency key / gateway-validity guard; UNIQUE slot ordinals;
  UNIQUE entity binding), compare-and-set state transitions for
  hatch/death (egg_instance / entity_uuid pinned), recovery mailbox
  (park/claim/route), audited terminal retirement, slot+gateway stay
  consumed on retire, cap double-enforced (config validation 1..20 at
  load + runtime clamp in the mint transaction).
- rebellion-dragons persistence layer: `Lineage` record (invariants:
  HATCHED requires entity, owner null only when RETIRED),
  `LineageState`, `LineageOutcome`/`LineageResult` (with explicit
  `mutated` flag so idempotent no-ops are distinguishable from real
  state changes), `LineagePolicy` (HARD_CEILING 20),
  `DragonsConfig` (minimal by design — only `dragons.max-lineages`;
  controller-phase tuning values deliberately absent until proven),
  `DragonsSchema` v1 (`rb_dragons_lineages` + `rb_dragons_audit`,
  portable DDL, module key `dragons`), `LineageStore` (CAS statement
  constants, dialect-spanning unique-violation classification),
  `LineageService` (transactional authority + by-id/by-egg/by-entity/
  by-gateway mirror; bounded fresh-transaction retry for slot-ordinal
  races; audit row written in the same transaction as every mutation).
- Security-shaped design decisions surfaced during implementation and
  locked in: hatch accepts ONLY `EGG_HELD` (a pre-park cloned egg can
  never hatch out of the recovery mailbox — claim/route re-issue the
  instance); claim refusals do not reveal the recorded claimant;
  `byEggInstance` resolves only while the lineage actually IS an egg;
  retirement keeps the last egg instance for forensics but gates all
  use on state.
- Tests: `LineageServiceIT` — 18 integration tests over real SQLite via
  Core's actual DatabaseService: sequential slot minting + audit trail;
  argument validation before DB; same-gateway idempotency (sequential,
  different victor, concurrent race → exactly one lineage); cap races
  (3-of-5 distinct gateways, cap-after); hard-ceiling clamp with a
  bypassing config (fills 20, refuses the 21st); hatch happy path +
  concurrent clicks (one winner); clone/stale eggs always lose
  (pre-hatch clone, post-hatch original, post-death stale stamp);
  recovery direct-hatch refusal; death→killer transfer with fresh
  stamp and mirror cleanup; simultaneous owner+dragon death → one egg
  + one audit row; duplicate/stale death events as typed no-ops;
  no-killer recovery with owner as claimant + wrong-claimant refusal;
  idempotent parking; admin route (held+recovery, audited, never a
  living dragon); retirement terminality (everything refused, slot and
  gateway stay consumed); full restart round-trip (all four states,
  identity stamps, lifecycle continues, audit persists).
  `DragonsConfigTest` — 7 tests: default 20, configurable lower, loud
  errors at 0 and 21, runtime ceiling clamp, shipped resource valid.
- Docs: ADR-0009; DATA_MODEL (registry + audit tables + structural
  guarantees); STATE_MACHINES (lineage lifecycle transition table with
  CAS guards and idempotency outcomes); ARCHITECTURE (dragons module
  section, explicit "persistence layer only" scope); CONFIGURATION
  (dragons.max-lineages contract); TEST_PLAN (automated coverage note +
  live gates for the lineage flow); MASTER_LEDGER (6 new tested rows,
  Phase 5 roadmap updated, truthful limitation notes: no plugin wiring
  yet, physical egg protection is controller-phase).

**Commands/results**
- `./gradlew :rebellion-dragons:test` — 28 tests, 0 failures (18 IT +
  7 config + 3 spike), stable across `--rerun-tasks`.
- Full-suite verification and the commit/push happen at session close
  (see next entry append if the session continues).

**Honest limitations**
- No live Paper 26.3 server in this environment: the ADR-0008 experiment
  set is still open and blocks the controller, egg-item protection
  listeners, commands and plugin wiring. Nothing here claims verified
  Paper 26.3 runtime behavior.
- The persistence layer is reachable only through the tested service
  surface right now; the module builds but is not an installable
  feature plugin (deliberate — no placeholder plugin presented as
  functionality).

**Next task (for the next session)**
- Phase 5 remaining, in order: run the ADR-0008 live experiments on a
  real Paper 26.3 server and record results in TEST_PLAN; then the
  controller slice (mount/steering/death presentation) + egg-item
  protection listeners + `/dragon` command family + DragonsPlugin
  wiring over the existing LineageService.
- Serverless parallel candidate if live work is blocked: Phase 6
  groundwork — ADR-0010 dimension-artifact state machine schema (same
  persistence-first style), or the teams/wards live-smoke checklist
  expansion.
