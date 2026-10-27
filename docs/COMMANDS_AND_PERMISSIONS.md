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
| `/rebellionitems` | `reload` | `rebellion.items.admin.reload` | yes | atomic reload of config + rarities + menus; on error the previous state stays active and every issue is printed |
| `/rebellionitems` | `status` | `rebellion.items.admin.status` | yes | annotation mode, override count, registered menus |
| `/rebellionmenu <menu-id>` | — | `rebellion.items.menu.<menu-id>` | no | opens a registered menu; per-menu permission is enforced server-side at open and click time (visibility is not authorization) |

Items permission nodes (all default op unless noted):

| Node | Scope |
|---|---|
| `rebellion.items.admin` | parent of the four admin nodes |
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

Contract: in-memory reads only (no SQL per lookup); unknown parameters return
`null` so PAPI leaves the text untouched; registered only when PlaceholderAPI
is present.
