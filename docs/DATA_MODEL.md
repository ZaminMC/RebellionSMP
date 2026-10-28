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

## Dragon lineage registry (module key `dragons`, migrations v1+v2 — ADR-0009, controller columns in v2)

`rb_dragons_lineages` — one row per lineage ever minted (hard cap 20):

- `lineage_id CHAR(36) PK` — immutable server-issued lineage UUID.
- `slot INT` — allocation ordinal (`MAX(slot)+1` inside the mint
  transaction), `UNIQUE`. Never reused, never shifts on ownership change;
  operators see "lineage #7", never a raw UUID.
- `state VARCHAR(16)` — `EGG_HELD`, `EGG_RECOVERY`, `HATCHED` or `RETIRED`.
  Every transition is a compare-and-set UPDATE pinned on the expected prior
  state plus the live egg/entity identity.
- `owner CHAR(36) NULL` — current egg holder or dragon owner; NULL only for
  `RETIRED`.
- `egg_instance CHAR(36)` — the PDC instance stamp the physical egg item
  must carry. Fresh on every (re)issue/claim/route, so cloned and stale
  copies can never win the hatch compare-and-set. Kept on retirement for
  forensics.
- `entity_uuid CHAR(36) NULL` — the bound living pet entity; present only
  while `HATCHED`; `UNIQUE` (one lineage per living entity). Cleared by the
  death-cycle transition, which is what makes duplicate death events
  idempotent: the CAS pins the entity, so the second event affects zero
  rows.
- `origin_world VARCHAR(64)`, `gateway_x/y/z INT` — the observed vanilla
  End gateway anchor that minted the row.
  `UNIQUE (origin_world, gateway_x, gateway_y, gateway_z)` is the
  gateway-validity guard and the issuance idempotency key: one gateway
  position mints exactly one lineage, ever — duplicate victory events,
  listener re-registrations and event retries all settle here.
- `health DOUBLE NULL` — hatch-time health snapshot while `HATCHED`;
  refreshed by pearl heals and the periodic controller snapshot.
- `last_pearl_heal_utc BIGINT` — cooldown stamp of the last committed pearl
  heal; the heal CAS requires it to be at least the configured cooldown
  old, which is what makes retried interact events typed refusals instead
  of double heals (0 = never).
- `loc_world VARCHAR(64) NULL`, `loc_x/loc_y/loc_z DOUBLE NULL` —
  **(v2)** last persisted pet position; all-or-none, NULL until the first
  snapshot after hatch. Lets `/dragon call` resolve a pet whose chunk is
  unloaded and lets the sweep reconcile bindings whose entity vanished.
- `last_persist_utc BIGINT NOT NULL DEFAULT 0` — **(v2)** stamp of the last
  controller snapshot write.
- `created_at_utc`, `updated_at_utc BIGINT`.

`rb_dragons_audit` — append-only audit trail (`id CHAR(36) PK`,
`lineage_id`, `action` ∈ {ISSUE, HATCH, DEATH_RETURN, PARK_RECOVERY,
CLAIM, ADMIN_ROUTE, RETIRE, PEARL_HEAL}, `actor` NULL for system actions,
`detail VARCHAR(255)`, `at_utc`). One row per real mutation, written in
the same transaction; idempotent no-ops write nothing. Indexed by
`lineage_id` and `at_utc` for admin forensics.

Structural guarantees (all proven by `LineageServiceIT` over real SQLite):

- **Only issuance inserts.** Transfers (death/claim/route) and hatches are
  pure UPDATEs; the row count can only grow through the gateway-keyed,
  count-checked mint. The cap (config `dragons.max-lineages`, default 20,
  hard ceiling 20 enforced at config load AND runtime) therefore cannot be
  exceeded by any gameplay path, and retirement never frees a slot — the
  physical gateway that backed the lineage does not respawn.
