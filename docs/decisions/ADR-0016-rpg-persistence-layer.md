# ADR-0016 — RPG persistence layer: reserved ritual claims, idempotent discoveries, precision-stamped tracking, mask effects, encounter ledger

Date: 2026-10-28 · Status: accepted (persistence layer implemented + tested;
the controller slice — soul entities, inventory ingredient removal, signal
rendering, commands, placeholders, menus — is future work and remains an
interactive/serverful gate)

## Context

Master prompt §5.3 fixes the Phase 7 product contract. A **low-frequency
rare-soul encounter system** must be "a configurable, deterministic and
debuggable encounter state machine, not random unchecked entity spawning":
spawn conditions (world/biome/time/player-count), maximum concurrent
encounters, cooldown, cleanup and reward table are all operator config.
A player who helps a rare soul **discovers a ritual recipe or knowledge
flag**, persisted against UUID and "recorded idempotently". A **tracking
ritual** reveals information about a specifically selected player — with
configured ingredients, preparation, radius/range, duration, cooldown, cost,
precision, countermeasures and failure chance; names must resolve to known
UUIDs with defined offline/unknown behavior; tracking "must not reveal more
precision than configured", must support "imperfect direction/range signals
and counter-rituals to make tracking contestable", and ritual attempts must
be rate-limited with ingredient duplication/race conditions prevented. The
hard persistence rule: "Ritual state changes and consumed ingredients must be
committed as one logical operation. If a database write fails, fail closed
and refund safely or leave the player with a clearly reported recoverable
state; never consume ingredients and falsely report that a ritual succeeded."

The Phase 7 roadmap line adds: "progression state, and the related menu/lore
flows. Ensure unique reward claims and ingredient consumption are
transactional/idempotent."

This slice is the ADR-0009/ADR-0013 groundwork move for Phase 7: the
`rebellion-rpg` module joins the build **serverless** — no plugin descriptor,
no listeners, no entities, no inventory code, no commands — and the whole
contract above that is *state* (as opposed to feel) is pinned in schema,
service and tests. Like those ADRs, the module ships nothing installable;
the controller phase will ride this contract.

## Decision

### 1. Six tables under module key `rpg`, every mutation a CAS or PK-guarded insert

- `rb_rpg_discoveries` — `PRIMARY KEY (player_uuid, discovery_key)`. The
  discovery ledger. Idempotency is structural: an INSERT that hits the PK
  loses, and the loser resolves to the typed no-op `ALREADY_KNOWN`. There is
  no UPDATE path — a discovery is a fact, never edited.
- `rb_rpg_claims` — the transactional anchor (§2). `claim_id` UUID is the
  idempotency key; `state` is `RESERVED → COMMITTED | RELEASED`, all
  transitions compare-and-set.
- `rb_rpg_tracking_sessions` — one row per successfully cast tracking
  ritual. `claim_id` is UNIQUE: one session per claim, ever. `precision` is
  the tier the config granted at cast time, stamped on the row. Expiry is
  **derived** (`expires_utc`), not swept into a state column; a counter-
  ritual revocation is a separate nullable stamp with its own CAS.
- `rb_rpg_effects` — time-bounded player effects; the persistence half of
  counter-rituals. The in-slice effect is `masking` (a mask on the *target*
  degrades incoming tracking precision by one tier). Lookup is
  `expires_utc > now`; rows are kept after expiry for forensics.
- `rb_rpg_encounters` — the encounter ledger: config-defined key, spawn
  site, `ACTIVE → COMPLETED | EXPIRED | DESPAWNED`, nullable soul entity id
  (attached by the controller once an entity exists), and the completing
  player. Max-concurrent and cooldown are enforced *through this table*
  inside the begin-encounter transaction, so the state machine is the
  authority, not a scheduler's memory.
- `rb_rpg_events` — append-only audit: `event_key` PRIMARY KEY composed of
  deterministic server-side fragments (`claim:<uuid>:commit`,
  `encounter:<uuid>:complete`, `discovery:<player>:<key>`). Idempotent by
  construction; written inside the same transaction as the mutation it
  describes.

### 2. The claim protocol: reserve → (inventory) → commit, all-or-none

The ingredient-consumption rule is honored with a two-phase claim rather
than a pretend-single-step write, because ingredients live in an inventory
(in-memory, controller-owned) while ritual state lives in the database —
two stores that cannot share a transaction. The database side is the
authority and goes first:

