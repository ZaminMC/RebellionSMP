# Data model

Current state: Core owns the migration framework and bookkeeping only. No
feature module has tables yet — the first feature-owned schema lands with
`rebellion-items` (Phase 2) and will be documented here with its migration.

## Migration framework (implemented, tested)

- Registry: `MigrationRunner`, one registry per module key
  (`register("items", List<Migration>)`).
- `Migration(version, name, statements)` — versions are 1-based, monotonic,
  gap-free per module; gaps are rejected at registration time (fail fast,
  tested).
- Bookkeeping table (created on first bootstrap):

```sql
CREATE TABLE IF NOT EXISTS rb_schema_migrations (
  module_key     TEXT    NOT NULL,
  version        INTEGER NOT NULL,
  name           TEXT    NOT NULL,
  applied_at     TEXT    NOT NULL,          -- ISO-8601 instant
  checksum       TEXT    NOT NULL,          -- SHA-256 over module+version+statements
  PRIMARY KEY (module_key, version)
)
```

- Rules enforced (all covered by `MigrationRunnerTest`):
  - re-registering an unchanged registry is idempotent;
  - checksum mismatch on an applied version → `MigrationException`
    (strict mode configurable via `database.yml`);
  - an older build than the recorded version → refusal ("refusing to run an
    older build") — no destructive downgrade;
  - a failed migration rolls back atomically on SQLite (transactional DDL)
    and leaves the schema untouched;
  - application is idempotent per version; partially migrated databases
    resume at the correct next version.

## Conventions for feature-module schemas (binding for future modules)

- Table prefix `rb_<module>_<entity>` (e.g. `rb_teams_members`).
- UUIDs stored as `TEXT` (canonical string form); identity columns are
  `TEXT`/`INTEGER` primary keys or `BLOB` where size demands it — never float
  or composite text blobs.
- Uniqueness where scarcity is a product requirement (lineage IDs, artifact
  IDs, unique item instance IDs, team membership, transaction idsempotency
  keys) is enforced by `UNIQUE` constraints, not application checks alone.
- Migrations are append-only: never edit an applied migration; add a new
  version. Destructive migrations require an OPERATIONS.md note.
- Repositories use prepared statements via `Sql.bind` — no string
  concatenation with user data anywhere.
- State transitions spanning rows go through `transaction(...)`; the caller's
  future receives the failure and no partial state is committed.

## Retention, backup, recovery

- SQLite: single file under `plugins/RebellionCore/`; WAL journal mode.
  Backup = copy the file while the server is stopped (or use the
  documented SQLite online-backup approach in OPERATIONS.md).
- MySQL/MariaDB: server-side backups; `database.yml` holds only connection
  parameters; credentials via env expansion.
- Every module must define crash reconciliation in RECOVERY.md once it owns
  state.

## Teams schema (module key `teams`, migration v1 — implemented, tested)

ADR-0005 introduced module-owned late migrations: feature modules ship
`MigrationSpec` sets and apply them at enable through
`RebellionDatabase.applyModuleMigrations(moduleKey, specs)`, which waits for
the Core bootstrap, registers + checksum-verifies the set and applies pending
versions on the bounded persistence executor. Idempotent per module key;
downgrades and changed sets are refused. ADR-0006 defines the teams schema:

- `rb_teams` — one row per team: `id` (UUID), `name_norm` (lowercase
  `[a-z0-9_]{3,24}`, UNIQUE — the uniqueness key), `display_name` (rendered
  form), `leader` (UUID), `friendly_fire` (0/1), `created_at_utc`,
  `renamed_at_utc` (rename cooldown anchor; initialized to creation time).
- `rb_team_members` — PK `(team_id, player)`, `UNIQUE(player)`: the one-team-
  per-player rule is enforced by storage, not by command code. `rank_key`
  ∈ {leader, officer, member}; `joined_at_utc`. FK to `rb_teams` ON DELETE
  CASCADE.
- `rb_team_invites` — `UNIQUE(team_id, invitee)`; `expires_at_utc` gates
  acceptance (expired rows are invisible everywhere and replaced on
  re-invite). FK cascade removes them with the team.
- `rb_team_relations` — symmetric diplomacy: exactly one row per pair with
  `team_a < team_b` (writer-enforced), `relation` ∈ {ally, enemy}
  (neutral = row removed). Allies never gain team privileges; the only
  cross-team effect is optional friendly-fire protection (config
  `teams.protect-allies`, default off).
- `rb_team_history` — append-only audit of every management mutation (create,
  invite, join, decline, uninvite, kick, leave, disband, disband_auto,
  rename, transfer, promote, demote, friendlyfire, relation), written in the
  same transaction as the mutation. `team_id` has no FK so history survives
  disband.
- `rb_chat_settings` — persisted per-player channel preference
  (global/team/proximity) surviving reconnects.
- `rb_team_player_state` — `last_create_utc` / `last_invite_utc` anchors for
  the create and invite rate limits.

Attribute chains (see ADR-0006 for the full contract): every membership
mutation runs in one transaction with the rank check against live rows; the
UNIQUE constraints are the race backstop — racing double-accepts or duplicate
names lose with a typed outcome, never a half-state. Cache refresh happens
only after commit and is composed into the caller's future.

## Wards (module key `wards`, migration v1 — ADR-0007)

`rb_wards` — one row per watch ward, bound to a placed anchor block:

- `id CHAR(36) PK` — server-issued ward UUID.
- `name VARCHAR(24)` + `name_norm VARCHAR(24)` — display form and the
  owner-scoped, case-insensitive uniqueness key
  (`UNIQUE (owner, name_norm)`); charset `[A-Za-z0-9_-]{3,24}` validated
  before insert.
- `owner CHAR(36)` — creating player; the only management authority without
  a team binding.
- `team_id CHAR(36) NULL` — creator's team at creation time. NULL for
  teamless owners or when the teams module is absent. Stale ids (team
  disbanded) are harmless: they can never match a live cache lookup, so the
  ward degrades to owner-bound.
- `world VARCHAR(64)`, `x/y/z DOUBLE` — the anchor block's center position
  (`block + 0.5`). `UNIQUE (world, x, y, z)` enforces one ward per placed
  anchor across all owners — the race backstop for two players targeting
  the same block.
- `radius DOUBLE` — horizontal detection radius in blocks (bounds enforced
  by config at the service edge).
- `type VARCHAR(8)` — `WARD` or `DECOY` (decoys detect identically but are
  labeled in alerts and excluded from own-intel/map surfaces).
- `active TINYINT` — toggled by `/ward toggle`; inactive wards stay
  persisted but never detect.
- `created_at_utc BIGINT`.

Indexes: `rb_wards_owner_idx (owner)`, `rb_wards_team_idx (team_id)`,
`rb_wards_active_world_idx (active, world)`.

There is deliberately **no protection/region/member table**: a ward is an
information device (master prompt §5.2). Caps (`max-wards-per-player`,
`max-wards-per-team`, `max-wards-total`) are checked inside the creation
transaction, mirroring the teams module's joins-while-full strategy — the
UNIQUE constraints plus in-transaction counts settle concurrent creates
with typed outcomes, never half-states. The in-memory mirror (spatial
index + anchor map) is updated only after commit and rebuilt by a full
read at enable (`WardService.loadAll`); detection state is ephemeral by
design (see ADR-0007).
