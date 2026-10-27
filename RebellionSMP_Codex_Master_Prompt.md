# RebellionSMP — Codex Master Engineering Prompt

**Project:** RebellionSMP  
**Repository:** https://github.com/ZaminMC/RebellionSMP  
**Primary namespace:** `net.zaminmc`  
**Target server:** latest stable PaperMC for Minecraft Java Edition **26.3**  
**Target Java:** Java 25, after confirming the official Paper 26.3 toolchain requirements  
**Working branch:** `develop`  
**Stable branch:** `main`  
**Requested commit author name:** `Abasing`

---

## 0. Your role and the mission

Act as the lead engineer responsible for building RebellionSMP as a production-grade, modular Minecraft survival server platform. You are not writing a quick feature plugin, a demonstration, or a collection of disconnected gimmicks. Build a coherent game system in which scarce power, exploration, intelligence, team relationships, dragon ownership, rituals, imprisonment, and risk interact consistently over a long-running Earth-map SMP.

Work directly in the checked-out repository. Investigate before coding, make evidence-based engineering decisions, implement working vertical slices, test them, document real behavior, and keep the work reviewable. Do not stop after writing an architecture document or creating empty module folders. Do not attempt to implement every subsystem as one enormous unreviewable change either. Move through the roadmap below in dependency order, maintaining a buildable repository at every meaningful checkpoint.

The quality bar is an enterprise-maintainable plugin family: strict module boundaries, persistent state, migrations, defensive event handling, permission checks, clear diagnostics, bounded background work, recoverable failure states, comprehensive tests for critical rules, configuration validation, and documentation that describes actual implemented behavior.

**Do not ask for confirmation for ordinary engineering choices.** Resolve ambiguity using the explicit rules in this prompt, research the live APIs/repositories where required, record meaningful decisions in ADRs, and continue. Ask only when a genuinely blocking choice cannot be safely resolved from repository evidence or these requirements. Do not silently make risky assumptions about the live world, production data, license rights, or destructive operations.

## 1. Non-negotiable project rules

1. **Target Paper 26.3 only for the initial release.** Verify the current Paper 26.3 API, Java requirement, plugin metadata format, dependency versions, and current build guidance before selecting versions. Do not blindly copy older examples. The official Paper 26.3 release/API references are in the reference ledger below.
2. **Use namespace `net.zaminmc`.** Use conventional subpackages under it, e.g. `net.zaminmc.rebellion.core`, `.api`, `.items`, `.dragons`, `.artifacts`, `.teams`, `.rpg`, `.alchemy`, `.prison`, `.wards`, `.economy`, `.cosmetics`, and `.integration`.
3. **This is a multi-JAR product.** Do not package all game features into one giant JAR. Produce separate installable JARs for the API/core and feature modules. Define a dependency graph, startup order, failure behavior, artifact naming, and install documentation. The shared contracts must have one runtime class identity; do not shade duplicate copies of shared API classes into every module.
4. **`develop` is the integration branch. `main` is stable/release-only.** Never implement features directly on `main`, never force-push, never rewrite published history, and never merge untested work into `main`. Create `develop` safely if absent. Make feature branches from `develop`; open focused PRs into `develop` when repository access supports this. Only release-ready work may go from `develop` to `main` through an explicit reviewed release PR.
5. **Commit attribution:** set the repository-local Git author name to `Abasing` if the configured identity does not already match. Preserve the valid email already configured for the authenticated account. Never invent an email, impersonate another real person, add a model/agent as co-author, or rewrite historical commit metadata. Confirm the final commit author with `git log -1 --format=fuller`.
6. **No land claims. None.** Do not implement claim blocks, chunk claims, town/nation claims, land ownership borders, claim-based block protection, or automatic territory denial. Watch wards and prison structure protection are limited game mechanics, not land-claim systems. A ward informs its owner that someone is approaching; it does not stop the person entering, breaking blocks, or using the land.
7. **Persistence is mandatory.** Important world state and player progression must survive restart, crash, reconnect, module reload, and ordinary item movement. SQLite is the out-of-box database. Also support a server database such as MySQL/MariaDB through configuration. Choose and document the exact supported database matrix after checking library compatibility. No blocking JDBC work on the server thread.
8. **Every feature module owns its configuration.** Each actual feature module must provide its own `config.yml` and `messages.yml`, plus relevant `items.yml`, `rarities.yml`, `menus/` or other focused files as required. Do not make one shared 2,000-line config the control panel for the entire plugin. An API-only module may omit user-facing configuration if it genuinely has no settings or messages; explain that exception.
9. **Menus use a DeluxeMenus-shaped YAML contract.** Inspect the current DeluxeMenus source and documentation first. Match the documented field names and layout for supported features instead of inventing a competing menu syntax. Document the exact compatibility subset. Do not falsely claim complete DeluxeMenus compatibility if any documented features are unsupported.
10. **PlaceholderAPI integration is required but optional at runtime.** The plugin family must continue to boot when PlaceholderAPI is absent. When present, register a correctly named expansion and expose documented `%rebellion_...%` placeholders. Avoid database I/O in placeholder evaluation.
11. **Treat performance and correctness as product requirements.** No per-player thread creation, unbounded async queues, per-tick full-world scans, SQL on the main thread, repeated full inventory scans every tick, global entity scans for every movement, or expensive work in hot event handlers.
12. **Do not promise a crash-proof server.** Implement controlled module failure, clear diagnostics, persistence recovery, and safe shutdown. Do not swallow fatal JVM errors, replace Paper's global uncaught-exception handler, or suppress critical server failures in an attempt to claim crash protection.
13. **Source research is mandatory.** For every integration involving undocumented/uncertain behavior, open the actual current source, API documentation, or maintainer example. Record the URL, checked date, revision/version where available, conclusions, and license in `docs/REFERENCE_LEDGER.md`. No guessing at APIs, YAML schema, entity controls, or menu behavior.
14. **Respect licenses.** Reference repositories to learn their documented behavior and design. Do not copy source code into RebellionSMP unless the actual source file's license and its compatibility with this project's chosen license have been checked. A public repository or downloadable world is not automatically open-source or freely redistributable.
15. **No fake enterprise polish.** No filler docs, fake metrics, pointless abstractions, redundant wrappers, excessive comments that repeat code, empty interfaces created “for future flexibility,” or hundreds of TODOs. Comments must explain invariants, non-obvious API behavior, recovery logic, and reasons for decisions. Any TODO must name a concrete follow-up issue/phase and must not conceal missing behavior that acceptance criteria require.

## 2. Confirmed repository and external compatibility facts

Before making changes, re-check the repository because its state may have changed since this prompt was prepared. The initial inspection showed `ZaminMC/RebellionSMP` public, empty (`size: 0`), with `main` as default and no `develop` branch visible. Bootstrap safely: if there is a valid `main` commit, create `develop` from it. If the remote really has no commit, create the initial project commit on a local `develop` branch and push only `develop`; do not create a work commit on `main`.

The Modrinth project ID `fRQREgAc` resolves to **Dynmap**, a browser-based map renderer. Its listing currently reports support through Minecraft 1.21.x, not 26.3. Do not make Dynmap a hard dependency or state that it is 26.3-compatible without a newer verified release. The better researched current mapping candidate is **BlueMap**: its project listing includes Minecraft 26.3, and the v5.24 release notes explicitly mention 26.3 world compatibility. Design RebellionSMP's mapping integration behind an adapter and make BlueMap the default optional integration candidate, while documenting Dynmap as unverified for the target version.

The EarthMC download page offers archived worlds. The currently listed Terra Aurora archive is described as a 2022–2026 map for Minecraft 1.21.8 and is about 50.8 GB; Terra Nova is about 50.6 GB and covers an older period. This does **not** mean a world is already verified for Paper 26.3. Do not download, unpack, upgrade, rewrite, or commit a 50 GB world as part of routine code work. Keep world assets outside the source repository, test on a disposable copy, preserve hashes/metadata, back up before conversion, and validate load/terrain/chunks/entities/portals on the target Paper build. Never alter the only copy of a world. Paper warns that upgrading a world can make downgrading impossible.

The historical `Rideable_enderdragon` repository below describes itself as a first/practice plugin, targets old versions, and has no license shown in its repository metadata. Use it only as a historical research pointer; do not treat it as production-quality code or copy its source. Inspect the current Paper 26.3 API and run a small test plugin to prove dragon passenger, movement, controls, fireball, unload/reload, and death behavior before choosing an implementation. The dragon system is a high-risk subsystem and must not be built on assumptions.

## 3. Required source and documentation ledger

