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

## Messages contract (all modules, ADR-0012)

Every module — Core through the newest feature module — renders all
player-facing feedback through its own extracted `messages.yml` via the
shared `MessageCatalog` (rebellion-api). The contract is uniform:

- **First boot** extracts the bundled `messages.yml` into the module data
  folder; admin edits are never overwritten afterwards.
- **Merge semantics at boot and module reload:** admin values win; missing
  keys fall back to bundled defaults (reported as warnings); a user template
  that fails strict closed-tag validation falls back to the bundled default
  for that key and is reported with the offending key. An unreadable file
  fails the enable (fail closed, actionable message).
- **Core's `/rebellion reload` keeps its stricter parse semantics:** any
  invalid template rejects the whole reload; the previous snapshot stays
  (unchanged, tested contract).
- **Outcome keys are deterministic:** `team.<action>.<outcome>` /
  `ward.<action>.<outcome>` — enum name lowercased, underscores as dashes
  (e.g. `team.kick.cant-target-leader`, `ward.create.decoys-disabled`).
  Texts identical across actions share a top-level key (`not-in-team`);
  infrastructure failures share `common.unavailable`.
- **Prefix convention:** one-shot feedback (usage, errors, success) renders
  with the `prefix` key; data lines inside listings render unprefixed — the
  same convention as Core's `/rebellion` surface.
- **Placeholders are filled by code** (`<player>`, `<ward>`, `<detail>`,
  `<sender>`…). Values are inserted as literal text/components, so they can
  never inject MiniMessage tags or click events.
- **Literal angle brackets escape only the opening bracket**
  (`/team invite \<player>`): MiniMessage has `\<` but no `\>` escape
  (verified against adventure-text-minimessage 5.2.0).
- **Chat channel formats** (`chat.team-format`, `chat.proximity-format` in
  RebellionTeams) are templates over `<sender>` / `<message>` component
  placeholders.
- **Reload scope:** RebellionItems reloads messages with everything else
  (`/rebellionitems reload`); Teams and Wards load messages at enable only —
  message edits there need a restart (documented, deliberate).

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

### Dragons (RebellionDragons — controller slice, ADR-0009/0014)

```yaml
dragons:
  # Maximum obtainable pet-dragon lineages across the ENTIRE server
  # (all worlds combined). Default 20, hard ceiling 20: the End has at
  # most 20 eligible vanilla gateways, so a larger value is rejected at
  # load time - never silently stretched. Boss victories at the cap
  # follow vanilla rewards but mint nothing.
  max-lineages: 20

  egg:
    # Material of the physical lineage-egg item. The item is stamped with
    # (lineage, egg-instance) PDC identity; live stamps are indestructible
    # (all destruction paths cancelled by the egg-protection listeners).
    material: DRAGON_EGG

  pet:
    hatch-health: 200.0            # vanilla dragon max, measured (ADR-0008 E3/E5)
    hatch-offset-forward: 12       # /dragon hatch spawn spot (blocks)
    hatch-offset-up: 12
    call:
      max-range-blocks: 512        # same-world recall limit (cross-world always allowed)
      cooldown-seconds: 30
      hover-offset-blocks: 8       # park height above the owner (HOVER platform)
    ride:
      enabled: false               # INTERACTIVE GATE: client ride feel unverified
      steer-interval-ticks: 8      # closed-loop podium re-target cadence (E2c)
      look-ahead-blocks: 48
    attack:
      range-blocks: 64             # crosshair raytrace range
      max-per-window: 30           # §5.6 budget; hard-validated ceiling 120
      window-seconds: 60
      min-interval-ms: 2000        # anti-burst guard
    pearl-heal:
      amount: 25.0                 # E5 measured heal(25) == +25
      cooldown-seconds: 10         # cooldown CAS = retry idempotency
    water-weakness:
      damage-multiplier: 1.25      # bounded 1.0..4.0 at load: a penalty, never an instant death

  boss-victory:
    observe-enabled: true          # ADR-0014 scan-diff minting
    scan:
      radius-min-blocks: 48        # annulus around the exit portal
      radius-max-blocks: 160       # (ring geometry: ADR-0008 E4b interactive gate)
      y-min: 0
      y-max: 256
      poll-interval-ticks: 40
      window-seconds: 90
```

