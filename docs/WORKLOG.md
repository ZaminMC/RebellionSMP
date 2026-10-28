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

## 2026-10-28 — Session 6: release pipeline — numbered dev prereleases from develop

**Context**

- Project-owner instruction: add GitHub automation so every build is
  released automatically as a **pre-release, never `latest`**, mirroring
  ZaminMC/ZaminTorch's build automation. Repository had CI only (build +
  artifact upload): 0 tags, 0 releases, no download path.

**Work**

- Studied ZaminTorch's `.github/workflows/release.yml` in full (tag ledger,
  pre-build bump sync with `[skip ci]` loop-breaker, lightweight tags,
  `gh release create --prerelease` through a PAT secret, orphan-tag repair,
  idempotent re-runs) and adapted it to this repository's build
  (ADR-0010):
  - numbering `vX.Y.Z-dev.N` from a single tag ledger — one numbering
    across base version bumps (pre-flight-tested: `0.1.0-dev.2` →
    `0.2.0-dev.3`);
  - version stamping is one line (`gradle.properties`), propagated into
    every `plugin.yml` by `processResources`;
  - artifacts discovered, not hardcoded: exactly the modules shipping
    `plugin.yml` are staged into `build/release/` with a descriptor
    assertion per jar (`rebellion-dragons` is reported as
    built-but-not-installable; future modules need zero pipeline edits);
  - `ci.yml` trimmed to PRs + `main` push validation so a develop push is
    never built twice.
- Created the `ABASING_TOKEN` Actions secret (classic PAT of the Abasing
  account) so the pipeline's bump commit, tag, and release are authored as
  Abasing, never `github-actions[bot]` (ZaminTorch parity).
- Docs: ADR-0010; README dev-builds section + stale module table fixed
  (teams/wards were listed as "not yet built"); OPERATIONS "Obtaining
  builds"; MASTER_LEDGER row; AGENTS.md auto-release rule for future
  sessions; TEST_PLAN environment + verified gate.

**Commands/results**

- Pre-flight (local): YAML parse of both workflows; ledger dry-runs
  (first build → `v0.1.0-dev.1`, next → `dev.2` with prev linkage, base
  bump → `v0.2.0-dev.3`); staging dry-run against real build/libs →
  `STAGED=5 SKIPPED=rebellion-dragons`.
- Baseline `./gradlew build --no-daemon` — exit 0 (289 tests) before any
  change.
