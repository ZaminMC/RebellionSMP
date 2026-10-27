# Commands and permissions

Current command surface (RebellionCore). Feature modules add their own
namespaced commands when they exist; this file is the single source of truth.

## /rebellion

| Subcommand | Permission | Console? | Behavior |
|---|---|---|---|
| `status` | `rebellion.admin.status` | yes | core/API versions, database backend + health, persistence queue metrics (queued/active/completed), applied migration summary, uptime |
| `modules` | `rebellion.admin.modules` | yes | registered modules with state (operational/degraded/failed/pending/disabled) and failure detail |
| `diagnose` | `rebellion.admin.diagnose` | yes | writes a rotated, sanitized report under `plugins/RebellionCore/diagnostics/` and reports its path + incident ID |
| `reload` | `rebellion.admin.reload` | yes | re-validates and atomically swaps config + messages; on any error the previous configuration stays active and issues are printed. Database config requires a restart (stated in output) |

Tab completion: subcommand names for the first argument only.

## Permission nodes

| Node | Default | Scope |
|---|---|---|
| `rebellion.admin` | op | parent of all admin nodes |
| `rebellion.admin.status` | op | `/rebellion status` |
| `rebellion.admin.modules` | op | `/rebellion modules` |
| `rebellion.admin.diagnose` | op | `/rebellion diagnose` |
| `rebellion.admin.reload` | op | `/rebellion reload` |

Rules: admin commands never default to non-op players. The command itself is
guarded by `rebellion.admin` in `plugin.yml` and each subcommand re-checks its
own node, so granting partial access does not leak other subcommands. No
command accepts free-text SQL, file paths, or class names; there is no
arbitrary console passthrough and no "reset all players" utility.

## RebellionItems commands

| Command | Subcommand | Permission | Console? | Behavior |
|---|---|---|---|---|
| `/rebellionitems` | `inspect` | `rebellion.items.admin.inspect` | no | shows the held stack's RebellionSMP identity (schema, rarity, item id, instance, tier) |
| `/rebellionitems` | `annotate` | `rebellion.items.admin.annotate` | no | idempotently applies rarity identity + lore to the held stack; refuses unknown schemas |
| `/rebellionitems` | `give <item-id> [player]` | `rebellion.items.admin.give` | yes (player required) | restricted mint of one DEFINED custom item from items.yml — never arbitrary combinations; unique definitions get a fresh instance id; every give is logged |
| `/rebellionitems` | `reload` | `rebellion.items.admin.reload` | yes | atomic reload of config + rarities + items + menus; on error the previous state stays active and every issue is printed |
| `/rebellionitems` | `status` | `rebellion.items.admin.status` | yes | annotation mode, override count, registered menus |
| `/rebellionmenu <menu-id>` | — | `rebellion.items.menu.<menu-id>` | no | opens a registered menu; per-menu permission is enforced server-side at open and click time (visibility is not authorization) |

Items permission nodes (all default op unless noted):

| Node | Scope |
|---|---|
| `rebellion.items.admin` | parent of the five admin nodes |
| `rebellion.items.admin.menuconsole` | required (with the config flag) for `[console]` menu actions |
| `rebellion.items.menu` | base permission for `/rebellionmenu` (default: true); each menu additionally checks `rebellion.items.menu.<menu-id>` |

There is deliberately no free-form item minting command: the admin annotate
command only applies rarity/identity to a stack the admin already holds.

## PlaceholderAPI placeholders (expansion `rebellion`)

| Placeholder | Value |
|---|---|
| `%rebellion_version%` | RebellionCore version |
| `%rebellion_modules_healthy%` | `operational/total` ratio |
| `%rebellion_database_backend%` | `sqlite`/`mysql`, or `offline` when unhealthy |
| `%rebellion_modules_degraded%` | count of modules not OPERATIONAL |
| `%rebellion_rarity_<MATERIAL>%` | configured rarity key of the material (items module); unmapped/unknown materials resolve to `common` |

