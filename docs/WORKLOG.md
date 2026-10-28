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

## 2026-10-28 — Session 9: ADR-0008 live experiments executed — the dragon control surface is now measured

**Session context**

Token rotation interrupted by a lost context window: the rotated PAT
value existed only in the conversation and was never staged to disk
(swept history, tmp, tool-results, repo). Nothing was broken — the
session-7 token remained fully valid (API 200, full scopes), the
`ABASING_TOKEN` secret (updated 00:32Z) had just driven the successful
dev.4 release, so development proceeded on the working credentials and
the swap is re-runnable whenever the new PAT is re-supplied.

**What ran**

The five ADR-0008 experiments plus the §5.6 implementation-gate extras
(fireball launch, chunk unload/reload) were executed against a real,
clean Paper 26.3 build 226 server through a new committed harness:
`rebellion-dragons/src/experiments` (a non-shipped `experiments` source
set; `./gradlew :rebellion-dragons:dragonExperimentJar` builds
`DragonExperiments.jar` into `build/experiment-libs/`, deliberately
invisible to release staging; console-driven via the session-7
boot-smoke script). Raw evidence: `docs/evidence/adr0008-experiments.log`.

**Measured results (full analysis in ADR-0008)**

- E1 mount: `addPassenger` works, `EntityMountEvent`/`EntityDismountEvent`
  fire; server-side passenger anchor is dragon origin + (0, 3.0, 0);
  `setAware(false)` + `HOVER` is a 0.00-drift stable platform over 5 s.
- E2 steering: velocity writes = 0.00 displacement (dead end); Paper
  pathfinder returns false and moves nothing (dead end);
  `setPodium(target)` + `FLY_TO_PORTAL` flies the dragon ~16 blocks/s
  toward the configured point — closed-loop waypoint steering works
  with no NMS.
- E3 death: sourceless `damage()` is IGNORED by dragons (three runs);
  `kill()` fires `EntityDeathEvent` the same tick (droppedExp=500, no
  item drops, killer=null) and the corpse lingers 199 ticks before
  `EntityRemoveEvent(cause=DEATH)` — the death-presentation window.
- E5 heal: `heal(25)` on a staged wound is exact (170→195), overheal
  clamps to max, no phase interference.
- E6 fireball: `setDirection` alone leaves a `DragonFireball`
  motionless — the acceleration/power vector launches it;
  `EnderDragonFireballHitEvent` fires with the `AreaEffectCloud`
  (cancellable) and zero block damage.
- E7 persistence: teleport + chunk unload fires
  `EntityRemoveEvent(UNLOAD)`; reload restores the same UUID with
  health/phase intact (`EntityAddToWorldEvent`).
- E4/E4b (vanilla egg/gateway emission): headless-blocked and
  bytecode-proven — `EnderDragonFight.tick()` only runs while a player
  is in the End, and every acquisition route (summon-link,
  `setRespawnPhase`, the previouslyKilled dance, `generateEndPortal` +
  crystal ritual, staged-dragon adoption) funnels into that gate. The
  committed harness is ready for the interactive pass
  (`dxexp victory` / `dxexp victory2`).

**API corrections recorded** (ADR-0008 addendum): 26.3 exposes
`DragonBattle.getGatewayCount()`/`spawnNewGateway()`/
`generateEndPortal(boolean)`/`setPreviouslyKilled(boolean)`;
`EnderDragon.getPodium()/setPodium()`; `EntityRemoveEvent` is
`org.bukkit.event.entity.*`. Also: `getEndPortalLocation()` is null
pre-victory; the boot-smoke flat preset yields a void world on 26.3.

**Honest limitations**

- No player ever joined: client-side rider render/feel and the vanilla
  first-victory emission remain the two documented interactive gates.
- The dragons controller, plugin.yml, commands and egg-item protection
  are still unwired — the next unit, now unblocked on the proven
  server-side surface.

**Next task**

- Phase 5 controller slice over `LineageService` informed by the
  measurements: DragonsPlugin wiring (plugin.yml makes the jar
  installable and release-staged automatically), boss-victory mint
  hooks, pet controller (mount platform, podium steering, fireball
  token bucket, pearl feeding), `/dragon` command family, egg-item
  protection listeners, tests. Then the interactive pass for the ride
  feel and E4/E4b.

## 2026-10-28 — Session 10: Phase 5 controller slice — RebellionDragons becomes the sixth installable plugin

**Session context**

Session-9's follow-up token rotation was again lost to a context-window
overflow (the new PAT existed only in the lost conversation; verified it
is nowhere on disk — shell history, /tmp, tool-results, staging swept).
The session-7 PAT remains fully valid (GET /user = 200 Abasing; drives
both the git remote and the `ABASING_TOKEN` secret, updated_at
00:32:28Z — a consistent, working credential pair), so per the
session-9 precedent development proceeded on it. The swap is re-runnable
whenever the new PAT is re-supplied; nothing is blocked.

**Protocol**

Pulled develop (b605d5f, dev.5 bump; already up to date), re-read the
remote master prompt (md5 a84a5323, unchanged, 615 lines), verified
v0.1.0-dev.5 published (prerelease, 5 jars, author Abasing), baseline
`./gradlew build` + forced test rerun: 307 tests green (api 8, core 44,
items 91, teams 50, wards 86, dragons 28).

**Implemented (34 new/changed files in rebellion-dragons)**

- **Schema v2** (additive `ALTER TABLE` migration): `loc_world`,
  `loc_x/y/z`, `last_persist_utc` — the controller's persistence
  snapshot. New CAS statements: `CAS_PEARL_HEAL` (cooldown-guarded,
  entity-pinned; retries are typed refusals before the pearl is
  consumed) and `CAS_SNAPSHOT` (entity-pinned; stale bindings are
  benign no-ops). `LineageService.pearlHeal` + `persistSnapshot` with
  the same publish/mirror discipline; `Lineage` record extended with
  the snapshot fields (all-or-none location invariant enforced in the
  constructor).
