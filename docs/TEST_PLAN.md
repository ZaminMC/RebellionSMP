# Test plan

## Environment

- Build/test: any machine with JDK 21+ (Gradle wrapper auto-provisions the
  Java 25 toolchain); no Bukkit server required for the current suites.
- CI: GitHub Actions `ubuntu-latest` + Temurin 25 (see
  `.github/workflows/ci.yml`), `./gradlew build` with dependency caching.
  Pushes to `develop` are owned by `.github/workflows/release.yml`, which
  runs the same build/tests and publishes the numbered dev prerelease
  (ADR-0010); ci.yml keeps pull requests and the read-only `main` push
  validation.
- A real Paper 26.3 server is available headlessly via the boot-smoke
  harness (`scripts/boot-smoke.sh`, ADR-0011): boot-level verification runs
  in this environment (install order, config generation, migrations,
  console surface, restart, shutdown). Player-level interaction (menus,
  invites, ward walking, combat) still requires an interactive session and
  stays a manual gate before any full "verified-on-Paper-26.3" status.

## Suites (implemented)

| Suite | Covers | Key invariants |
|---|---|---|
| ConfigLoaderTest | bundled copying, no-clobber of admin edits, issue aggregation, env expansion, path traversal rejection | admin edits survive plugin updates; secrets expand from env |
| CoreConfigLoaderTest / DatabaseConfigLoaderTest | schema-version, bounds (pool sizes, queue capacity, timeouts), backend enum, forced SQLite pool = 1 | invalid values disable Core with precise paths |
| CoreMessagesTest | bundled defaults parse + render, strict closed-tag validation, unclosed-tag rejection, placeholder exemption, missing-key default fill, file creation | failed reload never replaces the active snapshot |
| MessageCatalogTest (API) | shared loader contracts: extraction, admin-edit precedence, per-key fallback for invalid edits, user-only keys, traversal-guarded resource names, `\<` escape semantics, merge≡load equivalence (ADR-0012) | boot never renders broken templates silently; extraction cannot escape the data folder |
| ItemsMessagesTest / TeamsMessagesTest / WardsMessagesTest | each shipped messages.yml strict-parses; every rendered key and every (action, outcome) pair exists; literal angle brackets render as text; first-boot extraction | a key drift between code and yml fails the build, not the game |
| TeamsMessagesTest (chat formats) | `chat.team-format` / `chat.proximity-format` render `<sender>`/`<message>` component placeholders | channel formatting is admin-editable and injection-safe |
| DatabaseServiceTest | bootstrap, CRUD, transactions + rollback, UUID/boolean binding, unsupported param rejection, bounded queue rejection with zero side effects, shutdown flush | rejection happens before any SQL executes |
| MigrationRunnerTest | idempotent re-run, checksum mismatch refusal, downgrade refusal, version-gap rejection, failed migration rollback, bookkeeping rows | migrations are append-only and crash-resumable |
| DiagnosticsServiceTest | incident ID format, bounded ring, report rotation, single-line sanitization, no-secrets contract | report structure cannot be broken by hostile summaries |
| ModuleManagerTest | registration, atomic state updates, ghost-update rejection, duplicate registration, unhealthy/ratio reporting, snapshot immutability | registry state is thread-safe and defensive |
| RarityLoaderTest | COMMON fallback (incl. future materials), explicit overrides, unknown material/rarity issues, bundled defaults | unmapped materials can never crash or mis-tier the registry |
| ItemIdentityTest | schema bounds, unknown-rarity/malformed-id/forged-UUID fail-closed parsing, canonical UUID round-trip | identity is the authorization basis for unique assets |
| LorePlannerTest | append/top/bottom, marker de-dup, human lore preservation, removal pass, null-element safety | re-annotation never duplicates lines or deletes human text |
| MenuEngineTest | size/slot/amount bounds, duplicate slots, unknown materials/actions/requirements, command slash rules, console gating, requirement-order, registry traversal rejection, atomic reload, bundled menus parse from disk | invalid menus are diagnostics; clicks with failed requirements have zero side effects |
| ItemsConfigTest | mode/position enums, registry shape, bundled default validity, precise issues, per-flow switches | wide modes are explicit opt-ins |
| ItemsPlaceholdersTest | rarity placeholder resolution, COMMON fallback, junk input → null, foreign params pass through | placeholders never echo user-controlled junk |
| CustomItemLoaderTest | bundled family validity, defaults, malformed ids, unknown material/rarity/tier issues, immutability, tier axis ≠ rarity axis | definitions are the only sanctioned mint source |

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
   - Dragons serverless portion: **automated** — `LineageServiceIT` (real
     SQLite, 21 tests) covers gateway issuance races, cap races at the
     configured and hard ceiling, hatch races (concurrent clicks, clones,
     stale instances, direct-hatch-from-recovery refusal), simultaneous
     owner+dragon death (exactly one egg), duplicate/stale death events,
     recovery claim gating (no claimant leak), admin route audit, terminal
     retirement with slot/gateway staying consumed, the full restart
     round-trip, **pearl-heal cooldown idempotency (event retry refused
     before the pearl is consumed), entity-pinned snapshots (stale
     bindings are typed no-ops), and snapshot/pearl-stamp restart
     round-trips**. Controller arithmetic is pinned by
     `FireballBudgetTest` (30/60 s cap, rolling eviction, min-interval,
     per-dragon isolation, forward-pointing retry hints) and
     `GatewayDiffTest` (ADR-0014 decision table: emerging-only mints,
     registered exclusion, ambiguity refusal, scan-miss warning).
     `DragonsMessagesTest` strict-parses the shipped messages.yml and pins
     every rendered key; `DragonApi263SpikeTest.controllerSliceSurface`
     probes the newly-used members. Remaining live-server portions:
     physical egg delivery/destruction routing, the boss-victory
     observation on a real kill, and the ADR-0008 interactive halves.
   - Artifacts serverless portion: **automated** — `ArtifactServiceIT`
     (real SQLite, 22 tests) covers the exactly-three seed and its
     idempotency, config-owned anchor sync without touching gameplay
     state, capture/transfer/find races (cloned + forged stamps lose),
     idempotent loss recording, the return-delay gate + active-event
     check, arrival idempotency, Rebellion qualification (two-of-three and
     split holders refused), once-per-occurrence trigger idempotency
     (duplicates resolve to the same event id), the disabled-toggle
     refusal, all-or-none spending (refused set leaves every row
     untouched; retried spend is a typed "already spent" conflict), guard
     release + second occurrence (no permanent winner), admin routing of
     stuck states only, audit-trail discipline (no-ops write nothing), and
     the full restart round-trip (stamps, SPENT rows and the ACTIVE
     occurrence survive). `ArtifactsConfigTest` (10) pins the shipped
     config.yml, the defaults factory, anchor/delay bounds and the loud
     TEAM-scope refusal. Remaining live-server portions: the encounter
     structure, physical item flows, catastrophe effects and placeholders
     — controller-phase work.