Contract: in-memory reads only (no SQL per lookup); unknown parameters return
`null` so PAPI leaves the text untouched; registered only when PlaceholderAPI
is present. Modules contribute parameters by registering a
`PlaceholderContributor` with the ServicesManager; Core's expansion routes
lookups through every contributor at request time (registration order; no
parameter name collisions between modules).

## RebellionTeams commands

| Command | Subcommand | Permission | Console? | Behavior |
|---|---|---|---|---|
| `/team` | `create <name>` | `rebellion.teams.use` | no | creates a team (leader = creator); name must be 3–24 chars of `[A-Za-z0-9_]`, not reserved, globally unique; create cooldown per player |
| `/team` | `invite <player>` | `rebellion.teams.use` + officer rank | no | invites an ONLINE player (UUID comes from the live Player object, never the name); rate limited; invitee queue capped; re-invites refresh the expiry |
| `/team` | `accept [team]` | `rebellion.teams.use` | no | accepts the single pending invite, or a named one when several are pending; refuses when team is full or the invitee joined another team mid-flight |
| `/team` | `decline [team]` | `rebellion.teams.use` | no | declines the pending invite |
| `/team` | `uninvite <player>` | `rebellion.teams.use` + officer rank | no | withdraws the team's open invite for that online player |
| `/team` | `kick <player>` | `rebellion.teams.use` + officer rank | no | removes a member (officers can only remove members; only the leader removes officers; the leader cannot be kicked) |
| `/team` | `leave` | `rebellion.teams.use` | no | leaves the team; leaving as the last member auto-disbands (one audit row) |
| `/team` | `disband` | `rebellion.teams.use` + leader rank | no | disbands: memberships, invites and relations cascade; history survives |
| `/team` | `rename <name>` | `rebellion.teams.use` + leader rank | no | renames (unique names, cooldown) |
| `/team` | `promote <player>` / `demote <player>` | `rebellion.teams.use` + leader rank | no | member ↔ officer |
| `/team` | `transfer <player>` | `rebellion.teams.use` + leader rank | no | leadership moves to a member; the old leader becomes officer |
| `/team` | `friendlyfire <on\|off>` | `rebellion.teams.use` + leader rank | no | team-wide toggle; off = same-team damage is cancelled (see friendly-fire contract) |
| `/team` | `ally\|enemy\|neutral <team-name>` | `rebellion.teams.use` + leader rank | no | symmetric diplomacy (one row per pair); allies gain no privileges |
| `/team` | `info [team]` / `members` / `invites` | `rebellion.teams.use` | no | read-only views |
| `/team` | `history` | `rebellion.teams.history` | no | the team's append-only audit trail (latest 30) |
| `/chat` | `global\|team\|proximity` | `rebellion.teams.chat` | no | channel switch, persisted across reconnects; team needs membership, proximity needs the config flag |

Membership actions (`invite`, `uninvite`, `kick`, `promote`, `demote`,
`transfer`) resolve ONLINE players only: the provided name is used to find the
live Player object, and the live UUID is the authority. There is deliberately
no name-based offline management.

### Friendly-fire contract (ADR-0006)

Cancelled when the attacker's team has friendly fire disabled: direct melee,
projectiles (shooter), splash potions (intensity zeroed), lingering potion
clouds, tamed pets (owner), TNT (igniter), and — with `teams.protect-allies:
true` — allies of the attacking team. Unresolvable sources (creeper
explosions, natural fire tick, environment) are always allowed: attribution is
safe or absent, never guessed. Damage is also allowed while a player's cache
is still loading (join) and when either side is teamless. All decisions are
memory-only; no database access happens in damage events.

## PlaceholderAPI placeholders (expansion `rebellion`) — teams

| Placeholder | Value |
|---|---|
| `%rebellion_team_name%` | viewer's team display name, empty when teamless |
| `%rebellion_team_rank%` | `leader`/`officer`/`member`, empty when teamless |
| `%rebellion_team_online%` | number of online teammates |
| `%rebellion_has_team%` | `true`/`false` |

