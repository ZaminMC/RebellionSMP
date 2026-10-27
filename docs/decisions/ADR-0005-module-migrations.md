# ADR-0005 — Module-owned schema migrations: late, namespaced, fail-closed

Date: 2026-10-28 · Status: accepted

## Context

`MigrationRunner` applies registered module migration sets during Core's
database bootstrap. But feature modules are separate plugins that enable
*after* Core bootstraps (`POSTWORLD`), and `registerMigrations` is an
implementation-class method — unreachable through the `RebellionDatabase`
API and unusable after `bootstrap()` has run. Until now no feature module
owned tables, so the gap was invisible. The teams module (Phase 3) is the
first DB-backed feature module and needs its schema applied at its own
enable time, without weakening any existing guarantee (gap-free versions,
checksum verification, downgrade refusal, atomic bookkeeping, bounded
backpressure, no main-thread JDBC).

## Decision

- The **API** gains `MigrationSpec(int version, String description,
  List<String> statements)` — a pure data record in `rebellion-api` with
  the same validation rules as Core's internal `Migration`. Core converts
  specs to `Migration` internally; checksums stay computed by Core (SHA-256
  over statements) so checksums cannot be forged from the module side.
- `RebellionDatabase` gains
  `CompletableFuture<Void> applyModuleMigrations(String moduleKey, List<MigrationSpec> migrations)`.
  Semantics:
  - validates gap-free versions starting at 1 for the given set, and that
    the database is not already at a higher version for that module key
    (downgrade refusal, same rule as bootstrap);
  - verifies checksums of already-applied versions when
    `strict-checksums` is enabled (Core config);
  - applies pending versions in order and records each in the shared
    `rb_schema_migrations` table exactly like boot-time migrations;
  - registers the set in the runner so the *next* boot applies/checks it
    from the normal path (registration happens before any DB work; if the
    DB work fails, the registration is rolled back so a retry is possible);
  - runs on the bounded persistence executor: saturation fails the future
    with `PERSISTENCE_OVERLOADED` and applies nothing (fail-closed);
  - is idempotent per module key: calling it twice applies nothing the
    second time; two modules using the same key concurrently serialize
    on the runner lock (misuse is logged, not guessed).
- Feature modules must call this method before their first database access
  and must treat failure as fatal for themselves (module health FAILED),
  never for Core. Core keeps running with the previous schema untouched.
- MySQL DDL auto-commit caveat is inherited unchanged: a migration version
  is recorded only after every statement succeeded, and a partially applied
  version is refused on retry (documented in docs/DATA_MODEL.md).

## Consequences

- The migration engine keeps one code path for boot and module-time
  application; only the trigger differs.
- `DatabaseServiceTest` gains coverage of the late path against real SQLite:
  fresh module, idempotent re-run, downgrade refusal, gap detection, and
  behavior when the queue is saturated.
- Modules stay compile-time dependent on `rebellion-api` only; Core remains
  the single owner of connections, checksums and migration state.
