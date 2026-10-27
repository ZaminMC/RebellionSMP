# Recovery playbook

Procedures for interrupted operations and administrative reconciliation.
There is deliberately no hidden "reset all players" command anywhere.

## Interrupted database bootstrap

Symptom: Core disabled with "database bootstrap failed" + incident ID.
1. Read the report in `plugins/RebellionCore/diagnostics/` for the incident.
2. Most causes are actionable from the message: unwritable directory,
   bad MySQL credentials, edited applied migration (strict checksums).
3. SQLite: failed migrations roll back atomically — the file remains at the
   last complete migration; fix the cause and restart. Never delete the
   database to recover; you would destroy all persistent state.
4. MySQL: same rules; server-side rollback applies.

## Persistence queue left un-flushed after a crash

Symptom: shutdown log line "droipped N queued operations" (or a hard crash).
- Operations rejected or dropped before execution never had SQL side
  effects — they can simply be retried by their owning gameplay action.
- Any *in-flight* transaction either committed or rolled back entirely
  (SQLite transactional DDL/DML); there are no half-committed rows.
- After restart, `/rebellion status` shows migration + queue metrics;
  module enable re-waits on bootstrap, so no feature starts against a
  broken schema.

## Config rollback

`/rebellion reload` failures keep the previous valid snapshots — nothing to
do beyond fixing the reported keys. If startup config is invalid, Core
disables with every offending path listed; the previous on-disk file is never
overwritten by a bundled resource, so restoring is a manual file edit away.

## Diagnostics directory damaged/removed

The directory is recreated on the next `writeReport`; past reports are lost
but incidents regenerate. No game state lives there.

## PlaceholderAPI integration misbehaving

Remove PlaceholderAPI (or the expansion unregisters with Core on disable).
Core never depends on it; placeholders are pure in-memory reads and cannot
corrupt state.

## Future modules

Each feature module that owns irreversible state (dragon lineages, artifacts,
unique items, prison sentences, currency) must extend this file with:
- the durable pending-state it can be left in after a crash,
- the exact admin reconciliation command(s),
- and what those commands deliberately refuse to do.
