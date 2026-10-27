# Test plan

## Environment

- Build/test: any machine with JDK 21+ (Gradle wrapper auto-provisions the
  Java 25 toolchain); no Bukkit server required for the current suites.
- CI: GitHub Actions `ubuntu-latest` + Temurin 25 (see
  `.github/workflows/ci.yml`), `./gradlew build` with dependency caching.
- A real Paper 26.3 server is not available in this environment; server-level
  verification is a documented manual gate before any "verified-on-Paper-26.3"
  status.

## Suites (implemented)

| Suite | Covers | Key invariants |
|---|---|---|
| ConfigLoaderTest | bundled copying, no-clobber of admin edits, issue aggregation, env expansion, path traversal rejection | admin edits survive plugin updates; secrets expand from env |
| CoreConfigLoaderTest / DatabaseConfigLoaderTest | schema-version, bounds (pool sizes, queue capacity, timeouts), backend enum, forced SQLite pool = 1 | invalid values disable Core with precise paths |
| CoreMessagesTest | bundled defaults parse + render, strict closed-tag validation, unclosed-tag rejection, placeholder exemption, missing-key default fill, file creation | failed reload never replaces the active snapshot |
| DatabaseServiceTest | bootstrap, CRUD, transactions + rollback, UUID/boolean binding, unsupported param rejection, bounded queue rejection with zero side effects, shutdown flush | rejection happens before any SQL executes |
| MigrationRunnerTest | idempotent re-run, checksum mismatch refusal, downgrade refusal, version-gap rejection, failed migration rollback, bookkeeping rows | migrations are append-only and crash-resumable |
| DiagnosticsServiceTest | incident ID format, bounded ring, report rotation, single-line sanitization, no-secrets contract | report structure cannot be broken by hostile summaries |
| ModuleManagerTest | registration, atomic state updates, ghost-update rejection, duplicate registration, unhealthy/ratio reporting, snapshot immutability | registry state is thread-safe and defensive |

Run: `./gradlew test` (results in `*/build/reports/tests/test/`).

Flakiness control: concurrency-sensitive tests (`boundedQueueRejects…`) use
explicit start-latch synchronization rather than sleep heuristics; the suite
was run 3× consecutively with `--rerun-tasks` to confirm stability.

## Future layers (gates for later phases)

1. **JAR/metadata**: each JAR loads on a clean Paper 26.3 server in order;
   dependency metadata matches reality.
2. **Menu engine** (Phase 2): click/drag/shift-click/number-key bypass tests;
   requirement evaluation precedes actions; unknown actions produce config
   diagnostics.
3. **Item identity** (Phase 2): stack preservation through annotation, lore
   de-duplication, PDC uniqueness, forged-ID fail-closed, rarity fallback for
   every current Material.
4. **Dragon cap / artifact uniqueness** (Phases 5–6): uniqueness races,
   idempotent death processing, restart mid-event reconciliation — the exact
   adversarial matrix from master prompt §14.
5. **DB outage drills**: unique-asset creation under saturation = fail closed.

## Manual Paper 26.3 checklist (pre-release gate, not yet run)

- [ ] Clean server, drop RebellionAPI + RebellionCore, boot: both enable in order.
- [ ] Break `config.yml` deliberately: Core disables with a precise message.
- [ ] `/rebellion status`, `modules`, `diagnose` (report file written),
      `reload` (bad candidate keeps previous config).
- [ ] PlaceholderAPI present/absent: expansion registers/doesn't; unknown
      placeholders render untouched.
- [ ] Restart persistence: schema bootstrap is a no-op the second boot.