- **`DragonsConfig` full controller contract**: measured ADR-0008
  defaults (hatch health 200, pearl +25, fireball 30/60 s, water
  weakness 1.25 bounded 1.0..4.0, call range/cooldown, scan bounds),
  `ride.enabled: false` (the ride-feel interactive gate), every number
  range-checked with the bound named in the issue. `defaults()` factory
  pinned to the shipped config.yml by test.
- **`GatewayObserver` + `GatewayDiff` (ADR-0014, new)**: boss-victory
  minting = baseline annulus scan (async chunks + snapshots, off-thread
  block sweep) → cheap gateway-count poll → discovery scan → pure
  diff decision (emerging-only, registered-excluded, count
  cross-check, ambiguity/miss fail-closed) → `issueFromGateway` →
  stamped-egg delivery. No credited killer = no mint. Pets excluded
  even when the vanilla fight adopts them (findOrCreateDragon adopts
  strays in a ticking End — recorded in ADR-0008).
- **`PetController`** on the measured recipes: HOVER platform, closed-
  loop podium steering + phase guard, four-vector fireball recipe with
  per-dragon `FireballBudget` (pure, tested), pearl feeding behind the
  CAS, call/recall through the persisted location (async chunk load →
  resolve → teleport; vanished entities reconcile through the death
  CAS), mount/dismiss, water weakness, egg build/stamp/deliver with
  display name + lore through the catalog.
- **Listeners**: `BossVictoryListener` (battle-gate + pet exclusion +
  DragonEggFormEvent at the Paper FQN), `PetLifecycleListener`
  (direct kill → killer gets the egg; owner PvP death → killer inherits
  + death presentation `kill()` only after the CAS commits;
  EntityRemoveEvent cause != UNLOAD reconciliation; mount
  bookkeeping), `EggProtectionListener` (damage/combust/despawn/
  hopper/dispense/portal cancelled for live egg stamps; stale clones
  unprotected by design), `PetInteractListener` (pearl feed, water
  weakness, friendly-hit cancellation via UUID-tracked fireballs,
  phase guard).
- **`/dragon` + `/dragonadmin` + placeholders + `DragonsPlugin`**:
  hatch/status/call/mount/dismiss/attack/claim; admin list/info/route/
  audit/retire with the literal `confirm` keyword; `dragon_owned`,
  `dragon_id`, `dragon_health`, `dragon_count`, `dragon_cap`; full
  module wiring (config → messages → Core services → migrations v1+v2
  → mirror → controller → listeners → tasks → commands → placeholders
  → module health), 30 s persistence sweep + final flush in onDisable,
  steer task registered only while riding is enabled.
- **`messages.yml`** (ADR-0012 contract): every rendered key pinned by
  `DragonsMessagesTest`; dead keys deliberately absent.

**Evidence**

- `./gradlew build` green; **336 tests** stable across the suite (api 8,
  core 44, items 91, teams 50, wards 86, **dragons 57** — was 28).
  New: `FireballBudgetTest` (6), `GatewayDiffTest` (8),
  `DragonsMessagesTest` (3), `DragonsConfigTest` rewritten (13, was 6:
  shipped-file validity, defaults-factory lockstep, measured values,
  all bound checks), `LineageServiceIT` +3 (pearl-heal idempotency,
  entity-pinned snapshots, snapshot/pearl restart round-trip),
  `DragonApi263SpikeTest.controllerSliceSurface` (probe of every
  newly-used member).
- Boot smoke on Paper 26.3 build 226, upgrade-style on the existing
  smoke-server database: all six jars enable; dragons v0 → v2 migration
  applies in one boot and is a no-op on restart; config + messages
  extracted byte-identical (md5); console surface renders
  (player-only denial, empty list, retire-usage, modules all
  operational, diagnose clean); restart idempotent; graceful
  reverse-order shutdown with the final sweep; zero ERROR/SEVERE lines.
- **One live defect caught and fixed in-session**: the dragons jar
  shipped with an unexpanded `${version}` in plugin.yml (every other
  module had the processResources expansion; dragons had never shipped
  a descriptor). Added the same block, rebuilt, re-verified on the
  restarted server (`v0.1.0-dev.5` renders).

**Honest scope**

Console-surface + boot-level verification only: no player joined the
smoke server, no boss died, no egg was minted live. Player flows
(hatch/call/ride/feed/attack), the E4b vanilla first-victory emission
with real gateway coordinates, and the client ride feel remain the
documented interactive pass; riding ships disabled by config until the
ride-feel gate passes. Recorded risk: hatch spawns the entity before
the CAS binds it — a refused CAS removes the spawn, but a crash between
spawn and commit can leave one unbound vanilla dragon (the sweep never
touches unbound entities). Measured steering is ~16 blocks/s — the
"faster than unpowered elytra" §5.6 target is not yet met by the podium
primitive and stays on the interactive-pass list.

**Next task**

The interactive pass on the committed harness: one player joins the
boot-smoke server, flips `ride.enabled`, records the client ride feel
(E1/E2 client halves), then enters the End for `dxexp victory`/`victory2`
(E4b: emergence timing vs. the ADR-0014 baseline window, gateway ring
coordinates vs. the default scan bounds, trophy-egg flow alongside the
lineage mint). Serverless fallback if no player is available: Phase 6
groundwork — the three dimension-artifact state machines (ADR-0013,
ADR-0009 style) now that every module convention is proven twice over.

## 2026-10-28 — Session 11: PAT rotation completed + Phase 6 groundwork recovered, verified and landed (ADR-0013)

**Session context**

