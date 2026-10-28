# Commands and permissions

Current command surface (RebellionCore). Feature modules add their own
namespaced commands when they exist; this file is the single source of truth.

## Player feedback contract (ADR-0012)

Every command's user-facing text renders through its module's `messages.yml`
(see docs/CONFIGURATION.md, "Messages contract"). Outcome messages use
deterministic keys — `team.<action>.<outcome>` / `ward.<action>.<outcome>`
with the outcome enum in kebab-case (e.g. `team.kick.cant-target-leader`,
`ward.create.decoys-disabled`) — so an admin can locate and reword any
message without touching code. One-shot feedback carries the configured
`prefix`; data lines in listings do not. Dynamic values (names, ids,
distances) are placeholders, so they can never inject formatting.

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
| `/<open_command>` (per menu) | — | `rebellion.items.menu.<menu-id>` | no | each menu YAML's `open_command` (e.g. `/itemcodex`, `/talismanguide`, `/ritualguide`) is registered as a live top-level command by MenuCommandBridge; same per-menu permission as `/rebellionmenu` |

Items permission nodes (all default op unless noted):

| Node | Scope |
|---|---|
| `rebellion.items.admin` | parent of the five admin nodes |
| `rebellion.items.admin.menuconsole` | required (with the config flag) for `[console]` menu actions |
| `rebellion.items.menu` | base permission for `/rebellionmenu` (default: true); each menu additionally checks `rebellion.items.menu.<menu-id>` |
| `rebellion.items.menu.ritual_guide` | opens `/ritualguide`, the ritual guide over RebellionRPG's definitions (menu text resolves `%rebellion_...%` tokens per viewer; state lines need RebellionRPG installed) |

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

## RebellionDragons (Phase 5, controller slice)

All player feedback renders through the module's `messages.yml` catalog
(ADR-0012) with user values as unparsed placeholders — injection is
structurally impossible. Authority never comes from client input: egg
identity is read from PDC stamps validated against the live registry,
targets from the server-side crosshair raytrace.

### `/dragon` — player-facing lifecycle (permission `rebellion.dragons.use`, default true)

| Subcommand | Behavior | Enforced by |
|---|---|---|
| `hatch` | Hatches the lineage egg held in the main hand: spawns the pet in front/above the holder, binds it via the hatch CAS, consumes the egg only after the commit; a refused CAS (clone/stale/concurrent click) removes the spawn and keeps the egg | LineageService CAS + holder check |
| `status` | Own lineage: state, health, last persisted position, pearl cooldown, fireball budget, gateway origin | mirror reads |
| `call` | Recalls the living pet to a hover above the owner; per-owner cooldown; same-world range limit (cross-world always allowed); a pet whose chunk loads without it is reconciled into the recovery mailbox | in-memory cooldowns + persisted location |
| `mount` | Mounts the owner onto their pet; the right-click interaction (no Ender Pearl held) is the horse-like equivalent. Enabled by default for the live feel pass (`ride.enabled: true`); re-clicking while aboard is an idempotent no-op | config gate + distance check |
| `dismount` | Explicit rider dismount (sneak/shift already lands vanilla-style); ejects the rider and settles the pet so it never flies on alone | controller eject + settle |
| `dismiss` | Dismounts riders and settles the pet into the stable HOVER platform | — |
| `attack` | Fireball at the crosshair target within range; refuses owner/teammate targets; per-dragon rolling budget (default 30/60 s, hard ceiling 120) plus min-interval; vanilla impact semantics (no block damage, cloud on hit) | FireballBudget before spawn + hit-event cancellation |
| `claim` | Reissues a recovery-mailbox lineage egg to its recorded claimant (fresh egg instance; pre-park clones stay inert) | claimant-gated CAS |

Console is denied the whole `/dragon` family (player-only by design).

### `/dragonadmin` — administration (permission `rebellion.dragons.admin`, default op)

| Subcommand | Behavior |
|---|---|
| `list` | Every lineage ever minted with slot, state, owner, gateway origin (retired rows keep consuming slots) |
| `info <slot>` | Status lines plus the full audit trail |
| `audit <slot>` | Cold audit trail read (ISSUE/HATCH/DEATH_RETURN/PARK_RECOVERY/CLAIM/ADMIN_ROUTE/RETIRE/PEARL_HEAL rows) |
| `route <slot> <player>` | Re-routes any egg lineage to an online player with a fresh egg instance; audited with the acting admin |
| `retire <slot> confirm [reason]` | Terminal retirement; the literal `confirm` keyword in the same invocation is the required operator confirmation (ADR-0009 §6); audited; slot + gateway stay consumed forever |

Nothing in the admin family mints lineages. The only mint path is
`issueFromGateway` behind the boss-victory observer (ADR-0014).

### PlaceholderAPI placeholders (expansion `rebellion`) — dragons

| Placeholder | Value |
|---|---|
| `%rebellion_dragon_owned%` | `true`/`false` — does the viewer own a lineage |
| `%rebellion_dragon_id%` | the viewer's lineage UUID, or unset |
| `%rebellion_dragon_health%` | living pet's last persisted health, or unset |
| `%rebellion_dragon_count%` | lineages ever minted (never shrinks) |
| `%rebellion_dragon_cap%` | the effective server-wide cap |