- **One egg per death.** The death CAS replaces the HATCHED row's egg
  identity with a fresh instance instead of inserting anything, so
  simultaneous owner-death + dragon-death handling produce exactly one egg
  (`EGG_RETURN_NOOP` for every loser).
- **One dragon per egg.** The hatch CAS pins the live `egg_instance` and
  only accepts `EGG_HELD`; recovery eggs exit exclusively through the
  claimant-gated claim or the audited admin route (both re-issue the
  instance), so pre-park clones can never hatch out of the mailbox.

The in-memory mirror (by id / by egg instance / by entity / by gateway)
is updated only after commit and rebuilt by `LineageService.loadAll()` at
enable — same strategy as teams/wards.

## Dimension-artifact registry (module key `artifacts`, migration v1 — ADR-0013)

`rb_artifacts_registry` — **exactly one row per dimension artifact** (three
rows, forever: `OVERWORLD`, `NETHER`, `END`):

- `artifact_key VARCHAR(16) PK` — the dimension key; the stable identity.
- `artifact_id CHAR(36)` — immutable server-issued UUID (`UNIQUE`); the PDC
  identity of the physical item family.
- `state VARCHAR(16)` — `ORIGIN`, `HELD`, `LOST`, `RETURNING` or `SPENT`.
  Every transition is a compare-and-set UPDATE pinned on the expected prior
  state plus the expected holder/live instance stamp.
- `holder CHAR(36) NULL` — current holder (`HELD`) or last rightful holder
  (`LOST`, kept for forensics and the admin route); NULL in the other three
  states.
- `instance CHAR(36)` — the live PDC instance stamp. Fresh on every
  possession change (capture/find/transfer/voluntary return/arrival), so
  cloned and stale copies can never win a possession CAS. In
  `RETURNING`/`SPENT` the column holds the superseding stamp — whatever
  physical copy is left over is inert.
- `origin_world VARCHAR(64)`, `origin_x/origin_y/origin_z INT` — the
  protected origin realm anchor. **Config-owned world-setup data**, not
  gameplay state: `loadAll()` re-syncs these columns from `config.yml` on
  every boot and touches nothing else.
- `holder_since_utc BIGINT NOT NULL DEFAULT 0` — when the current holder
  took possession (0 whenever the holder is NULL).
- `state_since_utc BIGINT` — when the current state was entered; the
  return-delay base for LOST/SPENT rows.
- `created_at_utc`, `updated_at_utc BIGINT`.

`rb_artifacts_audit` — append-only audit trail (`id CHAR(36) PK`,
`artifact_key`, `action` ∈ {CAPTURE, TRANSFER, LOST, FOUND, RETURN_SUBMIT,
RETURN_BEGIN, RETURN_COMPLETE, ADMIN_ROUTE, REBELLION_TRIGGER, SPEND,
REBELLION_COMPLETE}, `actor` NULL for system actions, `detail
VARCHAR(255)`, `at_utc`). One row per real mutation, written in the same
transaction; idempotent no-ops write nothing. The seed writes no audit row —
the trail starts at the first gameplay mutation. Indexed by `artifact_key`
and `at_utc`.

`rb_artifacts_events` — one row per Rebellion-event **occurrence**
(`event_id CHAR(36) PK`, `trigger_holder`, `reason VARCHAR(255)` (the
auditable initiation reason), `state` ∈ {ACTIVE, COMPLETED},
`initiated_at_utc`, `completed_at_utc NULL`). Once per occurrence, not once
per server: after completion a new full possession mints a NEW durable
event id — no permanent winner.

`rb_artifacts_event_guard` — a singleton row (`singleton INT PK` = 1)
holding `active_event_id CHAR(36) NULL`. "At most one active occurrence" is
enforced by the single-row-ness plus the acquire/release compare-and-sets,
not by event-layer dedup code.

Structural guarantees (all proven by `ArtifactServiceIT` over real SQLite):