The token rotation pending since session 9 finally went through: the
user re-supplied the new PAT, it verified clean (GET /user = 200
Abasing, id 140362885, full classic scopes incl. repo/workflow/
admin:repo_hook), `ABASING_TOKEN` was re-encrypted and PUT over the
GitHub Actions secrets API (HTTP 204, updated_at 04:40:34Z), and the
git remote was re-pointed — fetch/push verified before the old pair
was left behind. The credential pair (remote + secret) is consistent
again; releases and pushes ride the new token.

The session then became a recovery: the previous session had started
the Phase 6 groundwork (the documented serverless fallback) and
overflowed its context window mid-flight, leaving the work uncommitted
— a complete `rebellion-artifacts` module, ADR-0013, and five doc
updates, but no README/ledger/WORKLOG pass and no commit.

**Protocol**

Pulled develop (be0e9e6, dev.6 bump; already up to date — the tree
changes were local), re-read the master prompt (md5 a84a5323
unchanged, 615 lines), audited the uncommitted work against §5.5 and
ADR-0013 before touching anything.

**Verified (the interrupted session's work, audited this session)**

- **ADR-0013** (`docs/decisions/ADR-0013-dimension-artifact-registry.md`):
  one registry table dimension-keyed (exactly three rows, seeded only
  by the idempotent load, no gameplay mint path, count != 3 fails the
  load), instance-stamped possession where every hand-change supersedes
  the live stamp (clones/stale copies resolve INSTANCE_STALE and can
  never move a row), five states (ORIGIN/HELD/LOST/RETURNING/SPENT)
  with every transition a compare-and-set, the return pipeline as two
  individually idempotent CAS steps (delay + active-event gated), and
  the Rebellion event as a singleton-guard CAS — once per occurrence,
  copy-proof without event-layer dedup, spend all-or-none in one
  transaction, complete-and-reconquer mints a new durable event id.
- **`rebellion-artifacts`** (11 main classes + config.yml, ADR-0009
  precedent: no plugin descriptor, not installable, exercised through
  the tested service surface): `ArtifactService` (transactional
  authority + post-commit mirror, the teams/wards/dragons strategy),
  `ArtifactStore`/`ArtifactsSchema` (four tables:
  rb_artifacts_registry, rb_artifacts_audit, rb_artifacts_events,
  rb_artifacts_event_guard; migration v1, portable SQLite/MySQL DDL),
  `ArtifactsConfig` (per-dimension origin anchors as config-owned
  data, return delay, event toggle, HOLDER-only scope — TEAM is a loud
  unimplemented error per §5.5's separate-option rule),
  `Artifact`/`ArtifactKey`/`ArtifactState`/`ArtifactOutcome`/
  `ArtifactResult`/`RebellionEvent` domain records.
- **Docs already written by the interrupted session** (verified
  consistent this session): ARCHITECTURE (+73), CONFIGURATION (+54),
  DATA_MODEL (+67), STATE_MACHINES (+78), TEST_PLAN (+18).
- **Tests**: `ArtifactServiceIT` (22, real SQLite through Core's actual
  DatabaseService): exactly-three seed + idempotency, config-owned
  anchor re-sync without touching gameplay state, capture/transfer/
  find races (cloned + forged stamps lose), idempotent loss, the
  return-delay gate + active-event refusal, arrival idempotency,
  Rebellion qualification (two-of-three and split holders refused),
  once-per-occurrence trigger idempotency, the disabled-toggle
  refusal, all-or-none spending (typed SPENT_CONFLICT on retry), guard
  release + second occurrence, admin routing of stuck states only,
  audit discipline (no-ops write nothing), the full restart
  round-trip. `ArtifactsConfigTest` (10) pins the shipped config.yml
  to the defaults factory, anchor/delay bounds and the TEAM refusal.

**This session's completions**

- README module table: RebellionArtifacts row (not yet installable) +
  removed artifacts from the "further modules" line.
- MASTER_LEDGER: six artifacts rows (registry, possession CAS, loss +
  return pipeline, Rebellion event, admin route + audit, config
  contract), Phase 6 roadmap row flipped from "planned" to the
  delivered persistence layer with honest remaining scope, session-11
  additions entry.
- Full build + forced test rerun: **368 tests green, 0 failures, 0
  skipped** (api 8, core 44, items 91, teams 50, wards 86, dragons 57,
  artifacts 32) — stable across the rerun; the six baseline modules
  are untouched (same counts as session 10).

**Honest scope**

The persistence layer is complete and tested but serverless: no
plugin.yml (installing it as a server plugin is not a supported
state), and the encounter/structure, physical item flows, catastrophe
effects (dimension instability, global warnings, maximum-health scars
— the Phase 9 profile surface), the world-wide purge power, commands
and `%rebellion_artifact_*` placeholders are controller-phase work,
exactly as ADR-0009 preceded the dragons controller. Nothing here has
been booted on Paper (there is nothing to boot), and no player flow
exists yet.

**Next task**

Either the interactive pass on the committed dragons harness (one
player joins the boot-smoke server: ride feel with `ride.enabled`
flipped, then the End for the E4b vanilla first-victory emission), or
the artifacts controller phase (encounter structure, physical item
flows, catastrophe effects, commands) — the persistence contract it
rides is now committed and green.

## 2026-10-28 — Session 12: the artifacts controller slice — RebellionArtifacts becomes the seventh installable plugin (ADR-0015)

**Session context**

Continued straight off session 11's committed persistence contract. The
documented next-task choice was the interactive dragons pass (blocked:
needs a live player) or the artifacts controller phase — the controller
phase proceeded, the same serverless-fallback discipline as before, with
every interactive gate documented honestly.

**Protocol**

Pulled develop (2e81bce, already up to date), master prompt md5
a84a5323 unchanged, baseline build 368 tests green.

**Decisions first (ADR-0015, new)**

- §1 the origin presentation is an ItemDisplay + Interaction pair,
  tagged, self-healing: the sweep reconciles loaded anchor chunks to
  exactly one pair per ORIGIN row; the pair is presentation, never
  state; no decorative encounter structure ships (recovery logic is the
  reconcile + return pipeline — the structure is operator map work).
- §2 item flows: possession changes ride the CAS (pickup/chest-take ->
  transfer|found), destruction means LOST not protection (the deliberate
  opposite of the dragons egg policy — artifacts are infinite-cycle
  assets with a return pipeline), hopper suction + crafting cancelled,
  chest storage legal.
- §3 the observer is a dumb post-HELD probe; the guard CAS does all
  dedup. Catastrophe surface in-slice: trigger broadcast, join notice,
  reminder + duration (config), sweep/admin completion.
- §4 the purge: config-off default, ONLINE-only target set (STORED =
  loud unimplemented), holder spared, telegraph, literal confirm,
  warning lead, and once-per-occurrence by the schema-v2
  `purge_executed_utc` CAS — the stamp commits before any player is
  touched.

**Implemented (14 new classes + plugin.yml + messages.yml + v2)**

- Schema v2: additive `ALTER TABLE rb_artifacts_events ADD
  purge_executed_utc`; store CAS_PURGE + purgeEvent; RebellionEvent
  carries the stamp; service `executePurge` (typed
  PURGE_EXECUTED / PURGE_ALREADY_EXECUTED); migrations() list; the
  append-only migration-set guard pinned by test.
- Config v2: per-dimension item materials, sweep interval (5..600 s),
  event duration (5..1440 min) + reminder (0..1440), purge section
  (enabled/target-set/spare-holder/min-delay/warning, every bound
  validated; STORED and TEAM are loud unimplemented refusals).
- `ArtifactItems` (PDC artifact_id + live instance, catalog-rendered
  name/lore), `ArtifactNames` (localizable short names).
- `OriginPresentation` + `OriginListener`: spawn/reconcile/protect/capture;
  `ArtifactFlowListener`: pickup, chest-take, removal->lost, hopper +
  craft cancels; `RebellionObserver`: probe + broadcast + join notice.
- `ArtifactsSweep`: return pipeline (begin/complete), presentation
  reconcile, duration completion, reminder cadence.
- `PurgeExecutor`: refusal pre-flight (readable, state-free) + armed
  execution (warning -> stamp CAS -> ONLINE kill loop, holder spared).
- `ArtifactsPlugin` (7th jar, POSTWORLD, config-owned sweep cadence),
  `/artifact` + `/artifactadmin` + tab completion, 3 placeholders,
  messages.yml (65 keys, strict-pinned 1:1).

**Verified**

- Suite: **389 tests green** (artifacts 32 -> 53: +8 config, +4
  messages, +5 purge-selection, +5 service-IT), stable across a forced
  rerun; the six sibling modules untouched.
- Boot smoke (upgrade-style, dev.5-era server -> dev.7 jars): 7 jars
  enable; artifacts v0 -> v2 in one boot; 3 rows seeded ORIGIN; restart
  no-op; admin-edit-wins fallback warned precisely for the one key the
  older extraction lacked; console renders list/info/events; module
  health operational; graceful reverse-order shutdown; sqlite after:
  guard clear, 0 events, 0 audit rows.
- Live reconcile proof (forceload): empty server -> pair correctly
  waits; chunk loaded -> exactly one pair spawns; kill one half ->
  converged to 1+1 (after fixing the orphan bug the proof caught);
  kill both -> fresh pair 1+1.

**Caught live and fixed during the smoke**

1. `admin.info` rendered as its own key: missing from messages.yml —
   and from the test's pinned key list (the 1:1 pin had the same gap).
   Both fixed; the ADR-0012 per-key fallback behaved exactly as
   designed in the meantime.
2. Partial-pair orphan: killing only the display made the next sweep
   spawn a fresh PAIR, orphaning a duplicate Interaction. The reconcile
   now clears remaining halves whenever either half is missing and
   spawns one fresh pair — convergence to exactly 1+1 proven live.

**Honest scope**

No player joined: capture, steal, destruction, third-capture trigger,
reminder cadence and the purge kill loop are server-side implemented
and boot-verified only. The decorative encounter structure is operator
map work; maximum-health scars are Phase 9 (profile surface);
dimension-instability *effects* (particles, ambient) need live
feel-tuning before they earn defaults.

**Next task**

The interactive pass (now covering both modules): one player on the
smoke server — dragons ride feel (ride.enabled flip, E1/E2 client
halves, E4b vanilla first-victory emission) AND the artifacts player
flows (capture from the presentation, drop/steal/chest/destruction,
the third-capture trigger broadcast, the purge with a real online
set). Serverless alternative: Phase 7 groundwork (rare souls, rituals,
tracking) or the items-module remaining surface.

## 2026-10-28 — Session 13: Phase 7 groundwork — the rebellion-rpg persistence layer (ADR-0016)

**Session context**

Continued straight off session 12's released dev.8. The documented
next-task choice was the interactive pass (blocked: needs a live player
on the smoke server — now covering both dragons ride feel and the
artifacts player flows) or serverless Phase 7 groundwork. The groundwork
proceeded, per the standing rule that interactive gates stay open and
honest while autonomous work continues.

**Protocol**

Pulled develop (bed8a1c, already up to date), master prompt md5
a84a5323 unchanged, baseline verified green (389 tests), dev.8 release
confirmed published with all 7 jars.

**Decisions first (ADR-0016, new)**

- §1 six tables under module key `rpg`: discoveries (PK-idempotent, no
  UPDATE path), claims (the two-phase transactional anchor), tracking
  sessions (UNIQUE claim; derived expiry — no swept state column),
  effects (counter-ritual masks, derived expiry), encounters (the
  ledger every spawn rule is answered from), events (append-only audit,
  deterministic server-composed PKs).
- §2 the claim protocol: reserve (DB first: definition + rate limit
  counting any-state attempts + cooldown counting COMMITTED attempts
  only + single-reservation race guard) → controller removes ingredients
  only under a live reservation → commit (CAS + effect rows + audit,
  all-or-none). Failure roll drawn ahead of the CAS, durable in the
  event row: a losing roll spends the claim (failure is a cost, which is
  what makes tracking contestable) but writes no session. Session-cap
  refusal auto-releases inside the commit transaction — the one mutated
  refusal. Stale sweep releases crash-orphaned reservations with the
  digest as refund evidence.
- §3 precision is an ordered enum stamped at cast (DIMENSION → REGION →
  COORDINATE); masks degrade exactly one tier and never stack; session +
  effect expiry are derived; offline players are legal targets; unknown
  names never reach persistence.
- §4 the encounter ledger: begin (definition + world + cap + cooldown
  from rows), attach (idempotent), complete (one-winner CAS; rewards
  ride the winner's transaction; unique claims = CAS + discovery PK),
  TTL sweep, admin despawn as the unwedge path.
- §5 config: rituals + encounters families, every bound validated
  (failure chance must stay below 0.95 — a guaranteed-fail ritual is
  config nonsense), the sample encounter ships **disabled** (spawn rules
  are operator-calibrated world data; the wards calibrated-scale
  precedent).

**Implemented (16 main classes + 2 test classes, schema v1)**

Domain: Precision, RitualKind, ClaimState, EncounterState, RpgOutcome,
RpgResult (generic over four payload families), Discovery, RitualClaim,
TrackingSession, PlayerEffect, Encounter, Ingredient, RitualDefinition,
EncounterDefinition, RpgConfig. DB: RpgSchema v1 (6 tables + 7 indices),
RpgStore (constants + CAS statements + typed ops; duplicate-PK inserts
classified into typed no-ops). Service: RpgService — future-based
transactions completing on the persistence executor (the
teams/wards/dragons/artifacts convention), typed refusals thrown inside
transactions (rollback) and unwrapped into results.

**Verified**

- Module suite: **41 tests green** (25 real-SQLite ITs + 16 config
  tests), full suite **430 green**, stable across a forced rerun; the
  seven sibling modules untouched.
- The IT covers every §5.3 persistence invariant end-to-end including
  the restart round-trip (fresh DatabaseService over the same file).

**Caught live and fixed during the slice (test-first evidence)**

1. The duplicate-PK discovery INSERT surfaces as a constraint violation
   (SQLite + MySQL alike), not a zero row-count — `RpgStore` now
   classifies unique violations into the typed `ALREADY_KNOWN` no-op
   instead of letting them surface as DB_ERROR.
2. Re-delivery of a commit originally resolved through a thrown refusal
   that lost the durable original outcome — the pre-flight was
   restructured so non-RESERVED claims resolve through the
   event-carrying `COMMIT_NOOP` (the test demanded the original
   SUCCESS/FAILED outcome in the detail).
3. A session-cap auto-release originally threw its refusal *after*
   writing the release — which would have rolled the release back with
   the transaction. It now returns normally as a mutated refusal so the
   release commits (the session-cap test pins the RELEASED state).

**Honest scope**

No plugin surface exists: no souls spawn, no inventory is touched, no
signal is rendered, no command or placeholder exists. The module ships
no plugin.yml (ADR-0009/0013 precedent) and is reported by the release
pipeline as not yet installable. Biome/time/player-count evaluation,
the NPC integration choice and preparation/cost surfaces are
controller-phase decisions that need a live world.

**Next task**

Either the interactive pass (one player on the smoke server — dragons
ride feel + E4b first-victory emission, artifacts capture/steal/trigger/
purge flows, and eventually the RPG controller's player flows), or the
RPG controller phase (soul entities + the inventory/ingredient
mechanics + signal rendering + commands + placeholders riding the
committed ADR-0016 contract), or Phase 8 groundwork (brewing
progression) — the persistence contract is committed and green either
way.

## 2026-10-28 — Session 14: live dragons hotfix — sweep NPE, friendly shield, horse-like riding opened for the feel pass

**Branch:** `develop` · **Base:** `142758c` (v0.1.0-dev.9)

**Context.** The live operator rode the first dev build on Paper 26.3
build 227 (v0.1.0-dev.8) and reported three defects: a repeating
`NullPointerException` in the dragons persistence sweep (every 30 s,
Task #3, `PetController.sweep:599`), vanilla boss contact damage from
the owner's own pet, and no usable mount/dismount path. The sweep NPE
was structural: `mounted.remove(lineage.entityUuid())` runs for every
non-HATCHED lineage, and egg-state rows carry a null `entityUuid` (the
`Lineage` record invariant) — `ConcurrentHashMap` rejects null keys, so
ONE egg-state row anywhere (the second minted lineage after the
operator's "The Next Generation" egg pickup) aborted the whole sweep
loop, silently disabling pet snapshots AND orphan reconciliation for
every living pet. This is exactly the crash-recovery gap §5.6 forbids.

**Fixes (rebellion-dragons):**

1. `PetController.sweep` split into `sweepOne` with a null-guarded
   mounted-cleanup (guarded against future states that retain a
   binding) and per-row try/catch — a single bad row now logs its slot
   and lineage id and the loop continues; fireball + breath-cloud
   pruning ride the sweep.
2. Friendly shield (`PetInteractListener.onPetHurtsFriendly`): damage
   BY a bound pet is cancelled when the victim is the owner, a teammate
   (teams module present) or the current rider; damage from the
   lingering impact cloud of one of OUR fireballs is cancelled for the
   owner's side — impact clouds that survive against hostile targets
   are tracked by UUID (`trackCloud`/`cloudOwner`, age-pruned by the
   same horizon as fireballs) because `EnderDragonFireballHitEvent`
   cancellation only removes the cloud of the *cancelled* hit. Natural
   boss dragons and foreign clouds keep vanilla semantics.
3. Horse-like riding opened for the ADR-0008 feel gate: `ride.enabled`
   now defaults to `true` (the gate could never be exercised while it
   shipped disabled); the steer cadence default tightened 8 -> 4 ticks
   (0.2 s look-loop latency); owner right-click without a pearl mounts
   (`PlayerInteractEntityEvent`, off-hand follow-up suppressed);
   re-clicking while aboard is an idempotent OK; sneak lands
   vanilla-style; `/dragon dismount` added as the explicit equivalent
   (eject + settle, the pet never flies on without its rider);
   `feed.wrong-item` replaced by `dragon.mount.not-yours` for
   non-owners; mount messages updated (shift-to-land hint).

Also fixed in passing (blocking the tree from building at all): the
rebellion-rpg WIP from session 13's in-flight controller slice had a
compile error — `Math.floorMod(double, int)` does not exist;
`SignalMath.compass8` now rounds into a `long` and uses
`floorMod(long, long)` (negative north-half octants still wrap into
[0, 8)).

**Tests actually run:** `./gradlew build` — full suite **431 green, 0
failed** (dragons: 27 across 5 classes incl. the updated
DragonsConfigTest pinning `ride.enabled=true` + 4-tick cadence and
DragonsMessagesTest pinning the new mount/dismount keys; rpg WIP tests
green). Boot smoke not re-run (no descriptor changes); the definitive
sweep verification is the operator's live log on the next dev build —
no WARN stack traces from Task #3 and riding actually exercised.

**Docs updated:** ADR-0008 (ride-feel gate section: live pass opened,
findings + consequences), MASTER_LEDGER (pet controller + command rows),
COMMANDS_AND_PERMISSIONS (mount row + dismount), config.yml/messages.yml
comments rewritten honestly.

**Honest scope.** Ride FEEL stays "live feel pass", not verified: the
operator rides the next build and reports whether the client-side feel
on the large body is acceptable without protocol work (ADR-0008's
community-research caveat). One open risk deliberately NOT guessed at:
whether a steered pet's body breaks non-End blocks on collision —
vanilla dragon block-crunching outside the End is unverified and needs
a live experiment before any claim either way.

**Next task.** Operator rides the dev.10 build (sweep silence + ride
feel report); then either continue the rebellion-rpg controller slice
(ADR-0017, WIP classes already compile) or the E4b first-victory
interactive pass.

## 2026-10-28 — Session 14 (part 2): the RPG controller slice — RebellionRPG becomes the eighth installable plugin (ADR-0017)

**Branch:** `develop` · **Base:** `0b37f42`

**Context.** Session 13 landed the ADR-0016 persistence layer;
session 12 had started the controller slice (config section, soul-entity
typing, pure helpers) as a WIP checkpoint which session 14 part 1
brought to a green compile (the `floorMod(double,int)` fix). This part
finishes the controller surface with **zero schema changes**.

**Shipped (rebellion-rpg):**

1. `RpgPlugin` + `plugin.yml` + `messages.yml` + `config.yml` — the
   eighth installable jar; `${version}` expanded via processResources
   (the session-10 lesson, applied proactively this time).
2. `SoulController` + `SoulListener` (ADR-0017 entity half): spawner on
   the pure `SpawnRules` verdicts (anchor-selected site in loaded
   surroundings, biome re-checked at the drawn column), ledger CAS as
   the only begin authority, souls persistent/faraway-kept/aware-off,
   damage-protected, one-winner right-click completion, non-UNLOAD
   vanish → row despawn (E7 lesson), TTL expiry + entity cleanup on the
   sweep. The shipped `wandering_soul` stays disabled — no soul ever
   spawns on uncalibrated world data (§5.3).
3. `RitualCommand` (ADR-0017 casting half): `/ritual
   list|cast|track|mask|status|signal|release` as the claim-protocol
   driver — verify → reserve → remove → commit, leftover-map race
   detector with release + exact refund, known-UUID-only target
   resolution, REGION signals via `SignalMath` (compass + bucket
   labels, effective tier only, "beyond sensing" for offline targets).
4. `RpgAdminCommand` (`/rpgadmin events <player>|revoke <sessionId>
   [reason]`) — read-only audit plus the idempotent audited revoke; the
   whole family mints nothing.
5. `RpgPlaceholders` + `RpgCacheListener`:
   `%rebellion_ritual_<key>_discovered%` over a join-loaded, evict-on-
   quit, grant-refreshed in-memory cache — placeholder resolution never
   touches the database (PlaceholderContributor contract, §10).
6. Reconciliation sweep (stale claims, TTL expiry, soul cleanup) every
   `sweep-interval` + final pass at disable, with per-row fault
   isolation (the session-12 sweep-NPE lesson).
7. `scripts/boot-smoke.sh` MODULE_DIRS updated to all eight installable
   jars (the list had silently rotted at five modules since the
   ADR-0011 harness landed).

**Tests actually run:** `./gradlew build` — full suite **437 green,
0 failed** (new: RpgMessagesTest contract pin, SpawnRulesTest,
SignalMathTest; all ADR-0016 RpgServiceIT invariants still green).
**Boot smoke on Paper 26.3 build 226** (clean first boot, eight jars in
order): RebellionRPG extracts both resources, applies schema v1, enables
with the quiet-spawner log line, `rebellion modules` reports `rpg
operational`, console `/ritual` renders the player-only catalog message,
3+ sweep cycles with zero exceptions (the only ERROR line is the
pre-documented flat-world generator warning, ADR-0008), graceful
reverse-order shutdown with the final sweep.

**Docs:** ADR-0017 (the controller decision), MASTER_LEDGER (controller
section + honest interactive-scope note), COMMANDS_AND_PERMISSIONS
(/ritual, /rpgadmin, the placeholder), this worklog.

**Honest scope.** Player-level flows are interactive-untested: reagent
feel, signal pulse timing and a live spawner on a calibrated map need a
real player on a real world (the smoke world is a flat void — it cannot
exercise the spawner at all, by design). The §5.3 NPC-integration
question stays open only for quest scripts richer than right-click-help.
No ride/feel claim is made for anything in this slice.

**Next task.** The operator rides dev.10 (dragons feel + sweep silence
report); the interactive pass for rpg (cast/track/mask with a real
inventory, signal pulse, then calibrate + enable `wandering_soul` on
the live map); or Phase 8 groundwork (brewing progression).

## 2026-10-28 — Session 15: Phase 7 menu surface — viewer-threaded placeholders + the ritual guide (ADR-0018)

**Goal.** Close Phase 7 is implementable scope: the roadmap §18 ends the
phase at "the related menu/lore flows", and the session-15 investigation
found the path blocked by two real defects rather than missing features.

**What the investigation found (before writing any feature code):**

1. `CorePlaceholders.route()` dropped the viewer: unknown parameters were
   routed to module contributors through the player-independent
   `resolve(params)` entry, so every viewer-scoped placeholder shipped by
   teams/dragons/wards/rpg resolved null through PlaceholderAPI while the
   module believed it had answered. Routing predates `resolveFor` (session
   3 vs session 4) and was never threaded. A live defect since day one,
   caught before any player beyond the operator ever saw a token.
2. The menu engine had no placeholder pass at all: `MenuHost` rendered
   MiniMessage but never substituted `%...%` tokens, and the
   `MenuSubject.displayName()` hook designed for it sat unused (dead code).

**Delivered:**

1. **Core routing fix** (`CorePlaceholders`): contributor routing is
   viewer-threaded (`resolveFor(player, params)` with the requesting
   player; player-independent requests route with a null viewer, which
   viewer-scoped contributors answer with null by contract). Core
   parameters now answer both contract entries — the new
   `CorePlaceholdersTest` exposed that `resolve("version")` returned null
   too, an honest gap in the old code, now fixed. The contributor source
   is injected (ServicesManager in production, a fixed list in tests), so
   routing is unit-pinnable serverless: viewer forwarding, null-viewer
   routing, self-exclusion, first-answer-wins.
2. **RPG placeholder wording** (`RpgPlaceholders`):
   `%rebellion_ritual_<key>_discovered%` now returns the
   operator-localized `ritual.list.state.known/unknown` templates (the
   exact `/ritual list` vocabulary, MiniMessage-colored) instead of a bare
   `true`/`false`; a missing template resolves null so the token stays raw
   rather than inventing wording. Parameter shape + wording pinned by the
   new `RpgPlaceholdersTest`; the viewer-scoped resolution stays a live
   path over the join-loaded cache.
3. **Menu placeholder rendering** (`MenuText` + `MenuSubject` +
   `MenuHost`): `menu_title`, `display_name` and every lore line resolve
   `%rebellion_<params>%` per viewer at open time, answered by the
   registered contributors with the opening player (core included — no
   PlaceholderAPI install needed on this path). The pure `MenuText` pins:
   unresolved tokens stay visible raw text (the operator sees their typo,
   not a blank line), foreign namespaces are never touched, values
   substitute verbatim (`$`/backslash safe, MiniMessage tags render).
   Dead `displayName()` hook replaced by `resolvePlaceholder(params)`;
   resolutions memoized per open. Click-action payloads deliberately not
   substituted (documented). `MenuTextTest` pins the substitution rules.
4. **The ritual guide menu** (`menus/ritual_guide.yml`, `open_command:
   ritualguide`, permission `rebellion.items.menu.ritual_guide`): explains
   the three shipped rituals (tracking / soul communion / masking —
   numbers mirror rebellion-rpg config.yml), the casting flow and soul
   encounters; the communion discovered-state line rides the placeholder
   per viewer. Menu ships in the menu-owning module (items), data comes
   through the shared contributor contract — the same data flow real
   DeluxeMenus deployments use, no module edge (ADR-0001). If RebellionRPG
   is absent the state line renders its raw token, documented in the file
   header. `MenuEngineTest` pins the communion token so a knowledge-key
   rename fails loudly; `ItemsConfigTest` pins the three-menu registry.

**Docs:** ADR-0018; DELUXEMENUS_COMPATIBILITY (placeholder section + the
stale open_command deviation note corrected — MenuCommandBridge shipped
per-menu commands in session 3); ARCHITECTURE (menu-placeholder bullet,
RPG section header refreshed from "persistence layer only" to the
three-slice reality); MASTER_LEDGER (session-15 rows, phase-table rows 2
and 7 — row 7 still claimed the controller phase was "remaining";
COMMANDS_AND_PERMISSIONS (per-menu open_command family + ritual_guide
permission).

**Honest scope.** Phase 7 implementable scope is now complete
(persistence ADR-0016 → controller ADR-0017 → menus ADR-0018). What
remains is interactive: opening `/ritualguide` as a player, casting with a
real inventory, signal pulse feel, the wandering_soul calibration, plus
the standing dragons ride-feel report and E4b first-victory emission. No
player-level menu flow is claimed verified.

**Next task.** Phase 8 groundwork (brewing progression: level/XP
persistence, recipe config surface — the persistence-first pattern a
fifth time), or the operator-side calibration of wandering_soul + the
interactive passes above.

**Session 15 addendum — the boot smoke caught a second live defect.** The
existing data folder (extracted by dev.9) booted with `menus: 2`:
`ritual_guide.yml` never extracted because extraction is keyed by the
data-folder config.yml registry, and `loadOrCreateConfig` called
`setDefaults` without `options().copyDefaults(true)` — probed empirically
against the pinned paper-api 26.3: `setDefaults` alone changes nothing for
section `getKeys` enumeration (and scalar `get*` reads never consult
bundled defaults at all). Fix: `copyDefaults(true)` at load — new bundled
`gui_menus` section KEYS merge into an existing install (upgrades gain
shipped menus automatically), admin-owned entries and scalar values keep
full sovereignty, the on-disk file is never modified (the module never
saves config.yml, so copyDefaults save-side copying cannot trigger). Three
new ItemsConfigTest upgrade pins. Honest latent gap noted but not touched:
a NEW SCALAR key in a future bundled config.yml will not apply to existing
installs (Bukkit scalar reads ignore defaults) — modules must keep shipping
method-arg fallbacks matching the bundled file, and the boot smoke covers
the clean-first-boot case per release.

## Session 16 — Phase 8 groundwork: the alchemy persistence layer (ADR-0019)

**Scope.** `rebellion-alchemy` joins the build as the Phase 8 groundwork
module (serverless, no plugin descriptor — the ADR-0009/0013/0016
precedent a fourth time). Everything in master prompt §5.7 that is *state*
is pinned in schema, service and tests; everything that needs a live
inventory, a brewing stand or a live player stays controller-phase.

**Research first (§4).** The potion config vocabulary was proven before
implementation, against the pinned paper-api 26.3 build 226 jar:
`PotionType` is a real enum whose static initializer only computes
NamespacedKey constants — jshell probe resolves all 46 constants with the
jar + adventure + guava on the classpath; `PotionEffectType` is an
abstract class whose static fields are registry-bound instances (javap),
so serverless use would crash. Decision: config, validation and rule rows
speak `PotionType` (enum-constant lookup, the Material/EntityType
discipline); the live PotionType→PotionEffectType mapping is
controller-phase.

**What shipped.** Five tables under module key `alchemy` (portable DDL,
UUID keys, enum-string states, every mutation a CAS or PK-guarded insert):

- `rb_alchemy_progress` — the atomic XP accumulate; **the level is derived
  by the pure `Leveling` walk on every read** (cost(level) =
  base+(level−1)·growth, clamped), never stored — the derived-expiry
  discipline applied to progression. Per-source idempotent awards: the
  `xp:<player>:<source>` event row is inserted *before* any mutation (the
  gate), then insert-or-accumulate, then the outcome column is finalized
  to LEVEL_UP/XP inside the same transaction. The concurrent-first-award
  PK violation is classified into the accumulate path.
- `rb_alchemy_unlocks` — the PK-idempotent two-path unlock ledger. The
  POWER/EFFICIENCY separation is structural: kind-mismatched config
  fields are refused at load ("efficiency must not quietly add combat
  strength" enforced by the record shape); prerequisites must reference
  existing keys and form no cycles (three-color DFS at config load).
- `rb_alchemy_claims` — the ADR-0016 claim protocol generalized with a
  purpose discriminator (UNLOCK_PURCHASE / AUTO_CONSUME): reserve (level
  gate + prerequisites + race guard / ownership + cooldown authority) →
  controller removes items under the reservation → commit all-or-none
  (CAS + unlock row / `last_fired_utc` stamp + audit). The admin grant
  path is audited and idempotent; a grant landing between reserve and
  commit still spends the claim (durable ALREADY_OWNED outcome).
- `rb_alchemy_rules` — player-defined auto-consumption under the operator
  policy family: trigger whitelist (with per-trigger HEALTH_BELOW
  bounds), PotionType whitelist, the anti-bot cooldown floor, priority
  range, per-player cap over live rows. Deterministic conflict order
  (priority, created, rule id); ownership-guarded idempotent toggle;
  stamped removal keeps forensics (ids consumed forever); `last_fired_utc`
  is the cooldown authority, stamped only by committed executions.
- `rb_alchemy_events` — append-only audit; deterministic composed keys;
  idempotent by construction.

**Evidence.** Full suite **514 tests / 0 failures** (counted from the
XMLs; forced fresh execution with `--rerun-tasks --no-build-cache`, 44 s),
up from 459 — 55 new (26 real-SQLite IT + 20 config + 9 pure leveling).
The four first-run failures were all test-side expectation bugs, plus one
contract improvement taken from them: `removeAutoRule` now resolves an
already-removed rule to the idempotent `RULE_REMOVE_NOOP` instead of
RULE_NOT_FOUND (removal is explicitly idempotent in the ADR; toggle and
reserve still treat dead rules as not found). Module jars untouched: no
plugin.yml, boot smoke unchanged (nothing new installs), release
pipeline builds + tests the module and reports "not yet installable".

**Docs.** ADR-0019; DATA_MODEL (five-table section); STATE_MACHINES
(claim lifecycle incl. the FIRED_UNSTAMPED mid-flight case, auto-rule
lifecycle, XP derivation); ARCHITECTURE (module section); TEST_PLAN
(suite inventory + the two caught defects); MASTER_LEDGER (feature rows +
Phase 8 roadmap row + known-limitations session note); README (new row +
the RebellionRPG row refreshed in passing — it had rotted at "not
installable yet" through the session-14/15 commits).

**Honest scope.** Bag items, the inventory safety listeners, brewing-stand
wiring, live trigger evaluation (health/fire/portal/fall), commands,
placeholders and menus are all controller-phase. No gameplay is claimed
verified. Next implementable slice: the alchemy controller, or operator
side-work (wandering_soul calibration, dragons ride-feel report).
