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

## Wards (rebellion-wards, Phase 4)

| Feature | Owning module | Status | Notes |
|---|---|---|---|
| TeamAffiliationService (cache-only team lookups for sibling modules) | rebellion-api | tested | adapter in teams over TeamCache; absence-tolerant consumer side |
| MapMarker/MapMarkerProvider (privacy-gated web-map surface) | rebellion-api | tested | providers return nothing without an explicit per-category admin gate |
| No-claim watch ward model (anchor-bound, no protection surface) | rebellion-wards | tested | Ward record cannot express protection; anchor break/explosion removes ward |
| Calibrated distance service (km gate ships disabled) | rebellion-wards | tested | DistanceServiceTest; uncalibrated worlds can never print "km" |
| Per-world uniform-grid spatial index (one-cell queries) | rebellion-wards | tested | no-miss property vs linear scan at 500 wards/3000 probes; measured in PERFORMANCE.md |
| Detection state machine (enter/leave, per-pair cooldowns, dwell, masking, decoys) | rebellion-wards | tested | WardDetectorTest (21) under fixed clocks; tests caught the epoch-0 cooldown defect |
| Movement sampling (O(1) event marks + cadenced async sweep) | rebellion-wards | tested | MovementFeedTest; ~50 ns/event measured |
| rb_wards schema + transactional caps + UNIQUE race backstops | rebellion-wards | tested | WardServiceIT (14, real SQLite): concurrent name/anchor/cap races |
| Alert routing (team/owner/both channels, owner fallback) | rebellion-wards | tested | AlertRouterTest; MiniMessage injection neutralized (parsed component has no click events) |
| /ward create\|remove\|toggle\|list\|info, /wardadmin list\|remove\|mask | rebellion-wards | implemented | command logic; server-level verification planned |
| %rebellion_nearest_ward_distance%, %rebellion_ward_count% | rebellion-wards | tested | own-network intel only; decoys/inactive excluded |
| Wards map marker provider (default off) | rebellion-wards | tested | publishes only when map-markers.publish-wards: true, active real wards only |
| Dragon 26.3 controller spike (API surface probe + report) | rebellion-dragons | tested | DragonApi263SpikeTest against pinned paper-api 26.3.226; ADR-0008; production controls gated on live experiments |
| Dragon lineage registry: 20 gateway-backed slots (rb_dragons_lineages) | rebellion-dragons | tested | ADR-0009; UNIQUE gateway/slot/entity backstops; rows mint only via issueFromGateway; cap clamped at load AND runtime |
| Lineage idempotency: issuance/hatch/death CAS transitions | rebellion-dragons | tested | LineageServiceIT (18, real SQLite): gateway races resolve ISSUED_ALREADY; hatch races one winner (HATCH_CONFLICT losers); simultaneous owner+dragon death = exactly one egg |
| Lineage recovery mailbox (park/claim/admin route) | rebellion-dragons | tested | claimant-gated claim (no claimant leak); admin route audited; recovery eggs exit only via claim/route (fresh instance each) |
| Lineage retirement (audited, terminal) | rebellion-dragons | tested | RETIRED refuses every other transition; slot + gateway stay consumed (retire never frees a slot); rb_dragons_audit per mutation |
| Dragons config contract (dragons.max-lineages) | rebellion-dragons | tested | DragonsConfigTest: 1..20 loud validation; ceiling clamps even bypassing loaders; shipped config.yml documents it |

## Roadmap (from master prompt §18)

| Phase | Scope | Status |
|---|---|---|
| 0 | Inspect, research, ledgers, ADRs | researched (this commit set) |
| 1 | Foundation + module loader contracts | implemented + tested (unit level) |
| 2 | Item identity, rarity registry, lore service, talisman tiers, DeluxeMenus-shaped menus | annotation flows complete and opt-in; PAPI item placeholder shipped; custom item registry with talisman family examples shipped; remaining: per-menu open_command registration, admin audit/recovery views over inventories, talisman activation mechanics |
| 3 | Teams, chat, friendly-fire attribution | implemented + tested (unit level); live-server verification planned |
| 4 | No-claim wards, calibrated distance, intel | implemented + tested (unit level); km display disabled until calibrated; measured pipeline costs in docs/PERFORMANCE.md; live-server verification planned |
| 5 | Dragon lineage proof spike, then full dragon module (20 lineages) | spike delivered (ADR-0008: verified API surface, two removals found, live-experiment gate defined); production controls intentionally blocked until live proofs; lineage registry persistence layer delivered and tested (ADR-0009: registry, CAS idempotency for issue/hatch/death, recovery mailbox, audited retirement); remaining: ADR-0008 live experiments, then controller + egg-item listeners + commands |
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
- Ward detection state (enter/leave, cooldowns) is deliberately ephemeral:
  after a restart, insiders are re-detected as new entries on first
  movement. Cooldowns do not survive restarts or world changes (documented
  in ADR-0007).
- Ward kilometer display is disabled for every world until an operator
  calibrates `distance.world-units-per-kilometer` per world
  (docs/CONFIGURATION.md). Until then distances report in blocks/meters only.
- No live Paper 26.3 runtime in this environment: the ward pipeline
  (movement sampling, anchor raytrace, alert rendering) and teams/wards
  integration need the documented manual smoke pass.

Session additions (2026-10-28, dragons persistence layer):

- The dragons module has NO plugin wiring yet (no plugin.yml, boot,
  commands or listeners): installing it as a server plugin is not a
  supported state until the controller phase lands after the ADR-0008
  live proofs. The persistence layer is exercised through the tested
  service surface (`LineageServiceIT`).
- Physical egg-item protection (lava/void/explosion/hopper/despawn
  routing) is controller-phase work; the registry models undeliverable
  copies via the recovery mailbox so the persistence side is complete,
  but no Bukkit listener enforces it yet.
