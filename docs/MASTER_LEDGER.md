# Master ledger — feature inventory and status

Status vocabulary: `planned` → `researched` → `implemented` → `tested` →
`verified-on-Paper-26.3` → `released`. A feature is `implemented` only when
its owning module has tests passing in `./gradlew test`.

## Foundation

| Feature | Owning module | Status | Notes |
|---|---|---|---|
| Gradle multi-project build, wrapper, version catalog | root | tested | Java 25 toolchain, pinned deps, FAIL_ON_PROJECT_REPOS |
| Release pipeline: numbered dev prereleases from develop (ADR-0010) | .github/workflows | tested | every develop push → `vX.Y.Z-dev.N` prerelease (never `latest`), JARs discovered by plugin.yml presence, writes authored as Abasing via ABASING_TOKEN; ci.yml keeps PRs + main |
| Paper 26.3 boot-smoke harness (ADR-0011) | scripts/boot-smoke.sh | tested | headless boot gate: fetch (checksum-verified) → jars → start/send/stop; caught 5 live defects on first run (fixed same session) |
| Shared API contracts (database, module registry, results) | rebellion-api | verified-on-Paper-26.3 (boot) | loads + enables first; one runtime class identity via plugin `depend` |
| Core lifecycle + bootstrap + fail-closed disable | rebellion-core | verified-on-Paper-26.3 (boot) | first-boot config generation, SQLite WAL pool, clean shutdown; BootstrapException path unit-covered; broken-config live path still manual |
| Config loader framework (atomic reload, env expansion, issue aggregation) | rebellion-core | tested | ConfigLoaderTest, CoreConfigLoaderTest, DatabaseConfigLoaderTest |
| Messages loader (bundled defaults, strict MiniMessage validation) | rebellion-core + rebellion-api | verified-on-Paper-26.3 (boot, reload) | CoreMessagesTest + MessageCatalogTest (ADR-0012); closed-tag contract; shared MessageCatalog since ADR-0012 |
| Diagnostics (incident IDs, bounded ring, rotated sanitized reports) | rebellion-core | tested | DiagnosticsServiceTest |
| Persistence service (bounded async executor, backpressure) | rebellion-core | tested | DatabaseServiceTest incl. rejection + shutdown flush |
| HikariCP pool (shaded/relocated) + SQLite + MySQL config | rebellion-core | tested | SQLite integration-tested; MySQL path config-tested only |
| Schema migrations (per-module registry, checksums, downgrade refusal) | rebellion-core | tested | MigrationRunnerTest |
| Module health registry (ModuleRegistry impl) | rebellion-core | tested | ModuleManagerTest |
| `/rebellion status\|modules\|diagnose\|reload` + tab completion | rebellion-core | verified-on-Paper-26.3 (console) | status/modules/diagnose verified in the boot smoke; reload + player-side still planned |
| PlaceholderAPI expansion (`rebellion`, 4 core placeholders) | rebellion-core | implemented | absent-PAPI path verified in the boot smoke (stays inactive); with-PAPI runtime check pending |
| Rarity registry + material resolver (COMMON fallback, data-driven styles) | rebellion-items | tested | RarityLoaderTest; serverless via injected validator |
| Versioned PDC item identity (fail-closed parsing, canonical UUIDs) | rebellion-items | tested | ItemIdentityTest; Bukkit adapter is thin |
| Lore annotation (marker de-dup, metadata preservation, config-gated flows) | rebellion-items | tested | LorePlannerTest; stack-level behavior on Paper is a manual gate |
| Talisman tier axis (NORMAL/ARTIFACT/RELIC, separate from rarity) | rebellion-items | tested | contract test |
| DeluxeMenus-shaped menu engine (registry, validation, click safety) | rebellion-items | tested | MenuEngineTest; live-inventory interaction is a manual gate |
| Bundled menus: item codex + talisman guide | rebellion-items | tested | parsed from disk in MenuEngineTest; first-boot extraction into the data folder live-verified (boot smoke) |
| /rebellionitems inspect\|annotate\|reload\|status, /rebellionmenu <id> | rebellion-items | implemented | boot verified (menus extracted, /talismanguide + /itemcodex registered); player-level verification planned |
| Annotation flows: block drops, crafting output, smithing output, container open | rebellion-items | implemented | config-gated (default off); event-bounded, no scans |
| Placeholder contributor architecture (module-routed `rebellion` expansion) | rebellion-api + rebellion-core | implemented | api: PlaceholderContributor; core: CorePlaceholders routes via ServicesManager |
| `%rebellion_rarity_<material>%` placeholder | rebellion-items | tested | pure registry read; junk input → null (PAPI leaves text untouched) |
| Custom item registry (items.yml: named items, talisman families, unique instances) | rebellion-items | tested | CustomItemLoaderTest; factory is the only sanctioned creation path |
| /rebellionitems give <defined-id> [player] | rebellion-items | implemented | restricted mint: defined items only, admin permission, logged |
| Module messages wiring: bundled+extracted messages.yml, catalog rendering (ADR-0012) | rebellion-api + items/teams/wards | verified-on-Paper-26.3 (boot, reload, restart) | extraction on first boot, admin-edit-wins, per-key fallback with precise warnings; per-module coverage tests; admin-edit reload + broken-template fallback live-verified |
| Chat channel formats + rate-limit notice via catalog | rebellion-teams | tested | `<sender>`/`<message>` component placeholders; TeamsMessagesTest |
| Ward alert/anchor/listing texts via catalog | rebellion-wards | tested | AlertRouterTest asserts the shipped templates; name injection structurally impossible (unparsed placeholders) |

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
| /ward create\|remove\|toggle\|list\|info, /wardadmin list\|remove\|mask | rebellion-wards | implemented | module enable + migration live-verified (boot smoke); player-level command verification planned |
| %rebellion_nearest_ward_distance%, %rebellion_ward_count% | rebellion-wards | tested | own-network intel only; decoys/inactive excluded |
| Wards map marker provider (default off) | rebellion-wards | tested | publishes only when map-markers.publish-wards: true, active real wards only |
| Dragon 26.3 controller spike (API surface probe + report) | rebellion-dragons | tested | DragonApi263SpikeTest against pinned paper-api 26.3.226; ADR-0008; production controls gated on live experiments |
| Dragon lineage registry: 20 gateway-backed slots (rb_dragons_lineages) | rebellion-dragons | tested | ADR-0009; UNIQUE gateway/slot/entity backstops; rows mint only via issueFromGateway; cap clamped at load AND runtime |
| Lineage idempotency: issuance/hatch/death CAS transitions | rebellion-dragons | tested | LineageServiceIT (18, real SQLite): gateway races resolve ISSUED_ALREADY; hatch races one winner (HATCH_CONFLICT losers); simultaneous owner+dragon death = exactly one egg |
| Lineage recovery mailbox (park/claim/admin route) | rebellion-dragons | tested | claimant-gated claim (no claimant leak); admin route audited; recovery eggs exit only via claim/route (fresh instance each) |
| Lineage retirement (audited, terminal) | rebellion-dragons | tested | RETIRED refuses every other transition; slot + gateway stay consumed (retire never frees a slot); rb_dragons_audit per mutation |
| Dragons config contract (dragons.max-lineages) | rebellion-dragons | tested | DragonsConfigTest: 1..20 loud validation; ceiling clamps even bypassing loaders; shipped config.yml documents it |
| Dragons plugin wiring + schema v2 (controller slice, session 10) | rebellion-dragons | verified-on-Paper-26.3 | DragonsPlugin + plugin.yml (6th installable jar); migration v2 adds loc_* + last_persist_utc columns (live upgrade proven: existing sqlite went dragons v0 -> v2); boot smoke: enable order, extraction byte-identity, restart idempotency, graceful reverse-order shutdown; caught + fixed live: ${version} not expanded |
| Pet controller on the ADR-0008-proven surface | rebellion-dragons | implemented (server-side) | PetController: HOVER platform (E1), closed-loop podium steering + phase guard (E2c), E6-recipe fireballs with per-dragon FireballBudget (30/60s default, tested), pearl feeding behind the cooldown CAS (E5), call/recall through persisted location, water weakness 1.0..4.0-bounded, mount/dismiss; ride ships config-DISABLED (ride-feel interactive gate) |
| Boss-victory gateway observation (ADR-0014) | rebellion-dragons | implemented (fail-closed) | GatewayObserver: baseline annulus scan (async chunks + snapshots, off-thread sweep) -> count poll -> discovery scan -> GatewayDiff (pure, tested: emerging-only, registered-excluded, ambiguity refused, miss warned) -> issueFromGateway -> egg delivery; no killer credited = no mint; pets excluded even when the vanilla fight adopts them |
| Physical egg-item protection | rebellion-dragons | implemented (live-verify pending) | EggProtectionListener cancels damage/combust/despawn/hopper/dispense/portal for stacks carrying a LIVE egg-instance stamp; stale clones are inert by design (protection is stamp-based, not material-based) |
| Pet lifecycle + reconciliation | rebellion-dragons | implemented (server-side) | direct kill -> killer receives egg (EntityDeathEvent); owner PvP death -> killer inherits egg + death presentation kill() after the CAS commits; EntityRemoveEvent(cause != UNLOAD) + 30s sweep reconcile vanished entities through the idempotent death CAS into the recovery mailbox |
| /dragon + /dragonadmin command family | rebellion-dragons | implemented (console-verified) | hatch (PDC-stamped, holder-only, rollback on refused CAS), status, call (cooldown+range), mount, dismiss, attack (crosshair, budget, friendly refusal), claim; admin list/info/route/audit/retire with literal `confirm` keyword; DragonsPlaceholders: dragon_owned/id/health/count/cap (mirror reads only) |
| Dimension-artifact registry: exactly three dimension-keyed rows (rb_artifacts_registry) | rebellion-artifacts | tested | ADR-0013; rows are created ONLY by the idempotent load-time seed (INSERT-if-absent per key, config-owned origin anchors re-synced every load); no gameplay mint path anywhere; a table not holding exactly three rows fails the load loudly |
| Instance-stamped possession CAS (capture/transfer/found/voluntary return) | rebellion-artifacts | tested | ArtifactServiceIT (22, real SQLite): every possession change presents the live instance and supersedes it; cloned/forged/stale copies resolve INSTANCE_STALE and never move a row — never two valid copies |
| Recoverable loss + return pipeline (LOST/SPENT -> RETURNING -> ORIGIN) | rebellion-artifacts | tested | idempotent markLost (LOST_ALREADY), beginReturn gated on the configured delay AND no active event, completeReturn restores a fresh discoverable origin instance; both sweep steps individually idempotent CAS so a crashed sweep cannot double-apply; SPENT rides the same pipeline home (no permanent winner) |
| Rebellion event: once-per-occurrence, copy-proof (rb_artifacts_events + singleton guard) | rebellion-artifacts | tested | trigger requires all three HELD by one player (copied artifacts cannot qualify structurally); guard CAS makes duplicates/races resolve EVENT_ALREADY_ACTIVE carrying the same occurrence; spendArtifacts all-or-none in one transaction (SPENT_CONFLICT typed); completeRebellion releases the guard — a new full possession mints a NEW durable event id |
| Admin route + audit trail | rebellion-artifacts | tested | adminRoute recovers stuck states only (LOST/RETURNING -> HELD; ORIGIN refused — the realm must be conquered, not granted); rb_artifacts_audit appends one row per affected artifact per real mutation; no-ops write nothing |
| Artifacts config contract (origin anchors, return delay, event toggle + scope) | rebellion-artifacts | tested | ArtifactsConfigTest (10): shipped config.yml pinned to the defaults factory; anchor/delay bounds; TEAM trigger scope is a loud unimplemented error (§5.5 separate-option rule) |