5. **DB outage drills**: unique-asset creation under saturation = fail closed.

## Verified on Paper 26.3 (headless boot smoke, 2026-10-28)

Run with `scripts/boot-smoke.sh` against Paper 26.3 build 226 (sha256-verified
via the fill API), released v0.1.0-dev.2 jars for the first pass, fixed local
jars for the re-run. Log excerpts preserved in docs/WORKLOG session 7.

- [x] Clean server, all five installable JARs (API, Core, Items, Teams,
      Wards) load and enable in the documented install order; dragons
      correctly ships no descriptor and is not installable by design.
- [x] First boot generates every module's data files: Core
      `config.yml`/`database.yml`/`messages.yml` + SQLite (WAL), Items
      `config.yml`/`items.yml`/`rarities.yml` + extracted bundled
      `menus/{item_codex,talisman_guide}.yml`, Teams/Wards `config.yml`.
- [x] Schema migrations apply on first boot (teams v1, wards v1) and are a
      no-op on the second boot ("Schema '…' at migration version 1").
- [x] Console surface: `rebellion status` (no stray tags, queue counts,
      migration counts), `rebellion modules` (all operational),
      `rebellion diagnose` (report written with incident ID under
      `plugins/RebellionCore/diagnostics/`).
- [x] PlaceholderAPI absent: Core boots, placeholders stay inactive.
- [x] Restart: configs preserved (no recreation), modules re-enable,
      persisted ward load runs, migrations do not re-apply.