- **Live verification (run #1, `ef4077c`, conclusion: success):** push to
  develop auto-cut `v0.1.0-dev.1` — release carries the 5 installable
  JARs, `prerelease: true`, author Abasing; `releases/latest` returns 404
  (never latest); bump commit `ece22d4 … [skip ci]` pushed by the
  pipeline; the run triggered by that bump push completed without cutting
  another release (git-verified: HEAD stayed at `ece22d4`, no `dev.2`
  tag). All assertions recorded in TEST_PLAN.

**Honest limitations**

- The Actions **API** (not the web UI, not git) intermittently rejected
  the PAT with `401 Bad credentials` while `rate_limit` and release
  endpoints accepted it (2026-10-28). The pipeline itself succeeded.
  The PAT is long-lived and previously flagged as exposed — rotating it
  and updating the `ABASING_TOKEN` secret is recommended; pipeline impact
  of rotation is one secret update (ADR-0010 documents this).
- `rebellion-dragons` ships no plugin descriptor yet (ADR-0009 scope), so
  its jar is deliberately absent from releases — noted in the release
  notes automatically.
- Stable/`latest` releases from `main` remain a manual reviewed process;
  automating them is explicitly out of scope (ADR-0010).

**Next task (for the next session)**

- Phase 5 remaining: run the ADR-0008 live experiments on a real Paper
  26.3 server and record results in TEST_PLAN; then the dragons controller
  slice + egg-item protection listeners + `/dragon` commands +
  DragonsPlugin wiring over the tested LineageService (gaining
  `plugin.yml` automatically adds its jar to every future release).
- Serverless fallback: Phase 6 groundwork — ADR-0011 dimension-artifact
  state machine schema (persistence-first style).

## 2026-10-28 — Session 7: headless Paper 26.3 boot smoke — five live defects found + fixed

**Context**

- New PAT provided by the project owner (old one intermittently rejected
  by the Actions API and previously flagged as exposed). Rotated before any
  work: git remote updated, `ABASING_TOKEN` secret updated via the sealed
  box API (HTTP 204), fetch/pull verified against develop.
- Master prompt re-read from remote: unchanged (615 lines, hash-verified).
- Documented next task was ADR-0008 live experiments (needs interactive
  players — genuinely manual) or serverless Phase 6 work. Instead, this
  session removed the long-standing "no live server" blocker itself: a
  headless boot smoke is possible in this environment.

**Work**

- Downloaded the pinned server, Paper 26.3 build 226, via the fill v3 API
  (v2 is sunset): `paper-26.3-226.jar`, sha256
  `1373c651510d4aeee7e7903f3cfd7003919c1a364528dbe124a4ae40e11a766e` —
  verified.
- Boot smoke pass 1 used the RELEASED v0.1.0-dev.2 jars (what users
  actually download). API + Core enabled cleanly (config generation,
  shaded+relocated HikariCP pool visible in logs, SQLite WAL,
  PlaceholderAPI-absent handled). Teams, Wards, Items ALL FAILED to enable.
- Root-caused five defects (details in ADR-0011):
  1. `YamlConfiguration.load(String)` = path, not content → Teams/Wards
     threw FileNotFoundException whose message was the file content.
     Fixed with `loadFromString` in both plugins.
  2. Items never extracted bundled `menus/*.yml` → enable refused.
     Fixed: copy-if-missing extraction in `ItemsModuleState.bootstrap`,
     keyed by the config registry, traversal-guarded, no-clobber.
  3. `onDisable` rewrote FAILED → `DISABLED "shutdown"`, hiding failures.
     Fixed in all three plugins: FAILED is terminal until re-enable.
  4. Failed Items bootstrap never registered with the module registry.
     Fixed: register + mark FAILED like teams/wards.
  5. Paired `</state>` closing tag leaked into `rebellion status`.
     Fixed: bare `<state>` placeholder (comment documents the rule).
- Re-verified with fixed local jars — boot smoke pass 2 + restart pass:
  all five modules enable in order, menus extracted, `/talismanguide` +
  `/itemcodex` registered via the command bridge, teams+wards migrations
  apply (v1) and are restart-idempotent, console surface clean
  (`rebellion status` no stray tags; `modules` all operational; `diagnose`
  writes report INC-20261028-004403-000001), configs preserved across
  restart, graceful reverse-order shutdown with Hikari close.
- Committed the harness as `scripts/boot-smoke.sh` (portable: env-var
  driven, checksum-verified fetch, FIFO console) and documented the gate in
  TEST_PLAN ("Verified on Paper 26.3" section), COMPATIBILITY_MATRIX
  (Paper row now boot-verified), MASTER_LEDGER (harness row; API/Core
  boot-verified rows; phase roadmap 1-4 live-verification notes; honest
  limitation: bundled messages.yml not yet wired in feature modules), and
  ADR-0011.

**Commands/results**

- `./gradlew build --no-daemon` — green after fixes; `./gradlew test
  --rerun-tasks` — green (all suites re-ran).
- Boot smoke: 2 clean boots + 1 restart against paper-26.3-226.jar;
  log excerpts: "RebellionTeams … enabled (max members 8, proximity 100.0
  blocks)", "RebellionWards … enabled (0 wards loaded … Wards report
  information only; they do not protect land)", "RebellionItems … enabled
  (mode: CUSTOM_ONLY, menus: 2)", "Migrations applied: 2 across 2
  module(s)", "Done (12.755s)".
- PAT rotation verified: secret PUT 204, `git fetch`/`pull` via new token,
  release pipeline run on push = the end-to-end token proof (see below).

**Honest limitations**

- Boot-level only: no player joined the server; menus, invites, ward
  walking, combat and the ADR-0008 dragon experiments remain interactive
  gates.
- `/rebellion reload`, deliberately-broken-config disable path, and
  PlaceholderAPI-present behavior are still unverified (still in the
  manual checklist).
- Feature modules still ship `messages.yml` without extracting/rendering
  it (hard-coded copy in use) — follow-up slice, recorded in the ledger.
- The scratch server dir (`/home/z/my-project/paper-test` locally) is
  disposable and never committed.

**Next task (for the next session)**

- Wire feature-module `messages.yml` through the Core messages pattern
  (extraction + rendering), then re-run the boot smoke.
- Phase 5 remaining: ADR-0008 live experiments (interactive), then the
  dragons controller slice + wiring.
- Serverless fallback: Phase 6 groundwork — ADR-0012 dimension-artifact
  state machine schema.

## 2026-10-28 — Session 8: module message catalogs (ADR-0012) — every module's player feedback now renders through messages.yml

**Context**

- Session 7's documented next task, step 2: "wire feature-module messages.yml
  via the Core messages pattern + re-run boot smoke". Items/Teams/Wards had
  hard-coded feedback strings; Items even bundled a messages.yml nothing
  read. Step 1 (verify dev.3 published) confirmed first: prerelease
  v0.1.0-dev.3 is on GitHub with the five fixed jars, authored as Abasing.