1. **`reserveRitual(player, ritual, digest)`** — one transaction: definition
   exists + enabled; **rate limit** (attempts of *any* claim state for
   player+ritual within the configured window — released attempts still
   count, deliberately: anti-abuse stays conservative); **cooldown** (the
   latest COMMITTED claim for player+ritual must be older than the
   configured cooldown — a released reservation never bought a cooldown);
   **single reservation** (no RESERVED claim may already exist for
   player+ritual — this is the ingredient-race guard: two simultaneous
   casts over one stack of reagents cannot both hold a reservation).
   INSERT the RESERVED claim carrying an `ingredients_digest` — the
   controller's snapshot of exactly what it verified and intends to remove.
2. *Controller phase:* remove the physical ingredients. Failure →
   `releaseReservation` (typed refund path). The controller never removes
   ingredients without a live reservation in hand — that ordering is what
   makes "never consume ingredients and falsely report success" structural.
3. **`commitTracking | commitCounterMask | commitKnowledge(claim, …)`** —
   one transaction: re-read the claim (RESERVED, owned by the caller), then
   CAS `RESERVED → COMMITTED` **in the same transaction as** the effect of
   the ritual (session row / effect row / discovery row) and the audit
   event. All-or-none: a crash before commit leaves a releasable
   reservation; after commit the outcome rows are durable together. A
   **commit-time permanent refusal** (tracking session cap) auto-releases
   the claim in that same transaction — the reservation can never succeed,
   so it converts to the refund state immediately.

Re-delivery (a retried commit, a duplicate event) resolves through the CAS:
the second call finds the claim no longer RESERVED and returns a typed
no-op **carrying the original outcome** (read back from the event row).
`releaseStaleReservations(olderThan)` is the crash-recovery sweep:
RESERVED claims older than the floor are released in one CAS; the digest
on the row is the operator's refund evidence for the rare window where the
server died after inventory removal but before commit — a *clearly
reported recoverable state*, not a silent loss.

The **failure roll** (configured `failure-chance`) is drawn from an
injected `DoubleSupplier` before the commit transaction and parameterizes
it: a losing roll still commits the claim (ingredients spent, cooldown
bought — failure is a cost, which is what makes tracking contestable) but
writes no session row; the event row records outcome `FAILED`. The
outcome is durable, so retries resolve to the original result.

### 3. Tracking precision: granted tiers, mask degradation, derived expiry