Open these references during Phase 0. Record what you inspected and which conclusions were accepted or rejected. Follow current documentation links from these pages when necessary. Check dates/versions, not just search snippets.

### Paper, server APIs, lifecycle, databases
- Paper source: https://github.com/PaperMC/Paper
- Paper 26.3 API Javadocs: https://jd.papermc.io/paper/26.3/
- Paper 26.3 release notes: https://papermc.io/news/26-3/
- Paper plugin development: https://docs.papermc.io/paper/dev/getting-started/paper-plugins/
- Paper database guide: https://docs.papermc.io/paper/dev/using-databases/
- Paper plugin lifecycle/runtime docs: https://docs.papermc.io/paper/dev/how-do-plugins-work/
- Paper official download/build service guidance: https://docs.papermc.io/misc/downloads-service/

### Menus and placeholders
- DeluxeMenus source (MIT listed by the repository): https://github.com/HelpChat/DeluxeMenus
- Actual default menu file: https://github.com/HelpChat/DeluxeMenus/blob/main/src/main/resources/default_menu.yml
- Actual basics menu example: https://github.com/HelpChat/DeluxeMenus/blob/main/src/main/resources/basics_menu.yml
- DeluxeMenus external menu documentation: https://github.com/HelpChat/Wiki2/blob/master/clips-plugins/deluxemenus/external-menus.md
- DeluxeMenus GUI options: https://github.com/HelpChat/Wiki2/blob/master/clips-plugins/deluxemenus/options-and-configurations/gui.md
- DeluxeMenus item options: https://github.com/HelpChat/Wiki2/blob/master/clips-plugins/deluxemenus/options-and-configurations/item.md
- DeluxeMenus requirements: https://github.com/HelpChat/Wiki2/blob/master/clips-plugins/deluxemenus/options-and-configurations/requirements.md
- PlaceholderAPI source: https://github.com/PlaceholderAPI/PlaceholderAPI
- PlaceholderExpansion guide: https://wiki.placeholderapi.com/developers/creating-a-placeholderexpansion/

### World map, visual map, optional voice chat
- EarthMC archived map downloads: https://earthmc.net/download
- BlueMap source and releases: https://github.com/BlueMap-Minecraft/BlueMap
- BlueMap 26.3-capable release reference: https://github.com/BlueMap-Minecraft/BlueMap/releases
- BlueMap Modrinth listing: https://modrinth.com/plugin/bluemap/versions
- User-specified Dynmap project: https://modrinth.com/project/fRQREgAc
- Simple Voice Chat source: https://github.com/henkelmax/simple-voice-chat
- Simple Voice Chat Bukkit API example/template: https://github.com/henkelmax/voicechat-api-bukkit
- Historical dragon-riding reference, **research only / old / no license shown**: https://github.com/tmvkrpxl0/Rideable_enderdragon
- Related old-version resource description: https://www.spigotmc.org/resources/rideable-ender-dragon.72383/

Do not treat the list as permission to use the source code or media. Record the actual license field from each repository and any subdependency. For all source-derived decisions, record a permalink to the relevant file/line or release/tag if possible. Add further high-quality repositories only if they illuminate a concrete feature; do not pad the ledger with unrelated links.

## 4. Research protocol: prove before you implement

For each uncertain capability:

1. Define the required behavior and failure cases in plain language.
2. Inspect the current Paper 26.3 API/Javadocs and, where needed, the corresponding implementation in the current Paper source or its upstream Minecraft mappings.
3. Inspect at least one maintained, licensed open-source implementation or a maintainer-provided example if one exists. If only a legacy implementation exists, clearly mark it legacy and use it only to identify historical approaches.
4. Build a tiny throwaway test or automated integration test against Paper 26.3 for behavior the API docs do not settle. Examples include mounting an Ender Dragon, controlling flight without NMS, obtaining a reliable kill attribution, creating a valid End gateway, preserving item PDC through stack movement, and loading the selected EarthMC map.
5. Choose the least brittle implementation that meets the requirements. Prefer official Paper APIs. Use NMS/reflection/protocol manipulation only if the ordinary API demonstrably cannot meet a required behavior; isolate it in one version-specific adapter, pin it to 26.3, explain the cost, and add tests that fail loudly after an update.
6. Put the evidence and decision in `docs/REFERENCE_LEDGER.md` and an ADR for important architectural decisions.
7. If a desired feature is impossible or only achievable with client mods, state that exactly. Do not fake it, simulate unrelated behavior, or claim success without a verified test.

Never invent methods/classes because an older plugin used them. Never assume that an Ender Dragon has normal mob pathfinding or behaves like a horse. Never assume that a Bukkit event provides a killer for every damage/death chain. Never assume a map's public download means it is licensed for redistribution.

## 5. Product concept and connected game loop

RebellionSMP is a long-running geopolitical/RPG Earth SMP with no land claims. The world should feel interconnected: discovery unlocks rituals; rituals provide limited intelligence; intelligence supports conflict and rescue; team relationships shape conflict; dragons and dimension artifacts act as scarce strategic assets; death sends players into a dangerous prison; prison consequences change long-term health/progression; rare items create specializations; cosmetic monetization does not grant combat power.

Keep the game playable for new and returning players. The rarest items should create stories and objectives, not make every other build irrelevant. Make all critical numbers configurable and document their defaults. Add admin tools that can inspect/recover state without allowing ordinary players to duplicate scarce assets.

### 5.1 Teams and communication

Implement player-created teams with:
- create, rename (configurable cooldown/cost), disband, invite, accept, decline, kick, leave, transfer leadership, roles/ranks, member list, team chat, and an auditable management history;
- no direct or indirect damage between members of the same team when friendly fire is disabled, including melee, projectiles, persistent fire, TNT/explosions attributed to a player, potion/cloud effects, pets/dragon attacks, and delayed damage where attribution can be resolved safely;
- a team-only chat channel and opt-in/command-toggle team chat mode;
- configurable alliance/enemy relationships as diplomacy, distinct from membership. Do not silently give allies every team privilege;
- safe behavior for offline leaders, simultaneous invites, joins while full, team deletion, stale cached profiles, duplicate names, disconnects during acceptance, database failure, and reconnects;
- UUID identities, normalized unique team identifiers/names, and database uniqueness constraints to stop racing invites or double membership.

Team management must be permission checked, rate limited where abuse is possible, and atomic in persistent storage. Never trust client-provided names or inventory contents as authority for access control.

**Voice chat reality:** Minecraft Java Edition has no native proximity-voice channel provided by a server plugin alone. Implement ordinary text proximity chat as a vanilla-client feature. Make true voice proximity an optional integration module for Simple Voice Chat and document that players need its compatible client-side mod, unless a researched alternative demonstrably avoids that requirement. Do not claim mod-free voice chat.

### 5.2 No-claim watch wards and outsider warnings

Build a **watch-ward / sentry / lookout** system, not a land-claim system.
- A player/team can bind a ward to a configured anchor/block/item or placed beacon-like device. Each ward has owner/team, radius, cooldown, active state, permissions to manage it, and optional countermeasures.
- When a player who is not in the ward owner's team enters the configured detection radius, alert the ward owner/team that an outsider is approaching and show distance, for example `An outsider is approaching from 2.4 km away.`
- Wards only report information; they do not prevent movement, block breaking, container access, damage, or building. Do not create invisible protection borders or call this territory ownership.
- Use a spatial index or bounded candidate lookup. Do not scan all online players for every movement event or every tick. Process meaningful position changes at a configurable interval and avoid alert spam through per-ward/per-player cooldowns and enter/leave state tracking.
- Distance in kilometers must be calibrated, not guessed. Use horizontal world-coordinate distance and a configurable `world-units-per-kilometer` or equivalent per world. Ship the default as unset/disabled until the operator configures/calibrates it against the imported Earth map. If uncalibrated, report blocks/meters only as explicitly configured; do not label arbitrary blocks as real kilometers. Document coordinate system, dimension transitions, same-world restrictions, border effects, and how an admin calibrates the scale.
- Support decoys, ward detection range, warning delay, player masking/counter-rituals, and alert channels only when each is fully implemented and tested. All are config driven.
- Wards must not protect land from non-team players. No claim-based flags, protected chunks, or town-style ownership boundaries.

### 5.3 Rare souls, discovery, rituals, and player tracking

