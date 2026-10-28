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
     SQLite, 18 tests) covers gateway issuance races, cap races at the
     configured and hard ceiling, hatch races (concurrent clicks, clones,
     stale instances, direct-hatch-from-recovery refusal), simultaneous
     owner+dragon death (exactly one egg), duplicate/stale death events,
     recovery claim gating (no claimant leak), admin route audit, terminal
     retirement with slot/gateway staying consumed, and the full restart
     round-trip. Remaining live-server portions: physical egg delivery/
     destruction routing and the ADR-0008 experiment set.
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
- [ ] Dragon live experiments (ADR-0008 gate — required before any
      production dragon controls): mounted rider position/animation;
      steering option comparison (velocity vs pathfinder vs phase control
      with `setAware(false)`); death event timing + `EntityRemoveEvent`
      cause ordering during the death animation; vanilla first-victory
      `DragonEggFormEvent` (Paper FQN) + gateway emission +
      `hasBeenPreviouslyKilled()` behavior across repeated victories;
      `heal(double)` mid-flight interference.
- [ ] Dragons lineage flow on Paper 26.3 (after the ADR-0008 gate passes,
      when the controller/listener layer exists): observed gateway →
      `issueFromGateway` mints once and `ISSUED_ALREADY` on duplicate;
      egg PDC stamp round-trip; undeliverable delivery parks in recovery;
      hatch consumes the item only on service success; owner/dragon death
      hands the egg to the killer; egg survives lava/void/explosion routing
      into recovery (item protection listeners — controller phase).