Player-dependent contributors implement `resolveFor(Player, String)` (added
with a default method so existing contributors are unaffected); the expansion
threads the requesting player through. All teams placeholders read the
hot-path cache only.

## RebellionWards (Phase 4)

Wards are information devices: they report approaching outsiders to the
owner's team and never protect land, blocks, containers or players
(ADR-0007). Anchor blocks are ordinary breakable blocks — destroying one is
the counterplay.

| Command | Subcommand | Permission | Player-only | Behavior |
|---|---|---|---|---|
| `/ward` | `create <name> <radius> [decoy]` | `rebellion.wards.use` | yes | binds a ward to the targeted anchor block (configured material, within reach); radius clamped by config bounds; caps (player/team/global) enforced inside the transaction; decoy subflag config-gated |
| `/ward` | `remove <name>` | `rebellion.wards.use` | yes | removes one of the actor's own/team wards (owner or configured manage rank enforced service-side) |
| `/ward` | `toggle <name>` | `rebellion.wards.use` | yes | activates/deactivates; both directions reset detection state (re-activation re-detects) |
| `/ward` | `list` | `rebellion.wards.use` | yes | own + team wards with live status and calibrated distance |
| `/ward` | `info <name>` | `rebellion.wards.use` | yes | one ward's details incl. reported insider count (suppressed/masked insiders are intentionally invisible) |
| `/wardadmin` | `list [player]` | `rebellion.wards.admin` | no | every ward, optionally filtered by an ONLINE owner (names never authority) |
| `/wardadmin` | `remove <id>` | `rebellion.wards.admin` | no | removes any ward by its server-issued UUID |
| `/wardadmin` | `mask <player> <seconds>` | `rebellion.wards.admin` | no | grants timed ward masking to an ONLINE player (1..86400 s); refuses when `detection.masking.enabled` is false |

Anchor lifecycle (no command involved): breaking the anchor block — or
destroying it via entity/block explosion — removes the bound ward
asynchronously and idempotently; the breaker receives honest feedback
(`You broke the anchor of watch ward '<name>'.`). No protection applies to
the anchor.

Management rights beyond ownership go through the cached
`TeamAffiliationService`: the actor must be in the ward's team with a rank
listed in `wards.manage-ranks` (default leader + officer). Without the
teams module, only the owner manages.

### Ward alert + countermeasure contract (ADR-0007)

- Outsider = anyone who is neither the ward owner nor a cached teammate of
  the ward's team. Missing teams module or a cold cache counts as outsider
  (wards alert rather than silently trusting unknown state).
- Enter alerts fire once per enter/leave cycle; re-entering the same ward
  within `alerts.realert-cooldown-seconds` (per ward, per player) is
  silent; a different ward is never throttled by an unrelated pair.
- `detection.dwell-seconds` delays the enter alert until the player has
  remained inside that long; leaving earlier produces nothing at all.
  Masking during the window suppresses the pending alert.
- Masking (config-gated): holding `detection.masking.held-material` in the
  main hand, or an admin/ritual timed grant. Masked players never trigger
  new enter alerts; delivered alerts are never undone; unmasking inside a
  ward does not retro-detect.
- Leave notices (config `alerts.notify-leave`) fire only when the enter
  actually alerted.
- Alerts route per `alerts.channel` (team/owner/both); the team channel
  falls back to the owner when the ward is teamless, the teams module is
  absent, or the team has no online members.
- Cross-world: wards are world-bound; changing worlds resets that player's
  detection state (documented behavior, not a bug path).

## PlaceholderAPI placeholders (expansion `rebellion`) — wards

| Placeholder | Value |
|---|---|
| `%rebellion_nearest_ward_distance%` | calibrated distance to the nearest own/own-team **active, non-decoy** ward in the viewer's world (`2.4 km` when calibrated, `2400 blocks` otherwise); unset when none |
| `%rebellion_ward_count%` | number of own/own-team wards |

Own-network intel only: nearest *enemy* ward distance is Phase 7 tracking
ritual scope and is deliberately not a placeholder. All reads are
in-memory (index + team cache); no database access.
