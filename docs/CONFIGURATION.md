# Configuration

All files live in the owning plugin's data folder (`plugins/<PluginName>/`)
and are created from bundled resources on first boot. Local edits are never
overwritten. Every file carries `schema-version`.

## RebellionCore

### config.yml
| Key | Type | Default | Notes |
|---|---|---|---|
| `schema-version` | int | 1 | future migrations keyed off this |
| `debug` | boolean | false | verbose logging; not for permanent production use |
| `startup-timeout-seconds` | int ≥ 1 | 30 | how long feature modules wait for the DB bootstrap |
| `diagnostics.directory` | string | `diagnostics` | subfolder for reports; path-traversal rejected by loader |
| `diagnostics.max-report-files` | int ≥ 1 | 10 | rotation bound |
| `diagnostics.recent-incident-capacity` | int ≥ 1 | 100 | in-memory incident ring size |

### database.yml
| Key | Type | Default | Notes |
|---|---|---|---|
| `backend` | `sqlite` \| `mysql` | sqlite | unknown values fail startup |
| `sqlite.file` | string | `rebellion.sqlite` | resolved inside the data folder |
| `mysql.host/port/database/username/password` | strings | localhost:3306 | password supports `${ENV}` / `${ENV:fallback}` |
| `mysql.properties` | map | `{}` | verbatim JDBC URL properties |
| `pool.max-pool-size` | int ≥ 1 | 10 | **ignored for SQLite: forced to 1** |
| `pool.min-idle` | int ≥ 1 | 2 | applies to MySQL |
| `pool.connection-timeout-ms` | int ≥ 100 | 5000 | Hikari connection timeout |
| `pool.max-lifetime-ms` | int ≥ 1 | 1800000 | Hikari max lifetime |
| `executor.pool-size` | int ≥ 1 | 4 | bounded persistence workers |
| `executor.queue-capacity` | int ≥ 1 | 2000 | hard backpressure bound; saturation rejects with incident |
| `migrations.strict-checksums` | boolean | true | refuse to boot on edited applied migrations |

### messages.yml
MiniMessage templates. Contract: **every tag explicitly closed**; unclosed
tags are rejected at load with the offending key. Runtime placeholders
(`<version>`, `<incident>`, …) are single identifiers and are exempt from
validation. Missing keys fall back to bundled defaults (reported, non-fatal).
Prefix is the `prefix` key and is prepended by `msg()`.

## RebellionItems

### config.yml
| Key | Type | Default | Notes |
|---|---|---|---|
| `schema-version` | int | 1 | |
| `annotation.mode` | enum | `custom-only` | `custom-only` / `managed-items` / `all-items`; the last is a wide metadata mutation policy and must be a deliberate opt-in |
| `annotation.lore-position` | enum | `bottom` | `top` or `bottom` |
| `annotation.flows.join-normalization` | bool | false | one bounded pass over the joining player's held stacks |
| `annotation.flows.pickup` | bool | false | annotate eligible picked-up stacks |
| `annotation.flows.block-drops` | bool | false | annotate stacks dropped by a broken block |
| `annotation.flows.crafting-output` | bool | false | annotate the crafted result stack |
| `annotation.flows.smithing-output` | bool | false | annotate the prepared smithing result |
| `annotation.flows.container-open` | bool | false | one bounded pass over a container's stacks when opened (mutates container inventories; deliberate opt-in) |
| `menus.allow-console-actions` | bool | false | also requires `rebellion.items.admin.menuconsole` at click time |
| `gui_menus.<id>.file` | string | — | DeluxeMenus-shaped registry; one file per menu under `menus/` |

### rarities.yml
Nine canonical rarity keys (COMMON…GOD) with `display-name`, `color`
(MiniMessage), `weight` (>= 0), `glint`; `materials:` overrides map material
names to rarity keys. Unmapped materials — including ones from future
Minecraft versions — resolve to COMMON by design. Unknown materials/rarities
are precise load issues.

### items.yml
Custom item definitions: `items.<id>` with `material`, `rarity` (default
common), `talisman-tier` (optional: normal/artifact/relic — an axis separate
from rarity), `display-name` (MiniMessage, defaults to the id), `lore`,
`glint`, `unique` (stamps a fresh instance_id per created stack). Ids must
match `[a-z0-9_]{1,64}`; unknown materials/rarities/tiers are precise load
issues. The bundled file ships a three-tier Haste talisman family and a named
banner to demonstrate the contract.

### menus/*.yml
`menu_title` (MiniMessage), `size` (9–54, multiple of 9), `open_command`
(validated identifier; see DELUXEMENUS_COMPATIBILITY for the deviation),
`items` with `material`, `slot`/`slots`, `amount` (1–64), `display_name`,
`lore`, view/left/right requirements (`has permission`) and click action
lists (`[close]`, `[message]`, `[sound]` (registry key), `[player] /cmd`,
`[console] /cmd`).

### messages.yml
Same closed-tag MiniMessage contract as Core.

## Validation behavior (all loaders)

- YAML syntax error → module fails to enable (fail closed, actionable message).
- Aggregated issues: every invalid entry is reported at once with
  `path: explanation`.