## Roadmap (from master prompt §18)

| Phase | Scope | Status |
|---|---|---|
| 0 | Inspect, research, ledgers, ADRs | researched (this commit set) |
| 1 | Foundation + module loader contracts | implemented + tested (unit level); boot verified on Paper 26.3 build 226 (ADR-0011) |
| 2 | Item identity, rarity registry, lore service, talisman tiers, DeluxeMenus-shaped menus | annotation flows complete and opt-in; PAPI item placeholder shipped; custom item registry with talisman family examples shipped; module enable + menu extraction live-verified; remaining: per-menu open_command registration, admin audit/recovery views over inventories, talisman activation mechanics, player-level menu flows |
| 3 | Teams, chat, friendly-fire attribution | implemented + tested (unit level); module enable + migration live-verified; player flows (invites, chat) need interactive verification |
| 4 | No-claim wards, calibrated distance, intel | implemented + tested (unit level); module enable + migration + persisted-ward load live-verified; km display disabled until calibrated; measured pipeline costs in docs/PERFORMANCE.md; ward walking/alert flows need interactive verification |
| 5 | Dragon lineage proof spike, then full dragon module (20 lineages) | spike delivered and executed live (ADR-0008: measured mount/steer/death/heal/fireball/persistence surface; bytecode-proven player-gated End fight); lineage registry + CAS idempotency delivered (ADR-0009); controller slice delivered (session 10): plugin wiring, pet controller, gateway observation (ADR-0014), egg protection, commands, placeholders, schema v2 — boot-verified on Paper 26.3 build 226; remaining interactive gates: client ride feel + vanilla first-victory emission (E4b) |
| 6 | Three dimension artifacts + Rebellion event | persistence layer delivered (session 11, ADR-0013): dimension-keyed registry, instance-stamped possession CAS, recoverable loss + return pipeline, once-per-occurrence Rebellion event — 32 tests, real SQLite; controller phase (encounter structure, physical item flows, catastrophe effects, commands, placeholders) is next |
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
- Paper 26.3 boot-level verification now runs headlessly
  (`scripts/boot-smoke.sh`, ADR-0011): install order, config generation,
  migrations, console surface, restart and shutdown are live-verified on
  build 226. The ward pipeline (movement sampling, anchor raytrace, alert
  rendering), teams invites/chat and menu clicking still need the documented
  interactive pass.