Create a low-frequency encounter system for rare souls/NPC encounters. The first design is a configurable, deterministic and debuggable encounter state machine, not random unchecked entity spawning.
- Spawn conditions, world/biome/time/player-count constraints, maximum concurrent encounters, cooldown, cleanup, and reward table must be configurable.
- Use supported Paper/Bukkit entities or a documented maintained NPC integration. Do not make Citizens a hard dependency unless a researched implementation proves it is needed and that choice is documented.
- A player who helps a rare soul through its configured quest/event can discover a ritual recipe or knowledge flag. Persist the discovery against UUID, record it idempotently, and show feedback in `messages.yml`.
- A tracking ritual can reveal information about a specifically selected player, using configured ingredients, preparation, radius/range, duration, cooldown, cost, precision, countermeasures, and failure chance. Keep it a game mechanic with visible in-game feedback; do not use external IP/location data. Support names that resolve to known UUIDs and define behavior for offline/unknown targets.
- Tracking must not reveal more precision than configured. Support imperfect direction/range signals and counter-rituals to make tracking contestable. Rate limit ritual attempts and prevent ingredient duplication/race conditions.
- Ritual state changes and consumed ingredients must be committed as one logical operation. If a database write fails, fail closed and refund safely or leave the player with a clearly reported recoverable state; never consume ingredients and falsely report that a ritual succeeded.

### 5.4 Items, rarity, talismans, artifact items, and relics

Create a shared item-identity and item-lore service with stable, versioned PersistentDataContainer keys. Never identify valuable items by display name or lore alone.

Two axes must remain separate:
- **Rarity:** `COMMON`, `UNCOMMON`, `RARE`, `EPIC`, `LEGENDARY`, `MYTHIC`, `SPECIAL`, `VERY_SPECIAL`, `GOD`.
- **Item progression/tier:** `NORMAL`, `ARTIFACT`, `RELIC` for talisman families where applicable.

Do not conflate the `ARTIFACT` talisman tier with item rarity. All labels, colors, weights, enchant glints, and default item mappings are data-driven in YAML. “Mythic” is the canonical enum/key; user-facing spelling may be localized via messages/config.

Requirements:
- Provide a resolver for every current Paper 26.3 `Material`. Define explicit overrides and a safe `COMMON` fallback for vanilla materials such as dirt and glass bottles. Avoid brittle version-specific hardcoded exhaustive enum lists if a safe fallback can cover materials introduced by future updates.
- Player-facing item lore must show rarity where enabled: e.g. dirt is Common and a glass bottle is Common. Because Minecraft has no API that automatically appends lore to every block/item without modifying its item metadata, implement item annotation safely and document coverage. Add lore to eligible stacks as they pass through controlled flows (join inventory normalization, pickup, drops, block drops, crafting/smithing outputs, plugin-created items, and opening/accessing containers where configured), and optionally provide an admin command to annotate a selected stack or bounded inventories. Do not scan every chunk/world each tick. Preserve existing display names, lore, enchants, custom model data/components, damage, PDC, attributes, and stack amounts. Prevent duplicate rarity lines and avoid corrupting items whose metadata is intentionally managed by another plugin. Include a configurable mode such as `custom-only`, `managed-items`, and `all-items`; make `all-items` explicit because it is a wider metadata mutation policy.
- For rarity display, define conflict policy if other plugins modify lore later. Do not claim that every item everywhere is decorated unless your implementation and tests cover it. Explain edge cases in docs.
- Implement talisman families with normal, artifact, and relic tiers; custom activation rules, cooldowns, charges, costs, and upgrades must be data driven. Include examples that demonstrate the progression without making configuration a Java-code editing exercise.
- Persist custom item identity and unique ID where duplication matters. On invalid/forged IDs, fail closed and log a sanitized diagnostic. Never rely on lore or material alone to authorize artifact ownership.
- Provide admin audit/restore tools for unique item instances; never expose arbitrary item minting to ordinary users.

### 5.5 The three dimension artifacts and the Rebellion event

There are exactly three unique dimension artifacts, one anchored to each dimension:
- **Overworld:** theme of life/dominion.
- **Nether:** theme of fire/destruction/war.
- **End:** theme of void/knowledge/dimensional influence.

Design each around a small set of memorable, configurable powers instead of a bag of unrelated buffs. The artifact identity, holder, dimension of origin, state, power cooldowns, and event history are persistent. There must never be duplicate valid copies.

- Each artifact begins in a protected, discoverable origin/realm state. “Protected” means its origin objective cannot be permanently deleted or irrecoverably griefed; it does not establish a general territory claim. Use a dedicated artifact encounter/structure and explicit recovery logic.
- Artifacts can change hands through valid gameplay. They can be dropped, stolen, captured, returned, or become lost. “Lost/destroyed” must transition to a recoverable state. After a configurable return delay and event check, the artifact should reappear at its protected origin, with the ledger reflecting the transition.
- If one player controls all three unique artifacts, trigger the configured **Rebellion Event** once, with a durable event ID and idempotent transition. Do not accidentally trigger three times from duplicate inventory events or an artifact copied by another plugin. The default trigger scope is one player possessing/controling all three; if a team-wide trigger is added, make it a separate explicit configuration option.
- The catastrophe can include dimension instability, global warnings, empowered encounters, and maximum-health scars. The exact effects must be configurable and bounded. There must be a clear warning/telegraph and an auditable initiation reason.
- Combined powers may include an explicitly configured world-wide kill/purge action as requested, but treat this as a catastrophic, expensive, opt-in-by-config power requiring a valid ritual/state, warning, cooldown, and final confirmation. Define whether the target set is online players or all stored player records. Do not accidentally kill the activating holder unless the configured rules explicitly say so. Do not allow a command injection to invoke this. Record outcomes and handle disconnects/restarts mid-event idempotently.
- Overpowering actions may consume/destroy the three artifacts and cause persistent player maximum-health degradation. Destruction means the current artifact instance is spent; it does not invalidate the artifact lineage permanently. Set health scars through supported APIs and persistent records rather than repeatedly stacking untracked attribute modifiers. Keep a documented hard floor so profiles cannot become unplayable due to rounding/stacking. No automatic reset on death, relog, or restart. Recovery, if configured, requires a rare explicit ritual/objective and is never free/default automatic.
- After the event/spending or a recoverable loss, artifacts transition to “returning” and then to their original protected realms, so the world can reconquer them and the SMP does not have a permanent winner. Every stage must be crash recoverable.

Use a state machine, database constraints, unique artifact IDs, and audit entries. Do not model this only as lore text or an in-memory boolean.

### 5.6 Finite dragons: 20 server-wide lineages, no breeding

Critical correction: the limit is **20 obtainable dragon lineages across the entire server**, because the End has at most 20 eligible End gateways. It is not one dragon per End portal, not 20 dragons per world, and not an unlimited pet system. The cap must be configurable, default `20`, with a maximum that cannot silently exceed the configured End gateway/lineage policy.

Separate the Ender Dragon boss encounter from player-owned pet dragons:
- A legitimate Ender Dragon boss victory may open the next vanilla End gateway and issue one unique **pet-dragon lineage egg** if a lineage slot remains and the gateway event is valid. Confirm current Paper 26.3 behavior before implementation; do not spawn fake gateways on every death if vanilla already handles them. Prevent multiple event listeners/retries from minting duplicate lineage eggs. At the configured cap, subsequent boss victories may follow vanilla XP/reward policy but must not create additional obtainable pet lineages.
- Pet dragon death/owner death is a **lineage transfer**, not a new lineage and not a new gateway unlock. It must never increase the total number of lineage slots. Every lineage has an immutable `lineage_id` and a persistent lifecycle state.
- A new lineage egg can be hatched to create its player-owned dragon. Hatching must reserve the lineage atomically; concurrent clicks, reconnects, inventory cloning, crashes, and duplicate event delivery must not create multiple dragons from the same egg. Egg state cannot be destroyed by lava, fire, void, explosions, dispensers, hoppers, item despawn, or accidental inventory loss; route protected eggs into a recovery/mailbox state when they cannot safely be delivered.
- Dragons cannot breed. Block all vanilla/entity/command breeding-like paths and ensure no player can mint an additional lineage through a command, egg, spawn event, or NBT item.
- A living pet dragon is unique and owned by one player. If its owner dies by PvP or another valid player-attributed cause, the dragon performs its death presentation and transitions to egg/recovery state; the valid killer receives the lineage egg. If a player kills the pet dragon directly, the killer receives that same lineage egg. The lineage is preserved, not permanently deleted. Death processing must be idempotent so simultaneous owner death + dragon death cannot produce two eggs. Define safe behavior for environmental deaths, suicide, void, disconnect, unknown killers, deaths outside loaded chunks, kill attribution timeout, staff interventions, and unavailable inventories; use a protected recovery mailbox/claim flow if there is no valid killer or the egg cannot safely be delivered.
- “Dragon cannot be destroyed” means no dragon lineage/egg can be permanently deleted through normal gameplay. The physical dragon can die using the requested death animation, but the lineage returns as a protected egg. Only an explicit audited admin action should retire a lineage, and that action must require confirmation and be logged.
- Rideable pet dragons must be faster than ordinary Elytra flight without rockets, but tune actual speed based on controlled 26.3 tests. Support owner mount, dismount safety, calling/recall, permissions, cooldown/range/conditions, rider launch into the air, target-aware fire attack, and safe movement through world/chunk boundaries. Do not teleport a dragon into unloaded terrain or through protected/invalid worlds.
- Fireballs must not destroy blocks. Default attack budget: no more than 30 fireballs per dragon per rolling 60-second window (or a stricter configured budget); ensure the scheduler and network load remain bounded. Prevent fireball spam using per-dragon token-bucket/cooldown accounting. Define collision, owner damage, friendly-fire checks, water weakness, rain/water behavior, and fire resistance.
- Slight water weakness should be a configurable, tested penalty, not an instant unfair death. Feeding Ender Pearls restores dragon health, with configurable amount, cooldown, valid owner/range, and atomic pearl consumption + health update. Do not allow an event retry to consume a pearl twice or heal twice.
- Support `/dragon call`, `/dragon status`, `/dragon dismiss`/safe recall if applicable, `/dragon name` if allowed, and admin audit/recovery commands. Store dragon health, XP/stats if any, owner UUID, location/state, cooldowns, and last action. Do not leave a living dragon orphaned on unload/restart.
- Possible dragon attributes include health, flight speed, fire cooldown, maneuverability, call range, and rider abilities. Keep stats bounded and non-random by default; any variance must be configurable and visible. Do not make early lineages permanently unbeatable because of unbounded random rolls.

