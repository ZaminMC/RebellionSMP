# RebellionSMP

A modular, no-claims geopolitical/RPG survival SMP plugin family for
[Paper](https://papermc.io) targeting **Minecraft Java 26.3**. RebellionSMP is a
long-running Earth-map SMP in which scarce power, exploration, intelligence,
team relationships, finite dragon lineages, rituals, imprisonment, and risk
interact as one coherent game system.

## Status

Early development on the `develop` branch. Currently implemented:

| Module | JAR | Status |
|---|---|---|
| RebellionAPI | `RebellionAPI-<version>.jar` | shared contracts (database, module registry, results) — tested |
| RebellionCore | `RebellionCore-<version>.jar` | lifecycle, config/message loaders, SQLite/MySQL persistence, migrations, diagnostics, `/rebellion` admin command, PlaceholderAPI expansion — tested |
| RebellionItems | `RebellionItems-<version>.jar` | rarity registry + resolver, PDC item identity, lore annotation, talisman tiers, DeluxeMenus-shaped menu engine with two bundled menus — tested |

Feature modules (teams, wards, dragons, artifacts, rituals, alchemy, prison,
cosmetics) are planned and not yet built — see `docs/MASTER_LEDGER.md` for the
phase roadmap and current status.

## Requirements

- Paper 26.3 (Java 25 toolchain; class files major 69)
- Java 21+ to run Gradle; the build auto-provisions the Java 25 toolchain
- Optional at runtime: PlaceholderAPI 2.12+ (Core boots fine without it)

## Build

```bash
./gradlew build          # all modules, tests included
./gradlew test           # test suites only
```

## Install order

1. `RebellionAPI-<version>.jar` → `plugins/` (STARTUP)
2. `RebellionCore-<version>.jar` → `plugins/` (STARTUP, depends on RebellionAPI)
3. `RebellionItems-<version>.jar` (POSTWORLD, depends on API + Core)
4. Further feature module JARs as they become available (POSTWORLD)

RebellionCore creates `plugins/RebellionCore/` with `config.yml`,
`database.yml`, and `messages.yml` on first boot. SQLite works with zero setup;
MySQL/MariaDB is configured in `database.yml` (credentials support
`${ENV_NAME}` expansion). See `docs/OPERATIONS.md`.

## Commands and permissions

| Command | Permission | Default |
|---|---|---|
| `/rebellion status` | `rebellion.admin.status` | op |
| `/rebellion modules` | `rebellion.admin.modules` | op |
| `/rebellion diagnose` | `rebellion.admin.diagnose` | op |
| `/rebellion reload` | `rebellion.admin.reload` | op |

Placeholders (when PlaceholderAPI is installed): `%rebellion_version%`,
`%rebellion_modules_healthy%`, `%rebellion_database_backend%`,
`%rebellion_modules_degraded%`.

## Documentation

- `docs/ARCHITECTURE.md` — module graph, lifecycle, thread boundaries
- `docs/MASTER_LEDGER.md` — feature status inventory
- `docs/CONFIGURATION.md`, `docs/DATA_MODEL.md`, `docs/TEST_PLAN.md`,
  `docs/OPERATIONS.md`, `docs/RECOVERY.md`, `docs/SECURITY.md`,
  `docs/COMPATIBILITY_MATRIX.md`, `docs/LICENSES.md`
- ADRs in `docs/decisions/`

## License

GPL-3.0 — see `LICENSE`. Third-party licenses in `docs/LICENSES.md`.