- **The seed is the only insert.** Rows are created only by the idempotent
  load-time seed (PK backstop on `artifact_key`); every gameplay transition
  is a pure UPDATE, so "exactly three" cannot drift, and a table that does
  not hold exactly three rows fails the module load loudly.
- **Never two valid copies.** Possession requires presenting the live
  instance; every possession change supersedes it, so a copied artifact can
  never move a row — which is what makes the Rebellion qualification
  copy-proof without any dedup code.
- **All-or-none spending.** The three spend CASes share one transaction; a
  mid-set race rolls everything back to the typed `SPENT_CONFLICT`.

The in-memory mirror (by key / by live instance, presentable states only)
is updated only after commit and rebuilt by `ArtifactService.loadAll()` at
enable — same strategy as teams/wards/dragons.

### rb_artifacts_events — schema v2 (ADR-0015 §4, session 12)

`purge_executed_utc BIGINT NULL` — the once-per-occurrence purge stamp.
NULL means the purge has not run for this occurrence; the executor's
compare-and-set (`purge_executed_utc IS NULL → now`) makes the kill set
structurally exactly-once: retried commands, restarts mid-warning and
racing admins resolve to the typed no-op `PURGE_ALREADY_EXECUTED`. The
stamp commits (with its audit row) before any player is touched, and
survives occurrence completion. Migration v2 is an additive `ALTER TABLE`
applied in one boot on first start (proven live: the smoke server's
artifacts schema went v0 → v2 in one boot; the append-only migration-set
guard is pinned by test).

## RPG module (module key `rpg`, migration v1 — ADR-0016)

Six tables under the `rb_rpg_` prefix; enum-string states, server-issued
UUID keys, no auto-increment, portable SQLite/MySQL DDL. All mutations are
compare-and-sets or PK-guarded inserts; every mutation transaction also
writes its audit row into `rb_rpg_events`.

### rb_rpg_discoveries — the discovery ledger

`PRIMARY KEY (player_uuid, discovery_key)` — a player knows a ritual
recipe or knowledge flag, idempotent by structure: the duplicate INSERT
surfaces as a PK constraint violation and is classified into the typed
no-op `ALREADY_KNOWN` (never an error). Columns: `player_uuid CHAR(36)`,
`discovery_key VARCHAR(64)` (config-defined), `source VARCHAR(16)` ∈
{ENCOUNTER, RITUAL, ADMIN}, `discovered_utc BIGINT`. There is no UPDATE
path — a discovery is a fact.

### rb_rpg_claims — the ritual claim anchor

`PRIMARY KEY (claim_id)` — the two-phase transactional anchor that makes
"consumed ingredients and ritual state changes are one logical operation"
honest across the inventory/database boundary. Columns:
`player_uuid CHAR(36)`, `ritual_key VARCHAR(64)`, `state VARCHAR(16)` ∈
{RESERVED, COMMITTED, RELEASED} (all transitions CAS on `state`; no path
back to RESERVED), `ingredients_digest VARCHAR(255)` (the controller's
snapshot of the exact verified reagent set — refund evidence for the
crash-between-removal-and-commit window), `created_utc BIGINT`,
`resolved_utc BIGINT NULL`.

Structural guarantees (proven by `RpgServiceIT` over real SQLite):

- **Rate limit counts attempts of any state** (released attempts still
  count — anti-abuse stays conservative), while **cooldown counts
  COMMITTED attempts only** (a released reservation never bought a
  cooldown).
- **Single reservation per (player, ritual)** — the ingredient-race
  backstop: two simultaneous casts over one reagent set cannot both hold
  reservations.
- **Session-cap refusal auto-releases** the claim in its transaction (the
  one mutated refusal).

### rb_rpg_tracking_sessions — tracking outcomes

