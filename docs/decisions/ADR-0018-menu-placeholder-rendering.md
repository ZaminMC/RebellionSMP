# ADR-0018 — Menu placeholder rendering and the ritual guide: Phase 7's menu surface

Date: 2026-10-28 · Status: accepted (implemented; unit suites green, boot
smoke on Paper 26.3 build 226)

## Context

Phase 7's roadmap scope (master prompt §18) ends with "the related
menu/lore flows". The controller slice (ADR-0017) shipped souls, casting,
signals, commands and placeholders, but menus were still missing, and two
defects blocked any menu that wanted to show per-player state:

1. **Routing dropped the viewer.** `CorePlaceholders` routed unknown
   parameters to module contributors through the player-independent
   `resolve(params)` entry — every viewer-scoped parameter (ritual
   discoveries, team/dragon/ward viewer facts) resolved null through
   PlaceholderAPI while the module believed it had answered. The routing
   predates `resolveFor` and was never threaded.
2. **The menu engine had no placeholder pass.** `MenuHost` rendered
   MiniMessage but never substituted `%...%` tokens; the
   `MenuSubject.displayName()` hook designed for it sat unused.

The module graph (ADR-0001) forbids a new hard edge rebellion-rpg →
rebellion-items, and the engine is deliberately items-private (§9:
RebellionItems owns the DeluxeMenus-shaped menu engine).

## Decision

### Viewer-threaded routing, one contract, both entry points

`CorePlaceholders` now answers core parameters through both contract
entries (`resolve` and `resolveFor` — before this change `resolve("version")`
returned null, an honest gap a new unit test exposed) and routes everything
else with the requesting viewer: `contributor.resolveFor(player, params)`.
Player-independent requests route with a null viewer, which viewer-scoped
contributors answer with null by contract. The contributor source is
injected (ServicesManager-backed in production, a fixed list in tests), so
the routing is unit-pinnable serverless — `CorePlaceholdersTest` pins
viewer forwarding, null-viewer routing, self-exclusion, first-answer-wins
and the core-parameter entries.

### Menu tokens: rebellion namespace only, misses stay visible

The menu engine substitutes exactly one token shape:
`%rebellion_<params>%`. The pure `MenuText.placeholders(raw, resolver)`
receives `<params>` — the same shape the expansion receives — and leaves
unresolved tokens as visible raw text (an operator sees their typo or an
absent module instead of a silently blank line; the PlaceholderAPI text
convention). Values substitute verbatim (`Matcher.quoteReplacement`
guards `$`/backslash). The dead `MenuSubject.displayName()` hook is
replaced by `MenuSubject.resolvePlaceholder(params)`; the Bukkit host
memoizes resolutions per open and asks the registered contributors with
the opening viewer — no PlaceholderAPI dependency exists on this path,
the contributors ARE the shared contract (core parameters resolve too,
because core is itself a registered contributor). Substitution applies to
the menu title, item display names and lore lines; click actions stay
unsubstituted for now (no requirement demanded them; adding them is a
conscious follow-up, not an oversight).

### The ritual guide ships in the menu-owning module, fed by the contract

`menus/ritual_guide.yml` (registered as `gui_menus.ritual_guide`,
`open_command: ritualguide`) lives in RebellionItems like every bundled
menu, while its per-viewer content comes from RebellionRPG's contributor —
the same data flow real DeluxeMenus deployments use (one menu plugin,
placeholder providers from feature plugins). No module edge is added; if
RebellionRPG is absent the discovery-state line renders its token as raw
text, which is documented in the menu header comment. The menu explains
the three shipped rituals (tracking / soul_communion / masking) with
numbers mirroring rebellion-rpg's config.yml, plus soul encounters and
the casting flow; `MenuEngineTest` pins that the communion token survives
renames.

### The discovery placeholder speaks the /ritual list vocabulary

`%rebellion_ritual_<key>_discovered%` now returns the operator-localized
`ritual.list.state.known`/`unknown` templates (MiniMessage-colored) instead
of a bare `true`/`false` — one vocabulary across chat and menus,
operator-tunable in messages.yml. A missing template resolves null (token
stays raw) rather than inventing wording. The pure halves (parameter shape,
state wording) are pinned serverless in `RpgPlaceholdersTest`; the
viewer-scoped resolution remains a live path over the join-loaded cache.

### Upgrade-path config merge (boot-smoke finding, fixed same session)

The first smoke boot of this slice caught a second live defect: on an
EXISTING install (data-folder config.yml extracted by an older version)
the new bundled menu silently never registered — `setDefaults` alone does
nothing for section enumeration in the pinned paper-api 26.3 (probed
empirically: `getKeys` ignores defaults without `options().copyDefaults(true)`;
the probe also proved scalar `get*` reads NEVER consult the bundled defaults,
and this module never saves config.yml, so copyDefaults' save-side copying
cannot touch the admin file). `loadOrCreateConfig` now enables
`copyDefaults(true)`: upgrades gain newly shipped `gui_menus` entries
automatically, admin-owned entries and scalar values keep full sovereignty,
and the on-disk file is never modified. Pinned by three new
`ItemsConfigTest` cases (upgrade gains, admin edit wins, scalar
sovereignty).

## Evidence

- `./gradlew build` green after the slice — **459 tests, 0 failures**
  across all eight modules (counted from the test XMLs: api 8, core 51,
  items 102, teams 50, wards 86, dragons 57, artifacts 53, rpg 52; core
  was 43 before this slice's 8, rpg 48 before its 4, items 88 before its
  14 — the registry pins were extended in place). New suites:
  `CorePlaceholdersTest`, `RpgPlaceholdersTest`, `MenuTextTest`, the
  extended bundled-menu registry pin, the updated `ItemsConfigTest`
  registry pin and the three upgrade-merge pins.
- Boot smoke on Paper 26.3 build 226, EXISTING data folder (the upgrade
  path, harder than a clean first boot): all eight installable jars enable
  in order; RebellionItems extracts ritual_guide.yml by gap-fill,
  registers `/ritualguide` and reports `menus: 3`.

## Consequences

- Phase 7's implementable scope is complete: persistence (ADR-0016),
  controller (ADR-0017) and menus (this ADR). What remains is the
  interactive pass (reagent feel, signal pulse timing, ride feel, E4b
  first-victory emission) and the operator-side wandering_soul calibration
  — all recorded as open in the ledgers.
- Viewer-scoped placeholders now actually resolve through PlaceholderAPI
  for every module that shipped one (teams, dragons, wards, rpg) — this
  was a live defect since session 3, fixed before any player besides the
  operator ever saw a `%rebellion_...%` token.
- Menu authors get per-player text without PlaceholderAPI installed; the
  engine's deliberate subset (rebellion namespace only) is documented in
  docs/DELUXEMENUS_COMPATIBILITY.md, whose stale open_command deviation
  note is corrected by this slice's docs pass.