Load rules (validated by `DragonsConfigTest`, which also pins the shipped
file and the `DragonsConfig.defaults()` factory to it):

- `dragons.max-lineages` must be an integer in `1..20`. `0`, negatives and
  anything `> 20` fail config load with an actionable message naming the
  ceiling — the cap "cannot silently exceed the End gateway/lineage
  policy" (master prompt §5.6). `LineageService` re-clamps at runtime as
  defense in depth.
- Every controller number is range-checked with the bound stated in the
  issue text: fireball budget `1..120` per window, water multiplier
  `1.0..4.0` (below 1.0 would be a water bonus — equally rejected),
  scan radii ordered and `<= 512`, scan window `10..600 s`, pearl amount
  `0 < a <= 1024`, steer interval `1..200` ticks. Nothing stretches
  silently.
- `ride.enabled` defaults to **false** and flipping it is restart-only
  (the steer task registers at boot): the client-side ride feel is an
  ADR-0008 interactive gate. The control loop itself is fully implemented.
- The persistence sweep cadence is fixed at 600 ticks (30 s) — health and
  position snapshots for loaded pets plus orphan reconciliation — and a
  final flush runs at shutdown while Core's database is still up
  (`DragonsPlugin.onDisable`).
- Retirement never frees slots and no config value can revive a retired
  lineage: retired rows keep consuming their slot and gateway anchor.

### Artifacts (RebellionArtifacts — Phase 6 groundwork, ADR-0013)

```yaml
artifacts:
  origins:
    overworld:            # theme: life / dominion
      world: world        # protected origin realm (calibrate on map import)
      x: 0
      y: 64
      z: 0
    nether:               # theme: fire / destruction / war
      world: world_nether
      x: 0
      y: 64
      z: 0
    end:                  # theme: void / knowledge / dimensional influence
      world: world_the_end
      x: 0
      y: 64
      z: 0

  return:
    delay-seconds: 3600   # LOST/SPENT -> RETURNING eligibility (0 = immediate)

  rebellion-event:
    enabled: true         # master switch for the occurrence trigger
    trigger-scope: HOLDER # only HOLDER is implemented; TEAM is a loud error
```

Load rules (validated by `ArtifactsConfigTest`, which also pins the shipped
file and the `ArtifactsConfig.defaults()` factory to it):

- `origins.<dimension>.world` must be `1..64` characters; `x`/`z` within
  the world border (`|coord| <= 30 000 000`); `y` in `-64..320`. The
  anchors are **config-owned world-setup data**: the service re-syncs the
  registry rows to these values on every load and touches no gameplay
  state, so calibrating the realms when the map is imported is safe. The
  shipped defaults (conventional world names, a spawn-ish anchor) are
  honest placeholders, not measurements.
- `return.delay-seconds` must be `0..2 592 000` (30 days). `0` makes a
  lost artifact return-eligible immediately. The sweep additionally
  refuses to run while a Rebellion occurrence is active (the §5.5 "event
  check").
- `rebellion-event.enabled: false` disables the trigger entirely (typed
  `FEATURE_DISABLED` refusal) until an operator re-enables it.
- `rebellion-event.trigger-scope` accepts **HOLDER only**: one player
  controlling all three unique artifacts. `TEAM` fails config load loudly
  as unimplemented — master prompt §5.5 demands a team-wide trigger be a
  separate explicit configuration option, never a silent stretch.
- Power cooldowns, catastrophe effects, the optional world-wide purge
  action and encounter tuning are deliberately absent until the
  controller phase exists (the same discipline DragonsConfig held before
  ADR-0008 landed).