`PRIMARY KEY (session_id)`, `UNIQUE (claim_id)` — one session per
committed claim, ever (re-delivery cannot mint a second). Columns:
`caster_uuid CHAR(36)`, `target_uuid CHAR(36)` (offline players are legal
targets; unknown names never reach persistence), `precision VARCHAR(16)`
(the tier granted at cast time — DIMENSION/REGION/COORDINATE — the
ceiling of what any signal may ever reveal), `began_utc`, `expires_utc`
(**derived expiry**: a session resolves while unrevoked and unexpired; no
state column, no sweep to forget), `revoked_utc BIGINT NULL` +
`revoke_reason VARCHAR(64)` (admin/recovery revocation, own CAS).

### rb_rpg_effects — counter-ritual effects

`PRIMARY KEY (effect_id)` — time-bounded player effects; the in-slice
key is `masking` (an active mask on the *target* degrades incoming
tracking precision by exactly one tier at resolution time; masks do not
stack — a second grant renews via its own later expiry). Columns:
`player_uuid`, `effect_key VARCHAR(64)`, `granted_utc`, `expires_utc`
(active = `expires_utc > now`; rows kept after expiry for forensics),
`granted_by CHAR(36) NULL` (the counter-ritual caster; null = admin).

### rb_rpg_encounters — the encounter ledger

`PRIMARY KEY (encounter_id)` — the bookkeeping half of the rare-soul
encounter state machine; the controller spawns entities only against a
begun row. Columns: `encounter_key VARCHAR(64)` (config-defined),
`world VARCHAR(64)`, `x/y/z INT` (spawn site), `state VARCHAR(16)` ∈
{ACTIVE, COMPLETED, EXPIRED, DESPAWNED} (all transitions CAS; no path
back to ACTIVE), `soul_entity CHAR(36) NULL` (attached by the controller
once an entity exists; idempotent CAS), `began_utc`, `resolved_utc BIGINT
NULL`, `completed_by CHAR(36) NULL`. The concurrency cap, encounter
cooldown, TTL cleanup and one-winner reward claims are all answered from
these rows — never from scheduler memory.

### rb_rpg_events — append-only audit

`PRIMARY KEY (event_key VARCHAR(128))` — deterministic server-composed
keys: `claim:<uuid>:commit`, `claim:<uuid>:release`,
`discovery:<player>:<key>`, `encounter:<uuid>:complete|attach|expire|
despawn`, `session:<uuid>:revoke`. Columns: `player_uuid CHAR(36) NULL`,
`type VARCHAR(32)`, `detail VARCHAR(255)`, `outcome VARCHAR(16)` (the
durable commit outcome — what re-delivery reads back), `at_utc BIGINT`.
Idempotent by construction; written inside the mutation's transaction.

## Alchemy module (module key `alchemy`, migration v1 — ADR-0019)

Five tables under the `rb_alchemy_` prefix; enum-string states, server-issued
UUID keys, no auto-increment, portable SQLite/MySQL DDL. All mutations are
compare-and-sets or PK-guarded inserts; every mutation transaction also
writes its audit row into `rb_alchemy_events`. The potion vocabulary is
`PotionType` (a real enum, serverless-resolvable — proven against the pinned
paper-api 26.3 jar; `PotionEffectType` is registry-bound and stays
controller-phase).

### rb_alchemy_progress — the atomic XP accumulate

`PRIMARY KEY (player_uuid)` — one row per brewing player: `total_xp BIGINT`,
`updated_utc BIGINT`. **The level is derived, not stored**: the pure
`Leveling` walk (`cost(level) = base + (level−1)·growth`, clamped at
max-level) computes it from `total_xp` on every read, the same discipline as
the RPG derived expiry — no level column can drift, no sweep can forget to
recompute, and a curve rebalance applies on the next read with zero
migration. Fresh awards either INSERT the first row or use the atomic
accumulate `total_xp = total_xp + ?` (single-statement read-modify-write);
the concurrent-first-award PK violation is classified into the accumulate
path, never an error.

### rb_alchemy_unlocks — the two-path unlock ledger