**Implementation gate:** before writing the full dragon controller, build a minimal Paper 26.3 test plugin or integration test that proves passenger mounting, controlling/approximating flight, fireball launch, chunk unload/reload, and death behavior. If the modern API cannot make the dragon reliably controllable, compare alternatives with evidence. Keep any required NMS implementation behind a single `DragonController`/version adapter, and document exactly why the public API was insufficient. Do not rely on the old 1.12 Rideable Ender Dragon plugin as a drop-in solution.

### 5.7 Brewing progression, potion bags, and automatic potion rules

Create a real progression system with two distinct paths:
- **Power path:** unlock stronger effects, longer durations, or advanced recipes at required brewing levels.
- **Efficiency path:** lower resource costs, better storage/access, controlled potion-bag capacity, or safer consumption automation. Efficiency must not quietly add combat strength unless configured as an explicitly balanced effect.

Implement:
- persisted brewing level/XP, unlocks, recipes, costs, and prerequisites;
- config-driven potion bags with clear inventory safety rules (no nesting bags, no duplicating stacks via shift-click, death, crafting, drag, cursor, or close events);
- optional player-defined auto-consumption rules, e.g. a chosen potion triggers below a health threshold, when burning, entering the Nether, or falling. Each rule must have a whitelist of allowed triggers/effects, cooldowns, toggle, storage requirement, resource consumption, conflict handling, priority, and audit/debug reason. Never make an infinitely replenishing potion bot. Make rules opt-in per player and subject to server policy;
- strict validation for potion effects, amplifier, duration, recipes, invalid material names, null item metadata, and unsupported effect combinations;
- atomic potion/cost consumption and safe fallback if a player inventory changes between rule evaluation and execution.

### 5.8 Death, imprisonment, escape, and health restoration

Every valid player death is routed into the global prison system according to configuration. Do not permanently soft-lock a player due to a missing jail world, database outage, disabled module, or broken config.

- Build a dedicated configurable prison structure/area. A built-in bounded structure builder or verified NBT structure template is acceptable. Before generating it, validate world/anchor/volume and refuse to overwrite existing builds unless the admin deliberately confirms a force operation. Keep its footprint documented. Protection applies only to the prison structure and essential mechanics, not to player territory.
- The prison must not be escapable by simply breaking blocks, using pearls/teleports, chorus fruit, elytra, vehicles, dragon call, death loops, disconnect/reconnect, world switches, nether portals, or known escape vectors. Deny ordinary player commands and external teleports while jailed, with explicit configurable exceptions for staff/recovery. Avoid trapping admins permanently.
- Provide an escape route based on meaningful objectives and/or defeating powerful NPC guards. Build guards using supported entity APIs with clear respawn/encounter rules; do not load a huge wave of entities continuously. Provide an external rescue mechanic so other players can enter, complete objectives, and free a prisoner when policy allows.
- Provide a very expensive paid release route. The fee formula is `death_score × inflation_factor × 5`; define units/rounding/minimum/maximum and overflow handling, store the fee calculation inputs and result for audit, and make the factor configurable. Use the shared economy provider/adapter so the feature works without hard-coding a third-party economy. If an optional Vault bridge is implemented, use it only as a deliberate adapter with correct dependency metadata. Use a decimal-safe representation appropriate to the currency; never use unchecked integer multiplication that can overflow.
- On prison release, max health does not instantly revert. The player must perform configured rehabilitation tasks/objectives to restore lost health bars. Track health scars and restoration progress persistently. Task completion and health restoration must be idempotent and have no inventory/database race that grants double healing.
- On death, apply a configurable maximum-health penalty and death-score change once. Do not deduct health twice because death and respawn events both fire or because the player logs back in. Set a hard floor and recovery path so it is possible to continue playing. No free reset by relog/restart/ordinary death.
- If teleporting into prison fails, retain a durable `pending_imprisonment` state and retry safely on respawn/join. If a prison world or its configuration is missing, fail closed into a safe spawn/administrative recovery state and report a clear actionable error rather than silently releasing the player or crashing the server.
- Provide `/prison status`, `/prison escape` where appropriate, admin inspect/unlock/recover, and auditable staff overrides. Staff overrides must not mint currency/items or clear another player's debt silently.

### 5.9 Economy boundaries and fee safety

Implement a small, explicit economy service if the prison/team mechanics need an internal balance; do not create an unrequested sprawling shop/auction economy just because it might be useful later. The service must use atomic ledgers, UUID account identifiers, idempotency keys, exact decimal/integer units, transaction reasons, and audit history. Support an optional adapter interface for third-party economy integration; Vault support may be an optional module, never a hard dependency unless the product later makes that a deliberate requirement.

- Never make external economy calls on the main server thread if they can block; respect the API thread contract and marshal world/player changes back onto the proper server scheduler.
- Prevent double charging when a player clicks a GUI repeatedly, disconnects mid-purchase, retries after timeout, or invokes a command at the same time. For payment and reward flows, define whether the transaction commits before the reward is issued and how rewards are reconciled after a crash.
- The release fee calculation must be transparent to the player and logged as structured values: score, inflation factor, multiplier, rounding, result, provider, and transaction ID.

### 5.10 Cosmetic-only monetization

Cosmetic content is the only monetization path described by this prompt. Do not introduce paid stats, permanent combat boosts, extra dragon lineages, stronger artifacts, shorter prison penalties for purchasers, pay-to-win potion unlocks, or paid health recovery.

Implement cosmetics as explicit cosmetic identities/unlocks, possibly displayed as “custom cosmetic enchantments”; do not accidentally use native gameplay enchantments or attribute modifiers for effects that should be visual only.
- Example: visual lightning striking the wearer/target as an effect, with configurable cosmetic levels I–III and proc chances defaulting to 10%, 50%, and 90% respectively. The user specified levels I and III (10% and 90%); level II can default to 50% but must be configurable. Use a visual-only effect API; it must not summon damaging lightning or change combat outcomes.
- Add standby particles/trails, cosmetic glints, kill/death visuals, dragon cosmetics and other purely visual effects as separate opt-in configurations.
- Cosmetics must never alter damage, defense, movement speed, cooldowns, detection, rarity, progression, health, prison outcomes, artifact power, dragon lineage cap, or resource yields. Verify this by inspecting every cosmetic code path and testing damage/attribute invariants.
- Provide permission/reward/entitlement interfaces so cosmetics can be granted by staff, events, or a future store, but do not implement payment processing or embed shop credentials in this project. Keep the cosmetic effect entirely disabled if entitlement state is invalid. Log unlock/transfer/revoke events.
- Rate-limit particles and visual entities. Avoid thousands of permanent armor stands or per-player effect threads.

### 5.11 Map and Earth-world integration

