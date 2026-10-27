# ADR-0002 — Database strategy: shared pool + bounded async executor in Core

Date: 2026-10-27 · Status: accepted

## Context

Requirements: SQLite out-of-box, optional MySQL/MariaDB via config, no JDBC on
the main thread, bounded backpressure, fail-closed unique assets, one central
pool (modules must not create their own). Paper 26.3 ships SQLite and
MySQL Connector/J drivers on the server classpath (verified against the
server's library listing) but does **not** ship HikariCP.

## Decision

- Core owns exactly one HikariCP pool + one bounded async executor
  (default 4 workers, queue 2000). All module persistence goes through
  `DatabaseService implements RebellionDatabase`; modules get repositories,
  not connections.
- HikariCP 7.1.0 (Apache-2.0) is shaded **and relocated** into RebellionCore.
- JDBC drivers are never bundled — SQLite and MySQL drivers come from the
  server; the test suite pins the same sqlite-jdbc version for parity.
- SQLite pool size is forced to exactly 1 connection (single-writer
  semantics; eliminates `SQLITE_BUSY`); WAL journal mode is applied at
  bootstrap; `foreign_keys=ON` per connection via init SQL.
- Saturation rejects submissions **before any SQL executes** and fails the
  future with `PERSISTENCE_OVERLOADED` + incident ID (no side effects by
  construction). Unique-asset callers must treat it as fail-closed.
- Schema migration engine (`MigrationRunner`): per-module registries, gap
  detection at registration, SHA-256 checksums, strict downgrade refusal,
  transactional application on SQLite, durable bookkeeping table.

## Consequences

- MySQL/MariaDB remain configuration-verified only (no CI integration server);
  the compatibility matrix states this plainly.
- The bounded queue is the single, testable backpressure point; queue
  metrics feed `/rebellion status`.
- Tests exercise real SQLite files, so persistence behavior is verified, not
  mocked. A future MariaDB test profile can slot into the same suite.