`PRIMARY KEY (player_uuid, unlock_key)` — idempotent by structure (the
duplicate INSERT loses the PK; there is no UPDATE path — an unlock is a
fact). Columns: `granted_utc BIGINT`, `claim_id CHAR(36) NULL` (the purchase
claim that paid for it; null = admin grant, which has its own audited
service path and deliberately ignores the definition's `enabled` flag as a
staff recovery tool). Definitions live in config with the two §5.7 paths
kept structurally distinct: POWER carries potion-type/amplifier/duration
grants, EFFICIENCY carries cost-reduction/bag-capacity/auto-cooldown
grants — kind-mismatched fields are refused at load, so efficiency can
never quietly add combat strength.

### rb_alchemy_rules — player-defined auto-consumption rules

`PRIMARY KEY (rule_id)` — the §5.7 auto-consumption state: `trigger`
(HEALTH_BELOW / BURNING / NETHER_ENTRY / FALLING), `potion_type`
(policy-whitelisted), `threshold INT NULL` (HEALTH_BELOW only),
`cooldown_seconds` (>= the operator floor at define time), `priority`
(lower = earlier; ties by created_utc then rule_id — deterministic
conflict handling), `storage` (BAG / INVENTORY / ANY — the storage
requirement), `enabled` (the toggle; ownership-guarded CAS), `created_utc`,
`updated_utc`, `removed_utc BIGINT NULL` (removed rules stop resolving but
are kept for forensics; their rule id is consumed forever), and
`last_fired_utc BIGINT NULL` — **the cooldown authority, stamped only by a
committed execution**. The resolution set (`rulesFor`) is live + enabled
rows in the conflict order; the controller evaluates at most one rule per
trigger per cycle from it.

### rb_alchemy_claims — the transactional anchor

`PRIMARY KEY (claim_id)` — the ADR-0016 two-phase discipline generalized
with a `purpose` discriminator (UNLOCK_PURCHASE | AUTO_CONSUME) and
`subject_key` (the unlock key, or the rule id as a string). Columns:
`state` ∈ {RESERVED, COMMITTED, RELEASED} (all transitions CAS; no path
back to RESERVED), `digest VARCHAR(255)` (the controller's snapshot of
the exact cost set / potion it verified — refund evidence for the
crash-between-removal-and-commit window), `created_utc`, `resolved_utc`.
Reserve-time guards answered from rows: unlock purchases check the
*derived* level gate + every prerequisite + non-ownership + the
single-reservation race guard; auto-consumes check ownership, liveness,
enabled, the `last_fired_utc` cooldown, and one execution reservation per
rule. `releaseStaleClaims(olderThan)` is the crash-recovery sweep.

### rb_alchemy_events — append-only audit

`PRIMARY KEY (event_key VARCHAR(160))` — deterministic server-composed
keys: `xp:<player>:<source>` (the XP idempotency gate itself — the award
inserts this row *before* any mutation, so a replayed source is a clean
no-op, and finalizes its outcome column to LEVEL_UP/XP inside the same
transaction), `claim:<uuid>:commit`, `claim:<uuid>:release`,
`rule:<uuid>:define|toggle|remove`, `unlock:<player>:<key>`. Columns:
`player_uuid CHAR(36) NULL`, `type`, `detail`, `outcome` (the durable
commit outcome — what re-delivery reads back), `at_utc`. Idempotent by
construction; written inside the mutation's transaction.

## Economy module (module key `economy`, migration v1 — ADR-0021)

Two tables over exact long minor units; every mutation is one transaction
pairing the account change with its append-only ledger row (a balance
never moves without its audit entry). Idempotency is structural:
`UNIQUE (account_uuid, idempotency_key)`.

### rb_economy_accounts — the balance rows

`PRIMARY KEY (account_uuid)` — one row per account, created lazily on
first mutation. `balance BIGINT` minor units, `updated_utc`. Balances
mutate only through guarded accumulates inside the mutation
transactions; the invariants (never negative, never above the
configured `max-balance`, validated ≤ 2^62 with a 2^40 single-mutation
ceiling) are checked before the write — the §5.9 overflow answer.

