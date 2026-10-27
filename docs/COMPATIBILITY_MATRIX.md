# Compatibility matrix

"Verified" means an experiment/test in this repository or a direct inspection
of the artifact; "inferred" means documentation claims only.

| Component | Version | Status | Evidence |
|---|---|---|---|
| Paper server | 26.3 (build 226 stable line) | targeted, not yet runtime-verified | no live server in this environment; API pinned and compiled against `io.papermc.paper:paper-api:26.3-...` |
| Java toolchain | 25 (class major 69) | verified | paper-api class files inspected; build compiles with Java 25 toolchain |
| Gradle wrapper | 9.8.2 | verified | wrapper runs the build |
| HikariCP | 7.1.0 | verified (tests) | shaded + relocated; pool exercised by DatabaseServiceTest against SQLite |
| sqlite-jdbc (tests) | 3.49.1.0 | verified | all db tests run against real SQLite files |
| MySQL / MariaDB | config path only | inferred | driver from server classpath per Paper docs; **no** integration test available in CI |
| PlaceholderAPI | 2.12.3 | compile-verified; runtime inferred | compileOnly pin; expansion registers only when present |
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
