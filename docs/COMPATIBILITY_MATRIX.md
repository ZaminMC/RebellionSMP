# Compatibility matrix

"Verified" means an experiment/test in this repository or a direct inspection
of the artifact; "inferred" means documentation claims only.

| Component | Version | Status | Evidence |
|---|---|---|---|
| Paper server | 26.3, build 226 (sha256 `1373c651…`) | boot-verified | headless boot smoke (ADR-0011): all 5 installable jars enable in order, migrations apply + restart no-op, console surface works, graceful shutdown; player-level flows not yet verified |
| Java toolchain / runtime | 25 (class major 69) | verified | paper-api class files inspected; server booted on Temurin 25.0.5 in the boot smoke |
| Gradle wrapper | 9.8.2 | verified | wrapper runs the build |
| HikariCP | 7.1.0 | verified (tests) | shaded + relocated; pool exercised by DatabaseServiceTest against SQLite |
| sqlite-jdbc (tests) | 3.49.1.0 | verified | all db tests run against real SQLite files |
| MySQL / MariaDB | config path only | inferred | driver from server classpath per Paper docs; **no** integration test available in CI |
| PlaceholderAPI | 2.12.3 | absence runtime-verified; presence inferred | compileOnly pin; boot smoke: Core boots cleanly without it and placeholders stay inactive; with-PAPI path not yet run |
| adventure-text-minimessage | 5.2.0 (transitive) | verified (behavior probes) | strict/lenient parse behavior probed directly; see REFERENCE_LEDGER |
| DeluxeMenus schema | current `main` | researched | MIT source + wiki inspected; compatibility subset defined, engine not built |
| BlueMap | v5.24 | researched | release notes mention 26.3; adapter planned, not built |
| Dynmap | latest listing | **not compatible per listing** | listing reports 1.21.x max; not claimed for 26.3 |
| Simple Voice Chat | current | researched | requires client-side mod; adapter deferred |
| EarthMC world archives | Terra Aurora (1.21.8 era) | research-only | ~50 GB ops-managed asset; not validated for 26.3; never committed |

Runtime plugin-family matrix (today):

| JAR | load | depends | softdepends |
|---|---|---|---|
| RebellionAPI | STARTUP | — | — |
| RebellionCore | STARTUP | RebellionAPI | PlaceholderAPI |
| RebellionItems | POSTWORLD | RebellionAPI, RebellionCore | — |
