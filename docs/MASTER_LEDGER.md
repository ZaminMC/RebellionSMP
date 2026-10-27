# Master ledger — feature inventory and status

Status vocabulary: `planned` → `researched` → `implemented` → `tested` →
`verified-on-Paper-26.3` → `released`. A feature is `implemented` only when
its owning module has tests passing in `./gradlew test`.

## Foundation

| Feature | Owning module | Status | Notes |
|---|---|---|---|
| Gradle multi-project build, wrapper, version catalog | root | tested | Java 25 toolchain, pinned deps, FAIL_ON_PROJECT_REPOS |
| Shared API contracts (database, module registry, results) | rebellion-api | tested | one runtime class identity via plugin `depend` |
| Core lifecycle + bootstrap + fail-closed disable | rebellion-core | tested | BootstrapException path, unit-covered |
| Config loader framework (atomic reload, env expansion, issue aggregation) | rebellion-core | tested | ConfigLoaderTest, CoreConfigLoaderTest, DatabaseConfigLoaderTest |
| Messages loader (bundled defaults, strict MiniMessage validation) | rebellion-core | tested | CoreMessagesTest; closed-tag contract |
| Diagnostics (incident IDs, bounded ring, rotated sanitized reports) | rebellion-core | tested | DiagnosticsServiceTest |
| Persistence service (bounded async executor, backpressure) | rebellion-core | tested | DatabaseServiceTest incl. rejection + shutdown flush |
| HikariCP pool (shaded/relocated) + SQLite + MySQL config | rebellion-core | tested | SQLite integration-tested; MySQL path config-tested only |
| Schema migrations (per-module registry, checksums, downgrade refusal) | rebellion-core | tested | MigrationRunnerTest |
| Module health registry (ModuleRegistry impl) | rebellion-core | tested | ModuleManagerTest |
| `/rebellion status\|modules\|diagnose\|reload` + tab completion | rebellion-core | implemented | command executor logic; server-level verification planned |
| PlaceholderAPI expansion (`rebellion`, 4 core placeholders) | rebellion-core | implemented | loads only with PAPI present; runtime check pending |
| Rarity registry + material resolver (COMMON fallback, data-driven styles) | rebellion-items | tested | RarityLoaderTest; serverless via injected validator |
| Versioned PDC item identity (fail-closed parsing, canonical UUIDs) | rebellion-items | tested | ItemIdentityTest; Bukkit adapter is thin |
| Lore annotation (marker de-dup, metadata preservation, config-gated flows) | rebellion-items | tested | LorePlannerTest; stack-level behavior on Paper is a manual gate |
| Talisman tier axis (NORMAL/ARTIFACT/RELIC, separate from rarity) | rebellion-items | tested | contract test |
| DeluxeMenus-shaped menu engine (registry, validation, click safety) | rebellion-items | tested | MenuEngineTest; live-inventory interaction is a manual gate |
| Bundled menus: item codex + talisman guide | rebellion-items | tested | parsed from disk in MenuEngineTest |
| /rebellionitems inspect\|annotate\|reload\|status, /rebellionmenu <id> | rebellion-items | implemented | command logic; server-level verification planned |
| Annotation flows: block drops, crafting output, smithing output, container open | rebellion-items | implemented | config-gated (default off); event-bounded, no scans |
| Placeholder contributor architecture (module-routed `rebellion` expansion) | rebellion-api + rebellion-core | implemented | api: PlaceholderContributor; core: CorePlaceholders routes via ServicesManager |
| `%rebellion_rarity_<material>%` placeholder | rebellion-items | tested | pure registry read; junk input → null (PAPI leaves text untouched) |
| Custom item registry (items.yml: named items, talisman families, unique instances) | rebellion-items | tested | CustomItemLoaderTest; factory is the only sanctioned creation path |
| /rebellionitems give <defined-id> [player] | rebellion-items | implemented | restricted mint: defined items only, admin permission, logged |

## Roadmap (from master prompt §18; nothing below is implemented)

| Phase | Scope | Status |
|---|---|---|
| 0 | Inspect, research, ledgers, ADRs | researched (this commit set) |
| 1 | Foundation + module loader contracts | implemented + tested (unit level) |
| 2 | Item identity, rarity registry, lore service, talisman tiers, DeluxeMenus-shaped menus | annotation flows complete and opt-in; PAPI item placeholder shipped; custom item registry with talisman family examples shipped; remaining: per-menu open_command registration, admin audit/recovery views over inventories, talisman activation mechanics |
| 3 | Teams, chat, friendly-fire attribution | planned |
| 4 | No-claim wards, calibrated distance, intel | planned |
| 5 | Dragon lineage proof spike, then full dragon module (20 lineages) | planned |
| 6 | Three dimension artifacts + Rebellion event | planned |
| 7 | Rare souls, rituals, tracking | planned |
| 8 | Brewing progression, potion bags, automation | planned |
| 9 | Prison, death scores, economy interface, rehab | planned |
| 10 | Cosmetics-only rewards, optional map/voice integrations | planned |
| 11 | Hardening + release candidate | planned |

## Known limitations (truthful, current)

- All annotation flows exist behind opt-in switches; the custom item
  factory's live ItemStack behavior (PDC stamping, glint, instance ids) is a
  manual Paper-26.3 verification gate (documented in TEST_PLAN).
- Talismans are definable and mintable but have no activation mechanics
  yet — the family examples exist to prove the identity/tier contract, not
  gameplay (Phase 7 scope).
- MySQL/MariaDB path has no integration test (no server available in CI);
  SQLite is the exercised backend. Not advertised as tested.
- The plugin has not yet been booted on a live Paper 26.3 server in this
  environment (no server runtime here); startup verification is planned as a
  manual/CI integration step. Unit suites cover everything that can be covered
  without a server.
- DeluxeMenus compatibility subset is researched but the menu engine is not
  built; `docs/DELUXEMENUS_COMPATIBILITY.md` documents the researched schema
  and the planned supported subset.