- Baseline before any change: `./gradlew build` green (289 tests); master
  prompt re-read from remote, unchanged (615 lines, md5 match); develop
  pulled to the pipeline's `b3d1d3c` bump commit.

**Implemented (ADR-0012)**

- `MessageCatalog` in rebellion-api (`api.messages`): one shared
  implementation of extraction (bundled → data folder, no clobber),
  admin-edit merge (user wins; missing keys → bundled default + warning;
  invalid template → per-key bundled-default fallback + precise warning),
  strict closed-tag validation with the bare-placeholder catch-all, and
  MiniMessage rendering (`raw`/`msg`/`prefix`/`template`). Resource names
  are traversal-guarded. Core's `CoreMessages` became a thin adapter over
  it — public surface and call sites unchanged.
- Feature-module wiring: RebellionItems (ItemsModuleState loads messages
  with everything else; reload applies them atomically),
  RebellionTeams (enable-time load; TeamCommand/ChatCommand/ChatListener),
  RebellionWards (enable-time load; WardCommand/WardAdminCommand/
  AlertRouter/AnchorBreakListener). Items' bundled file extended to the
  full key set; Teams and Wards gained bundled messages.yml files.
- Deterministic outcome keys `team.<action>.<outcome>` /
  `ward.<action>.<outcome>` (enum kebab-case); shared texts hoisted
  (`not-in-team`, `name-invalid`, `common.unavailable` for DB failures).
  All dynamic values now travel as `Placeholder.unparsed`/`component` —
  tag/click-event injection is structurally impossible; the old
  angle-bracket-stripping `escape()` helpers are gone. Chat channel
  formats are templates over `<sender>`/`<message>`.
- Two latent rendering defects found + fixed during the migration: wards
  `list.empty` shipped HTML entities that rendered literally in game
  ("&lt;name&gt;"), and `admin.list-line` carried an unclosed `<gray>`
  that lenient MiniMessage had masked. New MiniMessage knowledge recorded:
  `\<` escapes a literal opening bracket; there is NO `\>` escape (the
  backslash stays literal) — verified against adventure-text-minimessage
  5.2.0 and pinned in MessageCatalogTest.

**Evidence**

- Tests: 307 green (api 8, core 44, items 91, teams 50, wards 86, dragons
  28), stable across `./gradlew test --rerun-tasks`. New:
  MessageCatalogTest (8, API), ItemsMessagesTest (3), TeamsMessagesTest (4),
  WardsMessagesTest (3) — the per-module tests strict-parse each shipped
  file and pin every rendered key + every (action, outcome) pair, so
  code/yml drift fails the build, not the game. Teams key-coverage test
  caught a real key mismatch (create-cooldown) during development; wards
  coverage caught the unclosed `<gray>`.
- Live boot smoke on Paper 26.3 build 226 (fresh `build/paper-smoke`):
  all five modules enable; messages.yml extracted ×4 on first boot;
  console feedback renders through the catalogs (`rebellionitems status`,
  `wardadmin list`, console `team` → prefixed player-only notice); an
  admin edit of `status.header` applied live via `/rebellionitems reload`;
  a deliberately unclosed template produced the precise WARN
  ("status.header: invalid MiniMessage … using bundled default") while
  the module stayed operational and the bundled default rendered;
  restart kept the admin file byte-identical (md5) with no re-extraction;
  graceful stop. Full checklist in docs/TEST_PLAN.md.

**Artifacts**

- RebellionAPI/Core/Items/Teams/Wards jars rebuilt (v0.1.0-dev.3 stamps);
  install order unchanged; no new module.

**Persistence/config**

- No schema changes. New per-module `messages.yml` files extract on first
  boot. Items reloads messages with its `/rebellionitems reload`;
  Teams/Wards messages load at enable only (restart to apply message
  edits there — documented in CONFIGURATION.md).

**Honest limitations**

- Rendering verified at the console surface; a player has still never
  joined the smoke server — menus, invites, ward alerts delivered to real
  players and the chat formats in actual chat remain part of the
  documented interactive pass.
- The dragons module still has no plugin wiring (unchanged; gated on
  ADR-0008 live experiments).
- Session 7's roadmap note reserved "ADR-0012" for the artifact state
  machine; that work is renumbered to ADR-0013, not dropped.

**Next task**

- Phase 5 remaining: run the five ADR-0008 live dragon experiments
  (interactive; the harness now exists to host them), then the dragons
  controller slice + plugin wiring. Serverless fallback: Phase 6
  groundwork — the dimension-artifact state machine (now ADR-0013) in the
  tested style of ADR-0009.