Privacy is structural: viewers see only their own lineage; all reads are
in-memory mirror reads, never per-request SQL.

## RebellionArtifacts (Phase 6, controller slice — ADR-0015)

### /artifact — the player surface

| Command | Behavior |
|---|---|
| `status` | The three rows (localized short name, state, holder, since) + the active occurrence line |
| `return confirm` | Voluntary surrender of the one held live artifact (HELD → RETURNING; the live stack is removed only after the CAS commits) |
| `spend confirm` | The overpowering action: ACTIVE occurrence + trigger holder only; all three rows HELD→SPENT in one all-or-none transaction, the three live stacks die with the superseded stamps |
| `purge confirm` | The config-gated world-wide kill: quadruple runtime gate (occurrence + trigger holder + telegraph elapsed + literal confirm), global warning leads the kill loop by `warning-seconds`, the once-per-occurrence stamp makes it structurally unrepeatable |

Confirm keywords are literals — no free text from the command line is ever
executed (§5.5 command-injection rule). Eligibility is always re-verified
service-side through the CAS; the command layer only routes and renders.

### /artifactadmin — audited administration

| Command | Behavior |
|---|---|
| `list` | The three rows with states and holders |
| `info <artifact>` | Full row detail: id, live instance, origin anchor, state, holder |
| `route <artifact> <player>` | Recovers a stuck LOST/RETURNING row to an online player with a fresh instance; ORIGIN is refused (the realm must be conquered, never granted) and HELD is not the admin's to move |
| `audit <artifact>` | The append-only audit trail (CAPTURE/TRANSFER/LOST/FOUND/RETURN_*/ADMIN_ROUTE/REBELLION_*/SPEND/PURGE rows) |
| `complete confirm` | Force-completes the ACTIVE occurrence (guard cleared, reconquest opens) |
| `events` | Occurrence history with the purge stamp per event |

Permissions: `rebellion.artifacts.use` (default true — possession logic is
service-enforced), `rebellion.artifacts.admin` (default op).

### PlaceholderAPI placeholders (expansion `rebellion`) — artifacts

| Placeholder | Value |
|---|---|
| `%rebellion_artifact_state_<key>%` | ORIGIN/HELD/LOST/RETURNING/SPENT (`<key>`: overworld/nether/end) |
| `%rebellion_artifact_holder_<key>%` | recorded holder UUID, or empty |
| `%rebellion_artifact_event_active%` | `true`/`false` |

Privacy: the protected-origin coordinates are never exposed through
placeholders; all reads are in-memory mirror reads.

## RebellionRPG (Phase 7, controller slice — ADR-0017)

All player feedback renders through the module's `messages.yml` catalog
(ADR-0012). Authority never comes from client input: reagent checks are
server-side pure math over storage contents, targets resolve to known
UUIDs only (online exact match, then the non-blocking cached lookup —
tracking never guesses), and every mutation rides the ADR-0016 ledger
CASes.

### `/ritual` — player-facing casting surface (permission `rebellion.rpg.use`, default true)

| Subcommand | Behavior | Enforced by |
|---|---|---|
| `list` | Every ritual definition with kind, known/undiscovered/disabled state and its reagent list | viewer's discovery read |
| `cast <key>` | KNOWLEDGE rituals: verify reagents → reserve the claim (digest = refund evidence) → remove reagents (leftover = race detector, releases + refunds) → all-or-none commit; already-known keys still spend the claim (typed) | reserve/commit CASes |
| `track <key> <player>` | TRACKING rituals: same claim protocol; target must be a known UUID (online or cached offline — never a guess); a failed roll spends the claim and writes no session | resolveTracking ceiling + commit CAS |
| `mask <key>` | COUNTER_MASK rituals on the caster: masks degrade incoming tracking one tier, never stack | effect rows + commit CAS |
| `status` | Active sessions (effective precision + minutes left), active effects, open reservations, discovery count | per-viewer reads |
| `signal` | On-demand pulse of the viewer's active sessions (compass + bucketed range, effective tier only; masked = nothing resolves; offline target = "beyond sensing") | resolveTracking (the only sanctioned read) |
| `release` | Releases the viewer's open RESERVED claim (nothing was consumed) | release CAS |

Console is denied the whole `/ritual` family (player-only by design —
verified in the boot smoke).

### `/rpgadmin` — audit + recovery (permission `rebellion.rpg.admin`, default op)

| Subcommand | Behavior |
|---|---|
| `events <player>` | The 10 most recent audit rows for a player (claim releases, ritual commits with outcomes, encounter completions, session revocations) |
| `revoke <sessionId> [reason]` | Revokes a tracking session (idempotent, audited); the unwedge path for live tracking state |

Nothing in the admin family mints discoveries, sessions or effects.

### PlaceholderAPI placeholders (expansion `rebellion`) — rpg

| Placeholder | Value |
|---|---|
| `%rebellion_ritual_<key>_discovered%` | `true`/`false` — has the viewer discovered knowledge key `<key>`; resolves from the join-loaded in-memory cache (never a database read per request) |