- Feature-module `messages.yml` wiring is DONE (ADR-0012, session 8): all
  player feedback renders through extracted catalogs, live-verified through
  the boot smoke (admin-edit reload, broken-template fallback, restart
  byte-identity). Remaining honest gap: player-visible rendering (menus,
  invites, alerts to actual players) is still part of the interactive pass.

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

Session additions (2026-10-28, module message catalogs — ADR-0012):

- The previously reserved "ADR-0012 dimension-artifact state machine"
  roadmap slot moved to ADR-0013; ADR-0012 is the message catalog decision.
- Two latent rendering defects fixed by the migration: wards `list.empty`
  shipped HTML entities that rendered literally in game; `admin.list-line`
  carried an unclosed `<gray>` masked by lenient MiniMessage.
- MiniMessage escape knowledge recorded: `\<` escapes a literal opening
  bracket, `\>` does not exist (backslash stays literal).

Session additions (2026-10-28, ADR-0008 live experiments executed):

- The ADR-0008 live-verification gate is satisfied at the server-side
  level on Paper 26.3 build 226: mount mechanics, steering primitives,
  death timing, heal arithmetic, fireball launch and chunk
  unload/reload persistence are all measured and recorded
  (`docs/evidence/adr0008-experiments.log`, analysis in ADR-0008).
  Committed harness: `rebellion-dragons/src/experiments` (the
  `experiments` source set, `dragonExperimentJar` Gradle task, jar
  deliberately invisible to release staging).
