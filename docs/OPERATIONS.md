# Operations guide

## Install

1. Paper 26.3 server, Java 25 runtime (per Paper's toolchain).
2. Copy `RebellionAPI-<version>.jar` and `RebellionCore-<version>.jar` into
   `plugins/`.
3. Boot once. Core creates `plugins/RebellionCore/{config.yml,
   database.yml, messages.yml}` and the SQLite database.
4. (Optional) Install PlaceholderAPI — Core detects it and registers the
   `rebellion` expansion; without it, Core boots normally.
5. Add feature module JARs as they are released; they enable after Core and
   wait for the database bootstrap automatically.

## Upgrade

1. Stop the server (restart applies `database.yml` changes too).
2. Replace the JARs. Config files are preserved; new keys gain documented
   defaults on the next load and unknown keys are kept.
3. Schema migrations run automatically at startup, append-only. A migration
   failure refuses to enable dependent modules — do not delete the database
   to "fix" it; read the incident report.

## Databases

- **SQLite (default)**: `plugins/RebellionCore/rebellion.sqlite`, WAL mode.
  Backup: stop the server and copy the file (or snapshot `…-wal` alongside).
- **MySQL/MariaDB**: set `backend: mysql` + connection settings in
  `database.yml`; prefer `password: ${REBELLION_DB_PASSWORD}` over literal
  values. Server-side backups; keep credentials out of version control.
- Never run multiple servers against one SQLite file (single writer by
  design — Core pins its pool to one connection for this reason).

## Diagnostics and incidents

- `/rebellion status` — runtime health at a glance.
- `/rebellion modules` — per-module states and failure details.
- `/rebellion diagnose` — writes a sanitized report with incident ID to
  `plugins/RebellionCore/diagnostics/` (rotation: newest 10 kept).
- Incident IDs (`INC-YYYYMMDD-HHMMSS-nnnnnn`) correlate log lines with
  reports; quote them in bug reports.
- Persistence saturation is visible as `PERSISTENCE_OVERLOADED` failures and
  a rising queue metric in `status`; the queue rejects safely (no side
  effects) — increase `executor.queue-capacity` or worker count only after
  reviewing the source of load.

## Earth world (future; not required for Core)

The EarthMC Terra Aurora archive (~50.8 GB, MC 1.21.8 era) is an
operations-managed asset:

- Never commit world files to Git; never download/upgrade one during code work.
- Stage on a copy; keep the original untouched; validate chunk/entity/portal
  loading on a disposable server before pointing production at it. Paper warns
  upgrades may be irreversible.

## Shutdown

Stop the server normally: Core drains the bounded persistence queue (≤ 10 s),
reports any un-flushed operation with an incident ID, closes the pool, and
deregisters services. Killing the process risks losing queued (not yet
committed) writes — the design keeps that set small and reportable, and
unique-asset operations fail closed rather than half-apply.
