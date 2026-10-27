# ADR-0006 — Teams: schema, atomicity, attribution and chat model

Date: 2026-10-28 · Status: accepted

## Context

Phase 3 requires player-created teams with atomic membership, invites,
ranks, diplomacy, team chat, vanilla-client proximity text chat and
friendly-fire source attribution — with safety for offline leaders,
simultaneous invites, joins while full, disband races, duplicate names,
disconnect-during-accept, database failure and reconnects (master prompt
§5.1). UUIDs are the only player identity; names are never authority.

## Decision

### Schema (module key `teams`, migration v1; SQLite + MySQL compatible)

- `rb_teams` — `id CHAR(36) PK` (server-generated UUID), `name_norm
  VARCHAR(24) UNIQUE NOT NULL` (lowercase [a-z0-9_]{3,24}; the uniqueness
  key, never rendered), `display_name VARCHAR(48) NOT NULL` (rendered),
  `leader CHAR(36) NOT NULL`, `friendly_fire TINYINT NOT NULL DEFAULT 0`,
  `created_at_utc BIGINT NOT NULL`. One row per team.
- `rb_team_members` — `team_id CHAR(36)`, `player CHAR(36)` UNIQUE, `rank_
  key VARCHAR(16) NOT NULL`, `joined_at_utc BIGINT NOT NULL`, PK
  `(team_id, player)`. **UNIQUE(player) enforces one team per player at the
  storage layer** — the DB, not the command path, stops double membership.
  Foreign keys reference `rb_teams(id)` with `ON DELETE CASCADE` so disband
  removes memberships in the same statement scope.
- `rb_team_invites` — `id CHAR(36) PK`, `team_id CHAR(36)`, `inviter
  CHAR(36)`, `invitee CHAR(36)`, `created_at_utc BIGINT`, `expires_at_utc
  BIGINT`, UNIQUE `(team_id, invitee)`. Simultaneous identical invites
  collide on the constraint; acceptance consumes the row inside the same
  transaction that writes membership.
- `rb_team_relations` — `team_a CHAR(36)`, `team_b CHAR(36)`, `relation
  VARCHAR(8)`, PK `(team_a, team_b)` with `team_a < team_b` enforced by
  the writer. Diplomacy is symmetric by construction: exactly one row per
  pair, so "A allies B" and "B enemies A" cannot coexist. Allies receive
  **no team privileges** by design; the only configurable cross-team effect
  is friendly-fire protection for allies (default off).
- `rb_team_history` — append-only audit: `team_id`, `actor CHAR(36)`,
  `action VARCHAR(32)`, `detail VARCHAR(255)`, `recorded_at_utc`. Every
  management mutation writes exactly one history row in the same
  transaction; disband keeps its history (team_id is not FK-checked, so
  rows survive cascade).
- `rb_chat_settings` — `player CHAR(36) PK`, `channel VARCHAR(12) NOT NULL`
  (`global`|`team`|`proximity`). Channel preference persists across
  reconnects; it never authorizes anything.

### Atomicity and failure behavior

- Every membership/invite/diplomacy mutation is one
  `RebellionDatabase.transaction` block; constraint violations map to
  player-facing failure codes, never to half-states.
- Accept flow (the classic race): in one transaction — verify invite row
  (exists, unexpired) → insert membership (UNIQUE(player) rejects if the
  invitee joined another team mid-flight) → delete invite → verify team
  size limit → insert history row → commit. Disconnect during acceptance is
  harmless: the transaction either committed or not; the player reads
  their state at next login.
- Database failure during any mutation surfaces as `PERSISTENCE_FAILURE`
  / `PERSISTENCE_OVERLOADED` with an incident id; nothing is applied to
  caches or players unless the future completed normally.
- Team disband removes memberships, invites and relations for that team
  and leaves only the audit history, all in one transaction.

### Names and identities

- UUIDs only for players and teams; `/team` never resolves a player by
  name for mutations — invites are created for the *online player's UUID*
  from a command argument that must match an online player.
- Team names: display name 3–24 chars of `[A-Za-z0-9_]`, normalized to the
  lowercase uniqueness key. Reserved words (`rebellion`, `admin`, `staff`,
  `team`, `none`) are rejected at both loader and service level.

### Ranks

- Fixed, code-owned rank set: `leader` > `officer` > `member`. Rank grants
  are validated server-side per action (officers invite/kick members,
  leader-only: rename, disband, transfer, promote/demote, diplomacy,
  friendly-fire toggle). Ranks are data, not permission nodes, because they
  are per-team, not per-server.
- Kicking the last member is impossible (leader must disband); leaving as
  the last member auto-disbands (documented, one history row).

### Friendly-fire attribution

- Hot path is memory-only: a per-online-player cache
  (player UUID → team snapshot {teamId, friendlyFire}) is loaded on join
  and refreshed after every successful mutation that affects it. The DB is
  never queried inside damage events.
- Attribution resolver walks, in order: direct player → projectile
  shooter → area-effect-cloud source → tamed pet owner → TNT igniter (when
  the Paper 26.3 API exposes it on `TNTPrimed`). Unresolvable sources
  (creeper, natural fire tick, environment) are **allowed** — attribution
  must be safe, not guessed; the docs list what is and is not cancellable.
- Same team + friendly fire disabled + attacker ≠ victim → the damage
  event is cancelled (highest priority, ignoreCancelled) and splash
  potions have their intensity zeroed for teammates. Ally protection
  applies only when `protect-allies` is true and both teams' rows agree.
- Cache miss (join still loading, DB hiccup): damage is **allowed** and one
  diagnostic is logged per player per interval — never a full-team blanket
  cancel, never a silent DB call.

### Chat

- Team channel: AsyncChatEvent — membership is checked from the cache;
  viewers are replaced by the team's online members (same-world is NOT
  required for team chat); message rendering stays vanilla-shaped.
- Proximity channel (vanilla client compatible): viewers are filtered to
  same-world players within `radius` (horizontal distance, config), plus
  a bounded recipient pass; no global player scan beyond one
  world-scoped pass.
- Global channel: untouched vanilla behavior.
- Every channel switch persists to `rb_chat_settings`; chat handlers never
  write to the DB (reads go through the cache).
- Real voice chat is out of scope; documented as the optional Simple Voice
  Chat integration module for a later phase.

## Consequences

- One team per player is enforced by the schema; no membership code path
  can violate it.
- The friendly-fire listener has no async DB access, no per-event
  allocation beyond the resolver walk, and is unit-testable against the
  server-free attribution resolver.
- Diplomacy symmetry is guaranteed by storage shape; "enemy" rows exist for
  record-keeping and ward-intel (Phase 4), not for hidden mechanics.
- History gives operators the auditable management trail §5.1 requires;
  it is append-only and survives disband.