The EarthMC world archive is a separately managed asset, not part of the plugin build. Provide an operations guide covering archive choice, checksums if provided, disk space, safe unpacking, staging copy, version upgrade validation, backup/restore, and world folder/configuration discovery for Paper 26.3. Never ship world save files, database files, or downloaded map archives in Git.

Expose optional integrations through narrow interfaces:
- `MapMarkerProvider` for publishing configured ward/ritual/artifact markers if a supported web-map plugin is installed;
- BlueMap adapter as the primary candidate because its v5.24 release notes/listing explicitly mention 26.3;
- Dynmap adapter only if a current verified release supports 26.3. Do not load incompatible classes when the plugin is absent or incompatible, and do not make either renderer mandatory for core gameplay.

Do not expose secret artifacts, player tracking, ward locations, or private team data to public web maps unless an explicit admin setting permits the exact marker category. Public map markers must be privacy-by-default.

### 5.12 Proximity text chat

Implement a vanilla-client-compatible proximity **text** chat with configurable distance, world scoping, permissions, channel toggles, and optional whisper/shout modes. Use Adventure components and safe formatting. Avoid running a global player scan per message if an indexed candidate lookup is practical. Respect team chat and staff channels. Do not assume real voice chat is part of vanilla Minecraft.

## 6. Modular architecture and JAR layout

Use a Gradle multi-project build with the Gradle wrapper checked in. Choose the currently supported Gradle/JDK/plugin versions from official sources and pin them. Use Java (not Kotlin) unless repository state gives a strong reason otherwise. Enable consistent formatting, static analysis, and repeatable builds. Avoid unnecessary dependencies.

Suggested high-level structure (refine it in ADRs if evidence says a different split is materially better):

```text
RebellionSMP/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradlew / gradlew.bat
  gradle/wrapper/
  rebellion-api/             # shared API contracts and DTOs; unique runtime identity
  rebellion-core/            # lifecycle, services, DB, migrations, diagnostics, PAPI bridge
  rebellion-items/           # rarity, item identity/lore, talisman tiers
  rebellion-teams/            # teams, diplomacy, chat, friendly-fire policy
  rebellion-economy/          # internal ledger/provider and optional adapters, if needed
  rebellion-wards/            # no-claim watch wards and proximity warnings
  rebellion-rpg/              # rare souls, quests, rituals, tracking unlocks
  rebellion-artifacts/        # three unique dimension artifacts and Rebellion event
  rebellion-dragons/          # finite lineage ledger, eggs, pet dragon lifecycle and control
  rebellion-alchemy/          # brewing progression, potion bags and optional automation
  rebellion-prison/           # death, prison, fees, escape, rehab/health restoration
  rebellion-cosmetics/        # purely visual cosmetics/unlocks
  rebellion-voice/            # optional Simple Voice Chat adapter, if approved by research
  rebellion-bluemap/          # optional BlueMap adapter if useful
  docs/
  .github/workflows/
  scripts/                    # bounded maintenance/test scripts only
```

Do not create empty modules just to mimic this diagram. The actual modules must have clear dependency edges and build output. Use the diagram as a roadmap, not as permission to create dummy JARs.

### Module loading and contracts

- Prefer the stable Bukkit `plugin.yml` loader unless official 26.3 documentation demonstrates that a Paper-specific plugin descriptor is stable and better for this product. Paper's separate `paper-plugin.yml` system has been documented as experimental; do not adopt it without recording the trade-off and proving load order/classloading behavior.
- Provide a small installable `RebellionAPI` JAR when that is the cleanest way to give every plugin the same API class identity. Feature modules depend on it and Core through explicit plugin metadata. The API exports service interfaces and stable DTOs/events only; no game database, command handlers, or feature logic.
- Core owns lifecycle, configuration infrastructure, shared DB connection pools, migrations, service registry/bootstrap, diagnostics, placeholder registration, scheduler helpers, and module health states. It does not secretly own every gameplay feature.
- Feature plugins access capabilities through shared interfaces and Bukkit's ServicesManager or another documented one-way registration contract. Avoid direct imports of another feature module's implementation classes. No cyclic module dependencies.
- Startup order is explicit. If Core/API or a required service fails, dependent modules should not half-enable. Print the exact disabled module and actionable reason. Optional integrations must be isolated behind capability detection and `softdepend`/adapter boundaries.
- Each module registers commands/listeners/tasks only after configuration and required services validate. Shutdown cancels tasks, flushes bounded persistence queues, deregisters services/placeholders, and closes resources. No static global plugin singleton that keeps stale Player/World objects alive.
- Runtime module reload is not the default development mechanism. Support safe config reload where possible; if a feature cannot be safely unloaded/reinitialized with its current state, make it restart-only and explain why. Never promise `/reload` compatibility if it is not tested.

### API design rules

- Public services are small and contract-focused. Use UUIDs and immutable DTOs instead of retaining `Player`, `World`, or live entity objects in caches or database models.
- Add explicit result types or documented exceptions for unavailable services, invalid state, persistence failure, and permission failure. Don't use `null` to encode half a dozen failure cases.
- Keep Paper event handling thin; delegate validation and state transitions to testable services. Schedule entity/world operations on the correct Paper scheduler; never call Bukkit world/player APIs from arbitrary database threads.
- Do not use reflection as a generic dependency injection framework. Do not add an event bus abstraction unless cross-module event contracts genuinely require it.

## 7. Database, transactions, migrations, and caches

### Backends

- SQLite is the default and must work with zero external database setup. Verify the current Paper 26.3 JDBC driver situation rather than assuming an older driver setup. If shipping a driver is necessary, shade/relocate it correctly and document licensing.
- Support MySQL/MariaDB via configured JDBC URL, credentials, TLS options, pool sizing, connection timeout, validation, and sensible retry handling. Keep credentials out of logs and source control. If PostgreSQL is added, implement/test it explicitly; do not advertise it because a JDBC URL happens to parse.
- Use a supported pool (e.g. HikariCP for MySQL/MariaDB), pin a verified version, and shade/relocate libraries that must be bundled. Do not depend on incidental server classpath libraries unless the official docs explicitly guarantee them.

### Rules

- All database access is asynchronous and bounded; no query, migration, pool initialization, or disk-heavy serialization in an event handler on the main thread. Perform schema migrations during controlled startup before enabling dependent feature listeners.
- Use prepared statements, typed DAO/repository methods, transactions where a state change spans rows, and indices/unique constraints for UUIDs, team membership, unique artifacts, dragon lineage, ritual claims, transactions, and event IDs. No SQL string concatenation with player-provided inputs.
- Include a schema migration registry with monotonic migration versions, checksums if practical, transaction/backup strategy, and upgrade tests. No destructive migration without an explicit migration/backup note. A failed migration must prevent dependent modules from enabling and leave the database recoverable.
- Centralize connection pools in Core; modules request repositories/services instead of creating separate pools. Keep migrations for core-owned tables and each module's tables clearly namespaced and versioned.
- Use bounded caches for hot reads. Cache invalidation rules must be documented. Do not cache mutable player objects. Do not use `Player` objects as map keys; UUID keys are mandatory for persistent/cache state.
- The async persistence queue needs a bounded queue, backpressure/rejection behavior, metrics, and shutdown flush. No infinite retries or unbounded memory growth. Critical reward/ownership transitions must reserve state, commit before issuing an irreversible item/teleport where practical, and reconcile pending operations after a crash.
- Define duplicate-event idempotency keys for death processing, dragon eggs, artifact state transitions, event rewards, purchases, prison payments, rituals, task completion, and cosmetic entitlement changes.
- On DB outage, fail closed for unique asset creation and currency charges. Preserve player safety, issue clear messages, log an incident ID, and provide retry/reconciliation. Do not silently mint/consume valuables when persistence is unavailable.

## 8. Configuration and messages

Every module's defaults must be bundled as resources and copied to its own data directory without destroying local edits. Provide comments that explain units and dangerous settings. Use strict validation and actionable errors at startup.

