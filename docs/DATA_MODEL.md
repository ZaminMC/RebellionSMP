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