### rb_economy_ledger — the append-only idempotent audit

`PRIMARY KEY (entry_id CHAR(36))`, `UNIQUE (account_uuid,
idempotency_key VARCHAR(96))`. Columns: `reason VARCHAR(64)` (caller's
grammar-checked audit reason, e.g. `prison.release-fee`), `delta BIGINT`
(signed), `balance_after BIGINT` (the exact post-mutation balance — any
account's history can be reconstructed and audited from the ledger
alone), `created_utc`. A replayed key loses the constraint and resolves
to the typed successful no-op carrying the original entry id — crash
reconciliation without a second charge.

## Prison module (module key `prison`, migration v1 — ADR-0021)

Six tables. The §5.8 death contract ("apply once"), the sentence
lifecycle with a structural one-live-sentence guard, the §5.9 fee audit
as a frozen claim row, and idempotent clamped rehabilitation.

### rb_prison_profiles — the death-score and scar ledgers

`PRIMARY KEY (player_uuid)`. `death_score BIGINT` (accumulates
forever), `scar_points INT`, `restored_points INT` (both write-clamped
inside single-statement CASE updates: scars never exceed the configured
active-penalty ceiling; restoration never exceeds scars),
`updated_utc`. The active health penalty is derived (never stored):
`min(scar − restored, ceiling)`.

### rb_prison_deaths — the "apply once" anchor

`PRIMARY KEY (death_key VARCHAR(96))`, stored namespaced as
`player:key` so controller-composed keys never collide across players.
Columns: `score_delta`, `health_penalty`, `created_utc`. Death+respawn
double-fire and relog re-delivery lose the PK and resolve to the typed
no-op — health is never deducted twice.

### rb_prison_sentences — the lifecycle with the live guard

`PRIMARY KEY (sentence_id)`, `UNIQUE (live_guard)` — the portable
partial-unique emulation: `live_guard` carries the player UUID while
the sentence is PENDING/JAILED, NULL after release, so exactly one live
sentence can exist per player while released history rows survive
forever. States: `PENDING` (the durable `pending_imprisonment` retry
state) → `JAILED` (teleport confirmed) → `RELEASED` (+ `release_reason`
∈ PAID/ESCAPE/RESCUE/ADMIN_OVERRIDE), all compare-and-set. The jail
anchor (`jail_world`, `jail_x/y/z`, `jail_yaw/pitch` — integers all the
way down) is stamped from config at creation and never rewritten.

### rb_prison_fees — the frozen §5.9 audit and pay-protocol claim

`PRIMARY KEY (fee_id)`, `UNIQUE (sentence_id)` (one quote per sentence).
Columns carry every §5.9 structured value: `death_score`,
`inflation_factor` (exact decimal string), `multiplier`, `rounding`,
`clamp`, `amount_minor`, then `provider` + `ledger_entry CHAR(36)`
(the economy transaction id) stamped at payment, `quoted_utc`,
`resolved_utc`. States `QUOTED → PAID | VOID`, all CAS. The quote is
frozen at first computation and re-used across retries — it can never
drift for a half-paid release.

### rb_prison_rehab — idempotent task completions

`PRIMARY KEY (player_uuid, task_key, completion_key)` — structural
idempotency per completion identity. Columns: `occurrence INT` (counted
from rows, capped per task), `healed_points INT` (the clamped heal
actually applied — never more than the remaining scars), `created_utc`.

### rb_prison_events — append-only audit

`PRIMARY KEY (event_key VARCHAR(160))` — deterministic fragments:
`death:<player>:<key>` (the death idempotency gate, outcome finalized to
APPLIED inside the transaction), `sentence:<uuid>:created|jailed|released`
(release detail carries actor + note — staff overrides are never
anonymous), `fee:<uuid>:quoted|paid|void`, `rehab:<player>:<task>:<key>`.
Columns: `player_uuid`, `type`, `detail`, `outcome`, `at_utc`.