`Precision` is an ordered enum — `DIMENSION` (target's world only) →
`REGION` (world + coarse direction + distance bucket) → `COORDINATE`
(exact block) — the *persistence* representation of "must not reveal more
precision than configured". The granted tier is stamped on the session at
cast time; the controller renders a signal of **at most** that tier
(imperfect direction/range signals are controller feel). Composition is
service-side and pure over persistence: an active `masking` effect on the
target **degrades the effective tier by exactly one step**
(COORDINATE→REGION→DIMENSION; a masked DIMENSION track resolves to
*nothing*). Masks do not stack — a second mask renews/extends protection
rather than compounding, so a tracker always retains a floor signal
unless they started at DIMENSION. Offline targets are legal sessions (the
row records the resolved UUID); "unknown name" never reaches persistence —
the controller resolves names to known UUIDs first and releases the
reservation on failure. Session expiry is `expires_utc`-derived, so there
is no sweep to forget to run: an expired session stops resolving the
moment time passes it, crash or no crash.

### 4. Encounters: begin/attach/complete/expire, rewards ride the winner's CAS

- `beginEncounter(key, world, x, y, z)` — one transaction: definition
  checks (exists, enabled, world allowed), **concurrency cap** (count of
  ACTIVE rows for the key under the configured maximum), **encounter
  cooldown** (latest resolved row for the key + configured cooldown
  elapsed). Typed refusals; never an unbookkept spawn.
- `attachSoul(encounterId, entityUuid)` — the controller records the
  spawned entity (CAS on `ACTIVE` + `soul_entity IS NULL`); idempotent.
- `completeEncounter(encounterId, player)` — one transaction: CAS
  `ACTIVE → COMPLETED` with `completed_by` — exactly one winner; every
  other helper resolves to `ENCOUNTER_ALREADY_RESOLVED` and **earns
  nothing** (unique reward claims are the CAS plus the discovery PK).
  The winner's transaction inserts the configured reward discoveries
  (each idempotent by PK) and the audit event.
- `expireEncounters(now)` — the cleanup half of §5.3 line 1: ACTIVE rows
  past `began_utc + ttl` CAS to EXPIRED; re-runs are no-ops.

Entity spawning, biome/time/player-count *evaluation* (they need a live
world), NPC integration choice and quest feel are controller phase; the
ledger enforces every rule that is state, which is what makes the future
spawner "deterministic and debuggable" — every decision it will make is
answerable from these rows.

### 5. Config: two definition families, every bound validated, collect-all issues

`RpgConfig` (immutable record, whole-record reload swap — the ADR-0004
pattern) carries the two operator-owned families from a `rituals` /
`encounters` YAML shape (split per master prompt §11: the concepts deserve
independent editing):

- **Ritual definitions** — key (`[a-z0-9_]{1,32}`), enabled, kind
  (`TRACKING` | `COUNTER_MASK` | `KNOWLEDGE`), ingredient list (valid
  item material, amount 1..64; registry-free enum validation — the
  ADR-0015 serverless lesson), cooldown 0..30 days, rate-limit attempts
  1..100 / window 1..86400 s, effect duration 30..86400 s (sessions
  shorter than 30 s are config nonsense), tracking precision tier,
  failure chance 0.0..<0.95 (a guaranteed-fail ritual is rejected —
  nothing is learned from a ritual that can never succeed), tracking
  session cap 1..10.
- **Encounter definitions** — key, enabled, allowed worlds (non-empty),
  biomes (empty = any, controller-evaluated), time window
  (`DAWN|DAY|DUSK|NIGHT|ANY`), min/max online players (0 = uncapped),
  max concurrent 1..10, cooldown 0..30 days, TTL 60..86400 s, reward
  discovery keys (non-empty, same key grammar).

Defaults ship honest and conservative: one enabled `tracking` ritual and
one enabled `mask` counter-ritual with modest ingredients, and one
`wandering_soul` encounter **disabled by default** — its spawn rules are
world data the operator must calibrate on import (the wards
calibrated-scale precedent: nothing auto-spawns from a stock install).

### 6. Deliberately not in this slice

The controller phase owns: soul entity spawning + NPC choice, quest/event
interaction, inventory consumption mechanics, signal *rendering*
(compass/particles/text feel), preparation/`cost` surfaces that need a
live player (XP, economy — the latter is Phase 9 anyway), commands,
`%rebellion_ritual_<key>_discovered%` placeholders, menus/lore flows, and
every live-world spawn-condition evaluation (biome/time/player-count).
Nothing here boots on a server — like ADR-0009/0013, the module ships no
`plugin.yml` and is exercised through its tested service surface only.

## Consequences

- "One logical operation" is honest: the only sanctioned consumption
  ordering is reserve (DB) → remove (inventory, under a live reservation)
  → commit (DB, all-or-none with the ritual's effect rows + audit), and
  every deviation resolves to a typed, recoverable state (RELEASED with a
  digest, or COMMITTED with durable outcome rows).
- Idempotency is structural everywhere it matters: discovery by PK, claim
  transitions by CAS, sessions by UNIQUE claim, events by composed PK,
  encounter rewards by the completion CAS + discovery PK.
- Tracking can never over-reveal: the granted tier is stamped at cast and
  only ever degrades (mask), and expired/revoked sessions stop resolving
  by derivation.
- Encounter rules live in the ledger, so the future spawner inherits
  debuggability: every cap/cooldown decision is reconstructable from rows.
- Testing: `RpgServiceIT` against real SQLite through Core's actual
  `DatabaseService` — the claim protocol end-to-end (reserve/refusal
  families, release, commit success/failure-roll/no-op, session cap
  auto-release, stale sweep), mask degradation composition, derived
  expiry, discovery idempotency, the encounter lifecycle with one-winner
  rewards and restart round-trips. `RpgConfigTest` pins the operator
  contract: every bound above, key grammar, duplicate-key refusal,
  unknown-kind refusal, the disabled encounter default.
- The module ships **no plugin descriptor** (ADR-0009 precedent):
  installing it as a server plugin is not a supported state until the
  controller phase lands.