- [x] Graceful shutdown: modules disable in reverse order
      (Items → Wards → Teams → Core → API), Hikari pool closes, clean exit.

Session 8 additions (same harness, fresh server dir, fixed local jars at
v0.1.0-dev.3 + ADR-0012 wiring):

- [x] First boot extracts `messages.yml` for Core, Items, Teams and Wards
      ("Created default messages.yml." per module); no template warnings.
- [x] Console feedback renders through the module catalogs:
      `rebellionitems status` (header/config/count lines via placeholders),
      `wardadmin list` ("Watch wards on this server (0):"), `team` from
      console (prefixed player-only notice).
- [x] Admin edit → `/rebellionitems reload` applies it live (custom
      `status.header` rendered after reload without restart).
- [x] Deliberately unclosed template: precise WARN naming the key
      ("status.header: invalid MiniMessage … using bundled default"),
      bundled default renders, module stays operational.
- [x] Restart: admin file byte-identical (md5 before/after), no
      re-extraction, same precise WARN for the still-broken edit, clean
      13.7s boot, `rebellion status`/`diagnose` healthy, graceful stop.

The first smoke run caught five real defects (all fixed and re-verified in
the same session): `YamlConfiguration.load(String)` treats its argument as
a path (Teams/Wards enabled nothing but a FileNotFoundException whose
message was the file content); Items never extracted its bundled menus and
refused to enable; onDisable rewrote FAILED modules to
"disabled — shutdown", hiding failures; a failed Items bootstrap vanished
from the module registry entirely; and a paired `</state>` closing tag leaked
into the status line. Details in ADR-0011.

## Manual Paper 26.3 checklist (interactive; not yet run)

- [ ] Break `config.yml` deliberately: Core disables with a precise message.
- [ ] `/rebellion reload` on the live server (bad candidate keeps previous
      config).
- [ ] PlaceholderAPI present: expansion registers; unknown
      placeholders render untouched.
- [ ] RebellionItems on Paper 26.3: `/rebellionitems give talisman_haste_artifact`
      → stack carries item_id/rarity/tier/instance_id PDC, glint, styled
      display name and rarity lore line; a second mint of a unique item gets a
      DIFFERENT instance id; annotate a held diamond sword twice →
      exactly one rarity lore line, display name/enchants preserved;
      `/rebellionmenu item_codex` renders; shift-click/number-key/drag cannot
      remove menu items; `[console]` actions refused without the flag +
      permission; pickup/join flows annotate per configured mode only.
- [ ] Serverless gap (documented): `Material`/`ItemMeta`/live inventory
      interaction cannot run in plain JUnit (Material class-init requires
      server registries — verified 2026-10-28); those paths stay thin adapters
      verified by this manual gate. MockBukkit does not support 26.3 (its
      latest targets 1.21). Same applies to `org.bukkit.attribute.Attribute`
      (probe-caught, ADR-0008).