Common requirements:
- `config.yml`: feature toggles, limits, cooldowns, costs, dimensions/world names, scheduler periods, dependency behavior, and other runtime options.
- `messages.yml`: all player-facing feedback, errors, warnings, usage text, state transitions, and configurable formatting. Use Adventure/MiniMessage or the officially selected component format consistently, and document formatting placeholders. Escape user text safely.
- `menus/`: separate menu file per menu, using DeluxeMenus-shaped structure.
- `items.yml` / `rarities.yml` / `recipes.yml` / `encounters.yml` / `abilities.yml` / `rituals.yml` as appropriate to the module; split files when their concepts deserve independent editing.
- `database.yml` belongs to Core and owns backend/connection settings; secret credentials should be externalized or environment-variable configurable. Do not duplicate database passwords into every module.
- `schema-version` and config version migration support. Older config files should gain documented safe defaults without wiping unknown admin keys. Report invalid entries and whether the module disabled or retained a safe default.
- Validate numbers (finite, non-negative, range-bounded), enum strings, materials, effect names, world references, YAML types, key uniqueness, duplicate slots, unsupported placeholders, action names, required files, and path traversal attempts.
- Reload must parse and validate a candidate configuration fully before replacing the active one. On failure, retain the previous valid configuration and explain the errors; do not leave the module half-reloaded.
- Admin-sensitive configuration changes should be reflected in diagnostics without exposing passwords/tokens.

## 9. DeluxeMenus-shaped menu format

Read the actual DeluxeMenus source and wiki before building menus. Use the `gui_menus` registry pattern for file-backed menus and put each menu in an individual YAML file. Example layout:

```yaml
# plugins/RebellionItems/config.yml
# The layout deliberately follows DeluxeMenus' external-menu registry pattern.
gui_menus:
  item_codex:
    file: item_codex.yml
  talisman_guide:
    file: talisman_guide.yml
```

Each file should use documented DeluxeMenus field names such as `menu_title`, `open_command`, `size`, `open_requirement`, `open_commands`, `items`, item identifiers, `material`, `slot`/`slots` where the actual schema supports them, `amount`, `display_name`, `lore`, `view_requirement`, `left_click_requirement`, `right_click_requirement`, `left_click_commands`, `right_click_commands`, and supported item flags/update options. Check exact schema/version before declaring a field supported. Each item key must be unique and every slot within the inventory bounds.

Implement and document a deliberate compatibility subset instead of a pretend full clone. All menu actions should go through an explicit action registry. At minimum, support the documented actions required by the project and provide test coverage, such as close, message, sound, player command, and console command if accepted after a security review. Unknown action tags or requirement types should produce a precise config diagnostic; they must never crash the server or silently succeed. Requirement evaluation must precede click actions. Cancel/handle clicks correctly so players cannot take decorative menu items. Prevent shift-click, number-key swap, double-click collection, drag, cursor, off-hand, and creative-inventory loopholes. Use server-side permission/economy/item checks at the final action; menu visibility alone is not authorization.

If full DeluxeMenus behavior would turn into an oversized and risky project, implement only the researched subset needed by RebellionSMP and list missing features honestly in `docs/DELUXEMENUS_COMPATIBILITY.md`. Do not embed the actual DeluxeMenus implementation as a dependency without checking its current licensing, intended use, and whether simply recommending the real plugin is a better choice.

## 10. PlaceholderAPI contract

When PlaceholderAPI exists, provide a `RebellionExpansion` with stable identifier `rebellion`, correct author/version metadata, safe registration/unregistration, and a placeholder registry contributed by modules. Use official PlaceholderAPI developer guidance.

Minimum proposed placeholders (implement when the owning module exists):
- `%rebellion_team_name%`, `%rebellion_team_size%`, `%rebellion_team_role%`
- `%rebellion_dragon_owned%`, `%rebellion_dragon_id%`, `%rebellion_dragon_health%`, `%rebellion_dragon_count%`, `%rebellion_dragon_cap%`
- `%rebellion_artifact_overworld_holder%`, `%rebellion_artifact_nether_holder%`, `%rebellion_artifact_end_holder%`, `%rebellion_artifact_count_owned%`
- `%rebellion_prison_status%`, `%rebellion_prison_fee%`, `%rebellion_death_score%`, `%rebellion_health_scars%`
- `%rebellion_brewing_level%`, `%rebellion_ritual_<key>_discovered%`, `%rebellion_nearest_ward_distance%`
- `%rebellion_rarity_<material-or-item-id>%`, `%rebellion_cosmetic_<id>_level%`

Define exact naming, return values for offline/null context, case rules, escaping, cache durations, and privacy rules. Do not make placeholders query SQL per request. Unknown names return null/unchanged according to PlaceholderAPI's actual contract. Never expose exact player tracking data, secret artifact origin coordinates, DB errors, or staff-only state through public placeholders.

## 11. Permissions, command design, safety, and abuse cases

Create a `docs/COMMANDS_AND_PERMISSIONS.md` truthfully listing every command, subcommand, permission node, default permission policy, tab-completion rule, and required module. Use namespaced permission nodes. Deny administrative commands to ordinary players; do not give powerful commands to all users by default.

For every mutating command/event, test or reason about:
- permission denial and console execution;
- offline/missing/ambiguous player names, UUIDs, and renamed accounts;
- repeated/spammed command, duplicate click, race/reentrancy, event fired twice;
- disconnect/reconnect during transaction;
- cross-world, End/nether dimension, unloaded chunk, death/respawn, teleport, entity despawn, server restart;
- malformed YAML, absent world, missing optional plugin, DB outage, stale cache, corrupt custom item PDC;
- permission changes during an operation;
- entity/projectile/tnt/area effect attribution;
- integer overflow, invalid negative values, non-finite numbers, and zero/negative cooldowns;
- staff recovery/admin action audit.

Never use user-supplied values to construct raw SQL, file paths, reflection class names, or arbitrary console commands. Normalize/validate IDs and protect module data paths from traversal. Treat menu YAML and config files as trusted admin configuration but validate them anyway.

## 12. Diagnostics, “crash handling,” and recovery

Implement honest fault handling, not a fake promise to stop all crashes.

- Use one structured logging/diagnostics service with module name, event/operation ID, player UUID where appropriate, exception type, and sanitized context. Never log raw passwords, JDBC URLs with credentials, or secret tokens.
- A module that fails configuration validation or initialization should remain disabled and mark its health state; other independent modules may continue if their dependency graph permits. Required dependency failure must prevent dependent features from enabling.
- Catch recoverable exceptions at clear plugin boundaries, not every line. Do not catch and ignore `Throwable`, `OutOfMemoryError`, `ThreadDeath`, or other fatal JVM conditions. Do not override Paper's global exception handlers.
- Write concise diagnostic reports under the owning module/Core data folder with rotation/size limits and a unique incident ID. Include plugin versions, Paper version, Java version, active module states, config validation summary, migration version, and stack trace; redact secrets and player data that is not required.
- Add admin commands such as `/rebellion status`, `/rebellion diagnose`, and `/rebellion modules`; optionally export a sanitized diagnostic bundle. Do not run arbitrary shell commands or send reports over the network by default.
- During shutdown: stop accepting new jobs, cancel module tasks, reconcile/flush bounded critical writes, save safe persistent state, deregister PAPI/service integrations, close connection pools, release files, and log anything that did not flush.
- Build recovery playbooks for database backup/restore, unique dragon/artifact/item reconciliation, interrupted Rebellion events, pending prison/health state, and config rollback. Do not provide hidden “reset all players” commands.

## 13. Performance and thread/scheduler rules

- Paper 26.3 is the target. Do not claim Folia compatibility unless it is separately researched and tested. Avoid architecture that would make future safe scheduler adaptation impossible, but do not add unused Folia abstraction layers just for appearances.
- Player movement listeners must be cheap. Ward checks should use block/chunk change throttling, a spatial index, and bounded candidate sets. No expensive world scans.
- Every repeating task must have a documented interval, lifecycle owner, and cancellation path. Prefer one module service task over one scheduler task per player when it is cheaper and remains maintainable.
- Database calls, network calls, map rendering, archive extraction, heavy parsing, file compression, and diagnostic bundle creation must not block the server thread.
- World/player/entity operations must run on a safe Paper scheduler. Do not call Bukkit APIs on arbitrary database threads. Marshal results back through the supported scheduler and confirm the player/entity/world still exists before acting.
- Use bounded task queues, configurable limits, cache TTLs, and load shedding. Avoid per-player/per-entity operating-system threads. Use UUIDs instead of retaining live Player/Entity references in long-lived services.
- Rate-limit dragon fireballs, ritual attempts, GUI updates, tracking checks, chat messages, cosmetic particles, and encounter spawns. Define hard upper bounds in config validation.
- Profile realistic workloads on a test server (including many players, ward anchors, items, and entities) before claiming performance wins. Use Paper-supported profiling guidance (e.g., spark when installed) and record actual findings instead of invented TPS estimates.

## 14. Test strategy and release gates

Use JUnit 5 or a currently compatible equivalent, plus maintained mocking/integration tools only after checking support for Paper 26.3 and Java 25. Do not pin an old test framework merely because an online tutorial uses it. Prefer pure unit tests for domain services/state transitions, DB integration tests for repositories/migrations, then Paper 26.3 integration tests for events, world/entity behavior, commands, and plugin startup.

