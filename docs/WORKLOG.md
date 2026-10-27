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
