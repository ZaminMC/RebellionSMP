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