- Locked-in controller decisions from the measurements: mount platform
  `setAware(false)`+HOVER; steering via closed-loop `setPodium`+
  `FLY_TO_PORTAL` re-targeting (velocity writes and the Paper
  pathfinder are proven dead ends on dragons); fire attack via
  plugin-launched `DragonFireball` (acceleration vector required;
  hit/flame/shoot events are the budget hooks; zero block damage);
  death flow keyed on `EntityDeathEvent` with the ~200-tick corpse
  window as the death presentation.
- Corrections recorded against the serverless probe: 26.3 exposes
  `DragonBattle.getGatewayCount()`/`spawnNewGateway()`/`generateEndPortal(boolean)`/
  `setPreviouslyKilled(boolean)` (no-gateway-synthesis stays a POLICY,
  no longer a capability limit); `EnderDragon.getPodium()/setPodium()`
  exists; `EntityRemoveEvent` is `org.bukkit.event.entity.*` in 26.3.
- Honest blocker, bytecode-proven: the End fight only ticks while a
  player is in the End, so the vanilla first-victory/repeat-victory
  observation (egg emission, gateway ring, 12000-vs-500 XP) and the
  client-side ride feel remain interactive gates with the harness
  ready (`dxexp victory`, `dxexp victory2`). Sourceless `damage()` is
  filtered by dragons (three runs) — tests and controller must use
  sourced damage, `kill()` or `setHealth`.
- Boot-smoke environment note: the flat preset on 26.3 produces a
  void world (`No key layers in MapLike[{}]`) — harmless for module
  boot tests, unusable for any harness that needs ground.