Minimum test layers:
1. **Build/quality:** clean Gradle build, formatting, static analysis, no compilation warnings that indicate real compatibility problems, no duplicate classes across artifacts, no accidental dependency on server implementation classes in shared API.
2. **API/metadata:** all JARs have correct names, versions, main class, API version, dependency metadata, commands and permissions; each artifact loads on a clean Paper 26.3 test server in the intended order.
3. **Config:** default config boot, invalid YAML, missing optional integrations, migration from previous versions, failed reload preserves previous config, invalid materials/effects/slots/negative ranges.
4. **Database:** SQLite and MySQL/MariaDB integration as claimed, migrations from empty schema and prior version, concurrent team/dragon/artifact actions, outage/recovery, transaction rollback, uniqueness/idempotency.
5. **Items:** stack preservation, lore de-duplication, rarity fallback for all current materials, PDC identity, movement/crafting/containers, custom item collision, serialization/restart, duplicate/forged unique IDs.
6. **Teams:** membership races, invite acceptance after expiry, team deletion, all relevant friendly-fire sources, chat routing, offline leader changes.
7. **Wards/rituals:** calibrated distance and unit conversion, cross-world behavior, no claim/protection side effects, alert cooldowns, detection countermeasures, tracking cost consumption, duplicate reward prevention.
8. **Artifacts:** unique issuance, all three held by one player, repeated inventory events, simultaneous transfer, event interruption/restart, return to protected origin, catastrophic action costs/spends once, permanent health scars.
9. **Dragons:** cap never exceeds configured maximum, 20 lineage default, 21st eligibility denied, gateway boss interaction, egg hatching/lineage ID uniqueness, owner death + direct dragon kill race, safe egg delivery, no breeding, 30-per-minute cap, no block damage, pearl heal idempotency, mount/flight/chunk unload/restart.
10. **Alchemy/prison/economy:** trigger rules, potion bag inventory edge cases, no double consumption; death processed once, prison teleport fallback, escape restrictions, jail build refuses destructive overwrite, fee overflow/rounding, double charge prevention, health restoration once.
11. **Cosmetics:** cosmetic effect cannot cause damage or modify attributes; proc probabilities/counting are tested; particles bounded; missing entitlement is safe.
12. **Compatibility/ops:** no hard dependency on PlaceholderAPI, BlueMap, Dynmap, Vault, or voice chat unless that exact module declares it. Installation/upgrade/backup docs match actual build outputs.

Tests must exercise actual behavior. A test that only asserts a mock was called does not prove entity movement, item delivery, or plugin metadata works. Do not disable tests to get green. If a test cannot run in this environment, say so in the worklog and provide the exact command/reason; never claim it passed.

## 15. Repository ledgers and documentation requirements

Create and maintain these files as real engineering instruments, not decorative reports:

- `AGENTS.md` — concise persistent instructions for future Codex sessions: architecture rules, build commands, branch policy, source-of-truth docs, code style, testing, and “read before editing.”
- `README.md` — what RebellionSMP is, supported server/runtime, build/install instructions, module list, key features, current status, and links. Keep it factual and user-facing; no artificial AI marketing copy.
- `docs/MASTER_LEDGER.md` — feature inventory/status table: planned, researched, implemented, tested, known limitations, owning module, dependencies, data tables, config files, commands, and relevant issues/PRs.
- `docs/REFERENCE_LEDGER.md` — source URL, license, revision/tag/date checked, relevant path/lines, question answered, conclusion, and whether code reuse is permitted (default no reuse until checked).
- `docs/WORKLOG.md` — chronological work entries with date, branch/PR/commit, concrete files/features, tests actually run and results, open blockers, and next atomic task. Do not duplicate entire PR descriptions.
- `docs/ARCHITECTURE.md` — actual module dependency graph, lifecycle, API contracts, scheduler/thread boundaries, data flow, failure isolation, deployment artifacts.
- `docs/DATA_MODEL.md` — tables/entities, primary keys, uniqueness rules, indexes, relationships, migrations, retention, backup and recovery.
- `docs/STATE_MACHINES.md` — state diagrams/tables for dragon lineage, eggs, artifacts, Rebellion event, prison, rituals, cosmetics entitlements, and pending transactions.
- `docs/COMPATIBILITY_MATRIX.md` — Minecraft/Paper/Java versions and verified optional plugin integration versions. Mark tested vs inferred.
- `docs/CONFIGURATION.md` — config files per module, units/defaults/ranges/reload safety and secrets policy.
- `docs/COMMANDS_AND_PERMISSIONS.md` — command reference with exact permission nodes and default access.
- `docs/DELUXEMENUS_COMPATIBILITY.md` — supported YAML fields/action/requirement types and unsupported features.
- `docs/SECURITY.md` — threat model, permissions, item identity, data validation, public map/privacy, command/inventory abuse, secrets.
- `docs/TEST_PLAN.md` — test matrix, test environment setup, exact commands, coverage by invariant, manual Paper test cases.
- `docs/OPERATIONS.md` — install order, Earth world staging/import, database configuration, backup/restore, upgrades, diagnostics and safe shutdown.
- `docs/RECOVERY.md` — interrupted operations and admin reconciliation procedures.
- `docs/LICENSES.md` — project license decision, third-party licenses and shaded/linked dependencies, map asset provenance.
- `docs/decisions/ADR-0001-...md` — short Architecture Decision Records for high-impact choices.

Only add further ledgers if they solve a concrete tracking problem. Keep status truthful. A feature is not “done” because classes exist; mark it implemented only after acceptance tests pass. Distinguish `planned`, `researched`, `implemented`, `tested`, `verified-on-Paper-26.3`, and `released`.

## 16. Git, author identity, feature branches, PRs, and commits

1. Inspect `git status`, remotes, branch list, current commit, `.gitignore`, Git config, and actual repository tree before editing. Preserve local work; do not reset/clean over unknown user changes.
2. Set `git config user.name "Abasing"` locally for this repo if needed. Keep the verified existing email. Do not change global identity. If identity/email is absent, inspect authenticated GitHub/CLI configuration and use a verified account email; do not invent one. Never rewrite previous commits to change author names.
3. If `develop` exists, update/check it without destroying local commits. If it does not, create it safely from the current `main` commit. If the repository is truly empty, create a local `develop` branch and make the first commit there. Never push a feature commit to `main`.
4. Use focused feature branches off `develop`, e.g. `feature/core-bootstrap`, `feature/item-rarity`, `feature/team-system`, `feature/dragon-lineages`. Avoid one branch spanning every subsystem for weeks.
5. Use clear imperative professional commit subjects (e.g. `Add shared API and core bootstrap`, `Implement rarity registry and item identity`). Conventional Commit prefixes are not mandatory. Do not use generic, boastful, or AI-sounding messages.
6. After each coherent slice, run its relevant test suite and the full build. Inspect the diff, status, generated artifacts, and commit author before committing/pushing.
7. Open PRs into `develop` with: problem/context, behavior added, design trade-offs, module dependencies, config/data changes, tests run with actual results, risks/limitations, migration/rollback notes. Avoid giant PRs and empty ceremonial PRs. If GitHub auth/tooling is unavailable, leave a ready-to-use PR summary and report the limitation; do not claim the PR was opened.
8. Do not auto-merge a PR just because CI is green. Ensure tests are meaningful, diff is reviewed, required docs updated, no unrelated files, and no secret/local world/database files.
9. Never commit generated server worlds, database snapshots, credentials, private resource packs, build caches, machine-specific configuration, downloaded archives, or local-only files. Add appropriate `.gitignore` rules.
10. Use protected/expected-head semantics when updating a remote branch if the available tooling supports it. Do not force push. Do not merge release work into `main` as part of ordinary feature development.

## 17. CI, packaging, and release artifacts

Set up GitHub Actions for pull requests and pushes to `develop` (and read-only validation of `main`):
- checkout, set up the selected Java runtime, configure Gradle caching safely, run formatting/checks/tests, build all intended plugin JARs, and attach build artifacts for inspection;
- validate that every installable JAR contains its own descriptor/default configs and that dependency metadata is correct;
- use reproducible/traceable version information; avoid depending on the local machine's Gradle installation;
- avoid publishing releases or uploading artifacts to external marketplaces from ordinary CI without explicit configuration and secrets;
- keep CI permissions least-privilege; pin third-party actions to maintained secure versions/SHAs when practical;
- fail on tests/config validation errors; do not ignore a failing job or mask exit codes.

