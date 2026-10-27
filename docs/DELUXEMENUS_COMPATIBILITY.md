# DeluxeMenus compatibility subset

**Status: first release implemented in `rebellion-items` (Phase 2), unit
tested without a live server; live-inventory interaction is verified against
the manual checklist in TEST_PLAN.** The schema matches the current
DeluxeMenus source (MIT) and its published wiki (inspected 2026-10-27; see
REFERENCE_LEDGER). Gaps are listed as unsupported — no claims of full
compatibility.

## Deliberate deviations (documented, first release)

- Menus are opened through one fixed command `/rebellionmenu <menu-id>` with
  per-menu permission `rebellion.items.menu.<menu-id>`; per-menu
  `open_command` values are parsed and validated but are not individually
  registered as top-level server commands yet (follow-up issue, tracked in
  the ledger).
- `[sound]` actions use Minecraft sound registry keys (e.g.
  `ui.button.click`), not the deprecated legacy `Sound` constants.
- `[console]` actions require BOTH `menus.allow-console-actions: true` and
  the clicking player holding `rebellion.items.admin.menuconsole`.

## Registry pattern (supported)

```yaml
# plugins/<Module>/config.yml
gui_menus:
  item_codex:
    file: item_codex.yml
```

Each menu is an individual YAML file under the module's `menus/` directory;
the module's `config.yml` only holds the `gui_menus` registry. Registry keys
are unique menu identifiers; `file:` is a simple name resolved inside
`menus/` — path traversal (`../`, absolute paths) is rejected.

## Planned supported subset (per menu file)

| Field | Type | Notes |
|---|---|---|
| `menu_title` | string (MiniMessage in our stack) | DeluxeMenus uses color codes; we parse both |
| `size` | int | 9–54, multiple of 9 (validated) |
| `open_command` | string list | optional; our commands namespace (`/<module> …`) |
| `open_requirement` | requirement | evaluated before open |
| `open_commands` | action list | executed after a successful open |
| `items` | section | item buttons, unique keys required |

Per item:

| Field | Notes |
|---|---|
| `material` | Paper `Material` names validated against the registry |
| `slot` / `slots` | within `size` bounds; duplicate slots rejected |
| `amount` | 1–64 |
| `display_name` | MiniMessage |
| `lore` | string list, MiniMessage |
| `view_requirement` | hides/disables the button when unmet |
| `left_click_requirement` / `right_click_requirement` | requirement before actions |
| `left_click_commands` / `right_click_commands` | action lists |
| item flags (e.g. `hide_attributes`) | documented subset |

## Requirements (planned supported types)

`has permission`. Economy/level/xp/other plugin requirements are **not
supported** until an owning module exists; unknown requirement types produce
a precise config diagnostic (never a crash, never silent success).

## Actions (planned supported types, via an explicit action registry)

`[close]`, `[message] text`, `[sound] name`, `[player] command`,
`[console] command` (console actions require a security-reviewed permission
and are validated against a whitelist; player-supplied text is never
interpolated into console actions).

## Click safety (enforced by the engine, not by trust in the YAML)

- Requirement evaluation precedes click actions.
- Inventory click/drag/shift-click/number-key/double-click/offhand/creative
  collection loopholes are cancelled; menu items are never removable.
- Visibility is not authorization: server-side permission checks run again
  at action execution.
- Unknown action tags or requirement types: precise diagnostic at menu load;
  the menu fails validation and is not offered.

## Explicitly unsupported (will not be advertised)

Dynamic/animated items, external placeholder-driven refresh beyond a
documented interval, BungeeCord/Velocity actions, JavaScript logic, economy
requirements (until an economy module exists and is reviewed), and any
`paper-plugin.yml`-specific behavior.