Session additions (2026-10-28, dragons controller slice — session 10):

- The dragons module is now a real installable plugin (6th jar): plugin
  wiring, commands, listeners, tasks, placeholders and module health all
  follow the ADR-0011-proven boot pattern. Migration v2 (loc_* +
  last_persist_utc) upgrades existing databases in place — live-proven on
  the smoke server's dev.2-era sqlite going dragons v0 -> v2 in one boot.
- Every controller recipe is an ADR-0008 measurement, not a guess: HOVER
  platform (E1, 0.00 drift), closed-loop podium steering (E2c, ~16 b/s,
  phase guard via the cancellable change-phase event), the four-vector
  fireball recipe (E6), heal-clamp arithmetic (E5), kill() for the death
  presentation (E3: bare sourceless damage() is filtered by vanilla).
- Pearl healing and persistence snapshots are NEW CAS statements in the
  same ADR-0009 style: the pearl cooldown stamp makes event retries
  typed refusals before the pearl is consumed; snapshot writes pin the
  bound entity so superseded bindings are benign no-ops.
- Boss-victory minting decided in ADR-0014 (scan-diff): the module never
  synthesizes gateways; it discovers the real END_GATEWAY that emerged in
  the victory's observation window and mints only when the candidate set
  exactly matches vanilla's announced count. No credited killer = no
  mint (fail-closed WARN), pets never mint even when the vanilla fight
  adopts them.
- Caught live by the boot smoke and fixed: the dragons jar shipped with an
  unexpanded `${version}` in plugin.yml (every other module had the
  processResources expansion; dragons had never shipped a descriptor
  before). Fixed + re-verified on the restarted server.
- Honest scope of the slice: console-surface + boot-level verification
  only (no player joined the smoke server, no boss died, no egg was
  minted live). Player flows — hatch/call/ride/feed/attack, the E4b
  first-victory emission with real gateway coordinates, and the client
  ride feel — remain the documented interactive pass; riding ships
  disabled-by-config until the ride-feel gate passes.
- Speculative-spawn risk recorded: hatch spawns the entity before the
  CAS binds it; a refused CAS removes the spawn, but a crash between
  spawn and commit can leave one unbound dragon entity (harmless: it is
  a vanilla dragon; the sweep never touches unbound entities).

Session additions (2026-10-28, artifacts persistence layer — session 11):

- The long-pending PAT rotation completed first: the new token verified
  (GET /user = 200 Abasing, full scopes), `ABASING_TOKEN` re-encrypted
  and PUT (updated_at 04:40:34Z), git remote re-pointed — the credential
  pair is consistent again and drives both pushes and releases.
- Session 11 started as a recovery: the previous session's Phase 6
  groundwork (ADR-0013 + rebellion-artifacts + five doc updates) was
  found uncommitted in the tree after its context window overflowed
  mid-flight. The code was audited against the master prompt §5.5
  contract and ADR-0013, the full suite was rebuilt and force-rerun
  (368 tests green: 336 baseline + 32 new), and the session completed
  the unfinished docs pass (README module row, this ledger's rows +
  roadmap, WORKLOG session 11) before committing.
- Exactly-three is structural, the ADR-0009 pattern: rows are created
  only by the idempotent load-time seed; there is no gameplay INSERT
  anywhere; a corrupted table (count != 3) fails the load loudly rather
  than being papered over.
- Copy-proof possession: the physical item carries the row's live
  instance UUID and every possession change supersedes it — a cloned or
  forged copy resolves INSTANCE_STALE and can never move a row, which is
  also what makes the Rebellion trigger immune to copied artifacts
  without any event-layer dedup code.
- The Rebellion event is once-per-occurrence by a singleton guard CAS:
  duplicate inventory events, retried listeners and racing observers all
  resolve to EVENT_ALREADY_ACTIVE carrying the same durable event id;
  after completeRebellion a NEW full possession mints a NEW occurrence —
  no permanent winner, no unbounded event spam.
- Honest scope: this slice is serverless persistence only — the module
  ships no plugin.yml (not installable), and the encounter/structure,
  physical item flows, catastrophe effects (dimension instability,
  health scars — Phase 9 profile surface), commands and placeholders
  (`%rebellion_artifact_*`) are controller-phase work gated on the
  interactive verification pass, exactly like ADR-0009 preceded the
  dragons controller.