Expected packaging layout should be documented and consistent, for example:

```text
build/release/
  RebellionAPI-<version>.jar
  RebellionCore-<version>.jar
  RebellionItems-<version>.jar
  RebellionTeams-<version>.jar
  RebellionWards-<version>.jar
  RebellionRPG-<version>.jar
  RebellionArtifacts-<version>.jar
  RebellionDragons-<version>.jar
  RebellionAlchemy-<version>.jar
  RebellionPrison-<version>.jar
  RebellionEconomy-<version>.jar   # only if implemented as a separate module
  RebellionCosmetics-<version>.jar
  RebellionBlueMap-<version>.jar  # optional adapter
  RebellionVoice-<version>.jar    # optional adapter; client mod requirements documented
```

Only include modules once they contain tested functionality. The module/JAR names can be adjusted in ADRs, but separate feature JARs are mandatory.

## 18. Execution roadmap — proceed in dependency order

Maintain a buildable state at every milestone. Do not implement all modules in one monolithic patch. Each phase should be a series of focused commits/PRs, with the ledger updated after actual evidence.

### Phase 0 — Inspect, research, and plan
- Inspect actual repository and Git state, create/use `develop` safely.
- Verify Paper 26.3 + Java requirements and the appropriate API artifact/version.
- Inspect the exact source and YAML examples cited above; inspect map metadata and map-renderer versions.
- Create the ledgers and architecture docs with verified facts and clear unknowns.
- Decide module dependency graph, database strategy, plugin descriptor strategy, API runtime identity, versioning, and test harness in ADRs.
- Do not yet make speculative gameplay abstractions. Build a small compatibility spike if required to settle the dragon implementation approach.

### Phase 1 — Build foundation and module loader contracts
- Gradle multi-project build, wrapper, version catalog/properties, formatting/static checks, CI, `.gitignore`, license/third-party ledger, build metadata.
- `RebellionAPI` and `RebellionCore` boot successfully on a clean Paper 26.3 test server.
- Core config/message loaders validate defaults, copy files without destroying edits, reload atomically, and report clear errors.
- Implement async bounded database service, SQLite support, configured MySQL/MariaDB support, migrations, shutdown, health status, and persistence integration tests.
- Implement structured logging/diagnostics, module capability/service registry, placeholder integration gate, command/permission pattern, and safe plugin shutdown.
- Build and install only these JARs on a clean test server before progressing.

### Phase 2 — First complete gameplay vertical slice: item identity and rarity
- Implement stable PDC item identity, rarity registry/default mappings, item lore service, talisman tiers, config validation, and DeluxeMenus-shaped item codex/talisman guide.
- Implement a useful first release of the documented menu subset and integration tests for clicking/dragging/shift-click bypasses.
- Implement admin inspection/recovery for tagged items, avoiding unrestricted mint commands.
- Verify metadata/stack preservation and lore behavior with current Paper 26.3.
- This is the first phase that must deliver a usable end-to-end player feature, not just architecture.

### Phase 3 — Teams, chat, and friendly-fire rules
- Add atomic team memberships/invites/ranks/diplomacy, team/private chat, vanilla text proximity chat, and friendly-fire source attribution.
- Add tests for projectile/explosion/potion/pet damage and race conditions.
- Add PAPI placeholders where applicable.

### Phase 4 — Wards and world-coordinate intel
- Build no-claim wards, calibrated distance service, outsider alerts, spam protection, countermeasures, and optional map marker interface.
- Keep kilometer display disabled until an admin-calibrated scale is present.
- Measure lookup costs with realistic player/ward counts; document that wards do not protect land.

### Phase 5 — Dragon lineage proof, then full dragon module
- First deliver the minimal 26.3 controller spike and test report.
- Implement the 20-lineage global registry, gateway issuance flow, unique egg issuance/hatching, mount/flight, attacks, pearl healing, call/recall, safe death animation, owner death/dragon kill transfer, admin audit/recovery, and crash reconciliation.
- Do not continue to production dragon controls until passenger/flight/death behavior is proved on 26.3.

### Phase 6 — Dimension artifacts and Rebellion event
- Implement three unique artifact state machines, origin/return protection, powers, triple-artifact trigger, catastrophe state machine, health scars, spent-artifact return, and restart recovery.
- Add adversarial tests for copied PDC, repeated events, dropped/void items, simultaneous transfers, and restart during the catastrophe.

### Phase 7 — RPG souls, rituals, tracking, and talisman progression
- Implement rare-soul encounters/unlocks, ritual recipes, targeted tracking/counter-rituals, progression state, and the related menu/lore flows.
- Ensure unique reward claims and ingredient consumption are transactional/idempotent.

### Phase 8 — Brewing progression and potion automation
- Implement Power/Efficiency branches, potion bags, configurable recipes, auto-consumption rules and cooldowns.
- Add tests for inventory exploit paths, death, disconnect, potion conflicts, and repeated triggers.

### Phase 9 — Prison, death scores, economy interface, rehabilitation
- Implement prison structure setup/generation, death-to-prison state, NPC guards, escape objectives, external rescue, payment formula, health scars, rehabilitation/recovery, missing-world fallback, and staff recovery commands.
- Test the prison/world on a copy and prove it cannot overwrite existing builds during setup.

### Phase 10 — Cosmetic-only rewards and optional integrations
- Implement cosmetic unlock/entitlement records, visual-only cosmetic effects, probability levels and performance bounds.
- Add optional BlueMap marker adapter after integration checks. Add voice adapter only with tested Simple Voice Chat API and documented client-mod requirement.

### Phase 11 — Full hardening and release candidate
- Run all automated suites and the clean Paper 26.3 integration suite.
- Test install from scratch, upgrade from previous schemas/configs, restart, recovery, missing optional plugins, DB outage, bad YAML, and mixed module availability.
- Complete docs/config samples/permissions/placeholders/dependency notices. Check logs for accidental secrets and source tree for generated assets.
- Produce a truthful release candidate changelog and supported-version matrix. Keep it on `develop` until reviewed and explicitly released to `main`.

If time/context is limited, finish and verify the current phase before adding empty skeletons for future phases. Record the next small task in `docs/WORKLOG.md` and stop at a buildable, honest checkpoint rather than leave the tree half-migrated.

## 19. Done means demonstrated behavior

A feature is done only when its configuration, player feedback, admin operation, persistence, migration, permission model, tests, diagnostics, and documentation exist to the extent required by that feature. For critical rare assets, done also means crash-safe uniqueness and an audited recovery path.

Before reporting a phase complete, verify:
- clean checkout/build from the checked-in wrapper;
- relevant tests actually ran and results are accurate;
- new feature modules have separate installable JARs and correct dependency metadata;
- no placeholder class or no-op command is presented as implemented functionality;
- every public-facing message/config/menu follows module conventions;
- DB schema/migration/recovery paths are covered;
- no main-thread SQL, unbounded scans, duplicated API types, missing task cancellation, or unbounded entity/particle creation;
- no accidental land claim/land-protection feature was introduced;
- dragon cap is server-wide and fixed at 20 by default, pet transfers do not mint lineages, eggs cannot be bred or permanently lost, and fireballs do not break blocks;
- cosmetics have no gameplay effect;
- optional integrations fail safely when absent/incompatible;
- `docs/MASTER_LEDGER.md`, `docs/WORKLOG.md`, and relevant references/ADRs reflect what actually happened;
- `git diff --check`, formatting/static checks, full build, tests, git status, and author identity have been checked before commit.

When delivering a work summary, use this format:

1. **Implemented:** concrete behavior and owning module(s).
2. **Evidence:** exact tests/commands run and pass/fail results; Paper version used if integration-tested.
3. **Artifacts:** JAR paths produced and installation order.
4. **Persistence/config:** schema migration/config changes and recovery implications.
5. **PR/commit:** branch, commit SHA, author, PR URL if truly created.
6. **Limitations:** only real limitations or unresolved blockers, not speculative filler.
7. **Next task:** one concrete next vertical slice.

Do not claim a feature works on Paper 26.3 unless it was verified against Paper 26.3. Do not claim a PR was opened, test passed, server loaded, or repository push succeeded unless the tool output confirms it.

---

## Final instruction

Start now by inspecting the actual repository state and Git identity, then perform **Phase 0 and Phase 1**, and continue into the first complete rarity/item vertical slice if the foundational build remains healthy. Research before implementation, preserve the `main` branch, commit as `Abasing` using the real configured account email, update the ledgers with verified details, and create focused PRs into `develop` when the connected Git tooling is available. Optimize for a coherent, tested, recoverable game system—not maximum lines of code.