- Reload (`/rebellion reload`): candidate parsed and validated fully before
  the active snapshot is swapped; on failure the previous snapshot stays and
  the issues are printed. `database.yml` is validated only — restart required
  to apply (stated in-game).
- Secrets: never logged, never copied into diagnostic reports; env expansion
  keeps credentials out of the file.
- Path traversal: file/directory names containing `..` or absolute escapes are
  rejected.

## Per-module configuration rule (binding for feature modules)

Each feature module ships its own `config.yml` + `messages.yml` (and focused
files like `items.yml`, `rarities.yml`, `menus/*.yml` where that improves
editability). No shared mega-config. An API-only module may omit config if it
genuinely has no settings — RebellionAPI is that exception today.

## RebellionWards

Own `config.yml` under `plugins/RebellionWards/`, validated at load with the
same atomic-swap semantics as Core (invalid file keeps the previous
configuration; every issue is printed).

```yaml
wards:
  anchor-material: LODESTONE   # ward binds to this placed block
  create-reach-blocks: 6
  min-radius: 16.0
  max-radius: 256.0
  max-wards-per-player: 3      # in-transaction cap checks
  max-wards-per-team: 12
  max-wards-total: 2000        # keeps the spatial index + sweeps cheap
  manage-ranks: [leader, officer]
  decoys: { enabled: true }    # labeled alerts; excluded from intel/markers
detection:
  sweep-interval-ticks: 20     # movement events only mark; sweeps process
  dwell-seconds: 0             # warning delay before an enter alert commits
  masking:
    enabled: true
    held-material: AMETHYST_SHARD
alerts:
  channel: team                # team | owner | both (owner fallback applies)
  realert-cooldown-seconds: 300
  notify-leave: true
distance:
  world-units-per-kilometer: {}   # EMPTY = kilometers disabled everywhere
  uncalibrated-unit: blocks       # blocks | meters
map-markers:
  publish-wards: false            # privacy-by-default web-map gate
```

### Ward distance calibration (mandatory before "km" ever appears)

The kilometer display is **disabled until you calibrate it**. This is the
defect class the master prompt calls out: mislabeled arbitrary blocks are
worse than no kilometers. Procedure:

1. Pick two landmarks on your imported Earth map a known real-world
   distance apart (e.g. two cities 100 km apart).
2. Take their world coordinates (F3) and compute the horizontal
   world-coordinate distance between them (square root of dx² + dz²).
3. `world-units-per-kilometer = coordinate-distance / real-kilometers`.
4. Set it per world — e.g. `world: 1000.0` for a 1:1000 overworld map;
   if your nether is 1:8 on that map, its scale is `1000/8 = 125.0`.
5. Reload/restart. `DistanceService` formats calibrated worlds as
   `2.4 km` (one decimal below 10 km, integer above) and leaves every
   other world in blocks/meters.

Rules enforced at load: scales must be finite and inside 1.0..100000.0
(implausible values are rejected with "do not guess"); each world is
independent; `uncalibrated-unit` only chooses the label for uncalibrated
worlds.

Coordinate semantics (documented contract): horizontal plane only (Y
ignored); cross-world has no distance and no detection (the spatial index
is per-world); vanilla worlds do not wrap at borders, so distances are
plain Euclidean — consider border geometry when placing wards near edges.
Changing worlds resets a player's detection state (they left every ward of
the old world; the new world detects them fresh).

### No-protection statement (operator-facing)

Wards never prevent movement, block breaking, container access, damage or
building, and create no claims or protected chunks. Anchor blocks are
ordinary blocks: anyone can break them, and the ward dies with its anchor.
Hiding/defending anchors is gameplay the plugin deliberately does not
 adjudicate. This is enforced structurally (ADR-0007): the ward schema has
no fields that could express protection.

### Dragons (RebellionDragons — persistence layer, ADR-0009)

```yaml
dragons:
  # Maximum obtainable pet-dragon lineages across the ENTIRE server
  # (all worlds combined). Default 20, hard ceiling 20: the End has at
  # most 20 eligible vanilla gateways, so a larger value is rejected at
  # load time - never silently stretched. Boss victories at the cap
  # follow vanilla rewards but mint nothing.
  max-lineages: 20
```

Load rules (validated by `DragonsConfigTest`):

- `dragons.max-lineages` must be an integer in `1..20`. `0`, negatives and
  anything `> 20` fail config load with an actionable message naming the
  ceiling — the cap "cannot silently exceed the End gateway/lineage
  policy" (master prompt §5.6).
- As defense in depth, `LineageService` clamps the effective cap to the
  same ceiling at runtime even if a caller bypasses loader validation
  (proven by `LineageServiceIT.capCannotSilentlyExceedTheHardPolicyCeiling`).
- Retirement never frees slots and no config value can revive a retired
  lineage: retired rows keep consuming their slot and gateway anchor.
- Ride speed, fireball budgets, pearl-heal tuning and water-weakness
  values are intentionally absent until the ADR-0008 live-server proofs
  are recorded; shipping tuned-looking numbers for unproven behavior
  would be guesswork.
