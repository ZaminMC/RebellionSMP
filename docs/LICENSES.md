# Licenses

## This project

RebellionSMP is licensed under **GPL-3.0** — see `LICENSE` in the repository
root.

## Third-party components

| Component | Version | License | Use | Bundled? |
|---|---|---|---|---|
| Paper API | 26.3 | GPL-3.0 (API) | compile-only plugin API | no (server provides) |
| HikariCP | 7.1.0 | Apache-2.0 | connection pool | **yes** — shaded + relocated (`com.zaxxer.hikari` → `net.zaminmc.rebellion.libs.hikari`) inside RebellionCore; Apache-2.0 notice retained here |
| PlaceholderAPI | 2.12.3 | GPL-3.0 | compile-only expansion API | no (server provides if installed) |
| adventure / MiniMessage | 5.2.0 (transitive) | MIT | text components provided by Paper runtime | no |
| sqlite-jdbc | 3.49.1.0 | Apache-2.0 (bundled native libs under their own permissive licenses) | test-time driver parity with the Paper server | no — tests only |
| JUnit 5 (jupiter 6.x line) | 6.1.3 | EPL-2.0 | test framework | no (test classpath only) |
| Gradle shadow plugin | 9.6.1 | Apache-2.0 | build-time shading | no (build tool) |

## Not incorporated (research-only references)

The following were inspected for documented behavior and are **not** copied
into this repository: DeluxeMenus (MIT — schema inspiration only), BlueMap,
Dynmap, Simple Voice Chat, the historical Rideable_enderdragon repository
(unlicensed practice plugin — never a code source), EarthMC world archives
(asset provenance managed by operations, see OPERATIONS.md).
