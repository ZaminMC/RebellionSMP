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
| RebellionTeams | `RebellionTeams-<version>.jar` | atomic teams/invites/ranks/diplomacy, team + proximity chat, friendly-fire attribution — tested |
| RebellionWards | `RebellionWards-<version>.jar` | no-claim watch wards, calibrated distance, outsider alerts, countermeasures — tested |
| RebellionDragons | `RebellionDragons-<version>.jar` | finite dragon lineages: 20-slot gateway-backed registry (ADR-0009), boss-victory gateway observation (ADR-0014), pet hatch/call/mount/steer/fire/heal controls on the ADR-0008-proven surface, protected lineage eggs, audited admin recovery — boot-verified on Paper 26.3; ride feel + vanilla first-victory emission are interactive gates (riding ships disabled) |
| RebellionArtifacts | `RebellionArtifacts-<version>.jar` | three dimension artifacts (ADR-0013/0015): dimension-keyed registry seeded exactly once, instance-stamped possession CAS, protected self-healing origin presentation + capture, item flows (steal/destruction→recoverable loss), return pipeline, once-per-occurrence Rebellion event, config-gated world-wide purge (default off), /artifact + /artifactadmin, placeholders — boot-verified on Paper 26.3; player flows (capture/steal/purge) are interactive gates |

Further modules (rituals, alchemy, prison, cosmetics) are planned —
see `docs/MASTER_LEDGER.md` for the phase roadmap and current status.

## Dev builds

Every push to the `develop` branch is built, tested, and published
automatically as a numbered **pre-release** on
[GitHub Releases](https://github.com/ZaminMC/RebellionSMP/releases)
(`vX.Y.Z-dev.N`). Dev builds are prereleases by policy — they are never
marked `latest`; the `latest` slot stays reserved for reviewed stable
releases from `main`. Each release carries the installable module JARs and
notes listing the included commits. See `docs/decisions/ADR-0010-release-pipeline.md`
for the pipeline design.

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

Pre-built JARs come from the dev releases above; building from source is only
needed for development.

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