- [x] Release pipeline on GitHub (first automated run, ADR-0010 — verified
      2026-10-28, run #1 at `ef4077c`): push to `develop` cut
      `v0.1.0-dev.1` — release exists with exactly the installable JARs
      (API, Core, Items, Teams, Wards; dragons correctly absent),
      `prerelease: true`, authored by Abasing, the `releases/latest`
      endpoint returns 404 (dev build is not latest), the version-bump
      commit on `develop` carries `[skip ci]`, and the run triggered by
      that bump commit completed without cutting another release
      (verified via git: HEAD stayed at the bump commit; no `dev.2` tag).
- [ ] Teams on Paper 26.3: invite→accept happy path, kick/leave/disband,
      friendly-fire cancellation matrix (melee/projectile/TNT/pet/cloud),
      team + proximity chat channels, `/team history` rendering.
- [ ] Wards on Paper 26.3: create at a placed lodestone anchor; walk an
      outsider in → one enter alert with the correct unit label (blocks by
      default; km only after calibrating `world-units-per-kilometer`);
      leave notice; re-enter inside cooldown stays silent; dwell + masking
      (held amethyst shard) suppression; decoy labeling; `/wardadmin mask`;
      anchor break/explosion removes the ward with the breaker message;
      `%rebellion_nearest_ward_distance%` with PAPI installed.
- [x] Dragon live experiments (ADR-0008 gate — server-side halves
      **executed and recorded 2026-10-28**, evidence:
      `docs/evidence/adr0008-experiments.log`, full analysis in
      ADR-0008): mount mechanics (server anchor (0, 3.0, 0);
      `setAware(false)`+HOVER platform = 0.00 drift over 5 s);
      steering comparison (velocity: 0.00 displacement — dead end;
      pathfinder: returns false, 0.00 displacement — dead end;
      `setPodium`+`FLY_TO_PORTAL`: works, ~16 blocks/s closed-loop
      re-targeting); death timing (`kill()` → `EntityDeathEvent` same
      tick, droppedExp=500, no drops, killer=null → removal
      `EntityRemoveEvent(cause=DEATH)` **199 ticks later**; sourceless
      `damage()` ignored — dragons filter it); heal arithmetic
      (170 +25 → 195, +500 → clamp 200, no phase interference);
      fireball launch (`setDirection` alone = motionless; with
      `setAcceleration`+`setPower`+`setVelocity` it flies and
      `EnderDragonFireballHitEvent` fires with the `AreaEffectCloud`,
      zero block damage); chunk unload/reload (same UUID, health/phase
      preserved, `EntityRemoveEvent(UNLOAD)` ↔ `EntityAddToWorldEvent`).
- [ ] Dragon live experiments — the two interactive halves that need a
      human: (1) client-side rider render position/feel for a mounted
      player (gates the rideable feature flag); (2) the vanilla
      first-victory + repeat-victory observation (E4/E4b) — the End
      fight only ticks with a player present (bytecode-proven, see
      ADR-0008), so one player must enter the End, then the committed
      `DragonExperiments` harness (`dxexp victory`, `dxexp victory2`)
      records `DragonEggFormEvent`, gateway count delta per victory,
      `hasBeenPreviouslyKilled()` transitions, 12000-vs-500 XP and the
      gateway ring coordinates.
- [ ] Dragons lineage flow on Paper 26.3 (controller layer now exists —
      interactive pass): observed gateway → `issueFromGateway` mints once
      and `ISSUED_ALREADY` on duplicate (ADR-0014 observation window);
      egg PDC stamp round-trip; undeliverable delivery parks in recovery;
      hatch consumes the item only on service success; owner/dragon death
      hands the egg to the killer (idempotent CAS); egg survives
      lava/void/explosion/despawn/hopper/dispense (protection listeners
      shipped this session); `/dragon call` resolves an unloaded pet
      through the persisted location; pearl feeding refuses event retries
      inside the cooldown; fireball budget caps bursts; `/dragon mount`
      after flipping `ride.enabled` (record the client ride feel — the
      ADR-0008 interactive gate).

Session 10 additions (same harness, upgrade-style boot on the existing
smoke server database, fixed local jars at v0.1.0-dev.5 + the new
RebellionDragons):

- [x] Six installable jars (API, Core, Items, Teams, Wards, **Dragons**)
      load and enable; Dragons registers the `dragons` module and reports
      `0/20 lineages ever minted, victory observation on, riding disabled
      (ADR-0008 ride-feel gate)` in its enable line.
- [x] Live database upgrade: the dev.2-era sqlite (no dragons tables)
      applies dragons v1+v2 in one boot ("Schema 'dragons' at migration
      version 2"); second boot is a no-op (version already 2).
- [x] First boot extracts Dragons `config.yml` + `messages.yml`
      byte-identical to the bundled resources (md5-verified against
      `src/main/resources`).
- [x] Console surface: `dragon` from console renders the player-only
      notice; `dragonadmin list` renders "No dragon lineage has ever been
      minted."; `dragonadmin retire 1` without `confirm` renders the usage
      (no lineage with that slot exists); `rebellion modules` lists all
      five modules operational; `rebellion diagnose` writes a clean
      report.
- [x] Restart: version renders (`v0.1.0-dev.5`), no "Created default"
      re-extraction, migrations do not re-apply, graceful reverse-order
      shutdown with the final persistence sweep while Core's database is
      still up.
- [x] Zero ERROR/SEVERE/Exception lines in the whole session's server log.

The session-10 smoke caught one real defect (fixed and re-verified on the
restarted server in the same session): the Dragons jar shipped with an
unexpanded `${version}` literal in `plugin.yml` (processResources expansion
existed in every other module but Dragons had never shipped a descriptor),
printing `RebellionDragons ${version} enabled`.

## Session 12 checklist — artifacts controller (ADR-0015, boot-verified 2026-10-28)

Serverless suite: **389 tests green** (api 8, core 44, items 91, teams 50,
wards 86, dragons 57, artifacts 53 — up from 32 by 8 config, 4 messages,
5 purge-selection, 5 service-IT additions), stable across a forced rerun.

- [x] Config contract: per-dimension item materials (garbage + AIR
      refused, legal override accepted), sweep/duration/reminder bounds,
      purge defaults (off, ONLINE, spare-holder), STORED target set is a
      loud unimplemented refusal, min-delay/warning bounds.
- [x] `ArtifactServiceIT` purge additions: once-per-occurrence claim
      (`PURGE_EXECUTED` → retried `PURGE_ALREADY_EXECUTED` no-op), the
      stamp survives restart and blocks re-execution, completion preserves
      the stamp, unknown events refuse NOT_FOUND, and the append-only
      migration-set guard is pinned (a shrunk set after v1+v2 is a loud
      refusal; re-apply is a no-op).
- [x] `PurgeSelectionTest` (pure): holder spared per config, explicit
      rule may include the holder, holder-offline, empty server,
      deterministic order.
- [x] `ArtifactsMessagesTest`: strict-parse + 1:1 key pin (65 keys) —
      the boot smoke caught the console rendering `admin.info` before the
      test did (the pinned list had the same gap; both fixed same day).
- [x] Boot smoke (Paper 26.3 build 226, upgrade-style over the dev.5-era
      server): 7 jars enable; artifacts schema v0 → v2 in one boot on the
      existing sqlite; 3 rows seeded (ORIGIN, no holder); restart no-op
      (no re-migration, no re-extraction, admin-edit-wins fallback warned
      precisely for the one key the older extraction lacked); console
      surface renders (`artifactadmin list/info/events`, module health
      `operational`); graceful reverse-order shutdown; sqlite afterwards:
      guard clear, 0 events, 0 audit rows (the seed writes nothing).
- [x] Presentation reconcile proven live with `forceload`: on an empty
      server the anchor chunk is not loaded → the pair correctly waits;
      with the chunk loaded the sweep spawns exactly one ItemDisplay +
      Interaction; killing one half (and both halves) converges back to
      exactly 1+1 on the next sweep (a real convergence defect was caught
      live and fixed: a partial pair used to orphan a duplicate
      Interaction).
- [x] `/kill` on a non-living display bypasses damage events (measured):
      the protected-origin guarantee holds through the self-healing
      reconcile — the row, not the entity, is the authority.
- Remaining interactive gates (documented, honest): a player capturing
  from the presentation (right-click → CAS → stamped item), the
  steal/destruction/chest flows through real inventories, the trigger
  broadcast on a real third capture, the reminder/duration cadence under
  a live occurrence, and the purge kill loop with real online players.
