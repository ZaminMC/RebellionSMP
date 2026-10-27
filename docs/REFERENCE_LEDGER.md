# Reference ledger — inspected sources and conclusions

Every external source actually consulted during implementation is recorded
here with what it settled. Code reuse defaults to **no** unless the license
check below says otherwise. Checked dates are when the inspection happened.

## Toolchain and API

| Source | URL | Checked | Question | Conclusion |
|---|---|---|---|---|
| Paper API maven metadata | https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/ | 2026-10-27 | current 26.3 stable coordinates | `io.papermc.paper:paper-api:26.3-...` used via catalog pin `26.3.build.226-stable`; class files are major 69 (Java 25) |
| Paper API javadocs | https://jd.papermc.io/paper/26.3/ | 2026-10-27 | ServicesManager, PluginCommand, PluginMeta APIs | stable Bukkit-style APIs used by Core are present and unchanged in shape |
| Paper docs — plugin dev | https://docs.papermc.io/paper/dev/ | 2026-10-27 | plugin descriptor, load order, lifecycle | stable `plugin.yml` loader chosen (ADR-0003); `paper-plugin.yml` remains experimental |
| Paper docs — databases | https://docs.papermc.io/paper/dev/using-databases/ | 2026-10-27 | bundled drivers | Paper 26.3 server ships SQLite + MySQL Connector/J JDBC drivers on the server classpath; our JARs must not bundle them (ADR-0002) |
| HikariCP releases | https://github.com/brettwooldridge/HikariCP | 2026-10-27 | pool library license + version | Apache-2.0; `com.zaxxer:HikariCP:7.1.0` shaded + relocated into RebellionCore (relocation verified in shadow config) |
| sqlite-jdbc | https://github.com/xerial/sqlite-jdbc | 2026-10-27 | driver parity for tests | `org.xerial:sqlite-jdbc:3.49.1.0` matches the server-bundled driver; used for tests only, never shipped |
| PlaceholderAPI | https://github.com/PlaceholderAPI/PlaceholderAPI + https://wiki.placeholderapi.com/developers/creating-a-placeholderexpansion/ | 2026-10-27 | expansion contract | `PlaceholderExpansion` subclass; `getIdentifier/Author/Version`, `persist()`, `onPlaceholderRequest(Player, params)`; unknown params → `null` (PAPI leaves the text untouched). Expansion registered only when PAPI is present; compile-only dependency |

## Libraries verified by direct experiment

| Library | Version | Checked | Experiment | Result |
|---|---|---|---|---|
| adventure-text-minimessage | 5.2.0 (transitive of paper-api 26.3) | 2026-10-28 | probe harness (see WORKLOG 2026-10-28) over lenient + strict instances: unclosed tags, unknown tags, invalid tag args, placeholders | Default instance throws on **nothing** — malformed input degrades to literal text. `MiniMessage.builder().strict(true)` throws `ParsingExceptionImpl` ("All tags must be explicitly closed while in strict mode") on unclosed tags only. Decision: validation uses strict mode + catch-all placeholder resolver that never shadows standard tags (implemented in `CoreMessages`); render path stays lenient with real resolvers. |

## Menus (researched for Phase 2 — implementation not started)

| Source | URL | Checked | Conclusion |
|---|---|---|---|
| DeluxeMenus source (MIT) | https://github.com/HelpChat/DeluxeMenus | 2026-10-27 | menu registry pattern: `gui_menus:` top-level mapping, `file:` external file reference; MIT license permits schema inspiration; no code copied |
| DeluxeMenus default menu | https://github.com/HelpChat/DeluxeMenus/blob/main/src/main/resources/default_menu.yml | 2026-10-27 | field names in the wild: `menu_title`, `size`, `items`, `material`, `slot`/`slots`, `display_name`, `lore`, `left_click_commands` |
| DeluxeMenus wiki — GUI options | https://github.com/HelpChat/Wiki2/blob/master/clips-plugins/deluxemenus/options-and-configurations/gui.md | 2026-10-27 | documented GUI-level options incl. `open_command`, `size` bounds (9–54, multiple of 9) |
| DeluxeMenus wiki — items | https://github.com/HelpChat/Wiki2/blob/master/clips-plugins/deluxemenus/options-and-configurations/item.md | 2026-10-27 | item fields, `view_requirement`, click requirements/actions per button side |
| DeluxeMenus wiki — requirements | https://github.com/HelpChat/Wiki2/blob/master/clips-plugins/deluxemenus/options-and-configurations/requirements.md | 2026-10-27 | requirement types (has permission, has money via economy, …) — Phase 2 will implement a deliberate subset; see DELUXEMENUS_COMPATIBILITY.md |

## World map / optional integrations (research only; no code yet)

| Source | URL | Checked | Conclusion |
|---|---|---|---|
| BlueMap releases | https://github.com/BlueMap-Minecraft/BlueMap/releases | 2026-10-27 | v5.24 release notes mention 26.3 world compatibility — primary candidate for the `MapMarkerProvider` adapter (Phase 10) |
| Dynmap (Modrinth `fRQREgAc`) | https://modrinth.com/project/fRQREgAc | 2026-10-27 | listing reports support through 1.21.x only — **not** claimed compatible with 26.3; adapter deferred |
| Simple Voice Chat | https://github.com/henkelmax/simple-voice-chat + voicechat-api-bukkit | 2026-10-27 | proximity voice requires the client-side mod; any adapter must document that (Phase 10 decision) |
| EarthMC map archive | https://earthmc.net/download | 2026-10-27 | Terra Aurora archive ≈ 50.8 GB, MC 1.21.8 era — an ops-managed asset, never committed or upgraded as part of code work; see OPERATIONS.md |
| Rideable_enderdragon (historical) | https://github.com/tmvkrpxl0/Rideable_enderdragon | 2026-10-27 | old, unlicensed practice plugin — research pointer only; dragon work requires its own 26.3 spike (Phase 5) |

## License positions

- RebellionSMP: GPL-3.0 (repository `LICENSE`).
- HikariCP: Apache-2.0 — compatible with GPL-3.0 use; shaded + relocated, license notice retained in `docs/LICENSES.md`.
- DeluxeMenus: MIT — schema inspiration only; no source copied into this repository.
- PlaceholderAPI: GPL-3.0 — compile-only dependency (provided by server at runtime), never bundled.
- adventure/MiniMessage: MIT — used transitively via the Paper server runtime, not bundled.
