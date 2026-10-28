# ADR-0019 — Alchemy persistence layer: derived brewing levels, idempotent XP, prerequisite-gated unlock purchases, player-defined auto-consumption rules

Date: 2026-10-28 · Status: accepted (persistence layer implemented + tested; the
controller slice — potion bags as items, brewing-stand event wiring, inventory
consumption, commands, placeholders, menus — is future work and remains an
interactive/serverful gate)

## Context

Master prompt §5.7 fixes the Phase 8 product contract. Brewing must be "a real
progression system with two distinct paths": a **Power path** ("unlock
stronger effects, longer durations, or advanced recipes at required brewing
levels") and an **Efficiency path** ("lower resource costs, better
storage/access, controlled potion-bag capacity, or safer consumption
automation") where efficiency "must not quietly add combat strength unless
configured as an explicitly balanced effect". The state to persist: "persisted
brewing level/XP, unlocks, recipes, costs, and prerequisites". Potion bags
are "config-driven … with clear inventory safety rules" — those rules are
event work, but bag *definitions* (capacity tiers, unlock gating) are operator
config. Auto-consumption rules are "player-defined … e.g. a chosen potion
triggers below a health threshold, when burning, entering the Nether, or
falling. Each rule must have a whitelist of allowed triggers/effects,
cooldowns, toggle, storage requirement, resource consumption, conflict
handling, priority, and audit/debug reason. Never make an infinitely
replenishing potion bot. Make rules opt-in per player and subject to server
policy". And the atomicity rule: "atomic potion/cost consumption and safe
fallback if a player inventory changes between rule evaluation and execution".

The Phase 8 roadmap line adds: "Add tests for inventory exploit paths, death,
disconnect, potion conflicts, and repeated triggers" — inventory exploit paths
and death/disconnect handling are controller-phase (they need live
inventories); repeated triggers, conflicts and cooldowns are state and land
here.

This slice is the persistence-first groundwork move for Phase 8, the same
move as ADR-0009 (dragons), ADR-0013 (artifacts) and ADR-0016 (RPG): the
`rebellion-alchemy` module joins the build **serverless** — no plugin
descriptor, no listeners, no inventory code, no commands — and the whole
contract above that is *state* (as opposed to feel) is pinned in schema,
service and tests. The module ships nothing installable; the controller
phase will ride this contract.

## Research finding (proven before implementation, per §4)

The potion vocabulary for config is `org.bukkit.potion.PotionType`, **not**
`PotionEffectType`. Proven against the pinned paper-api 26.3 build 226 jar:
`PotionType` is a real enum (`extends java.lang.Enum`) whose static
initializer only computes `NamespacedKey.minecraft(...)` constants — it
resolves in a bare JVM with the jar on the classpath (46 constants, valueOf
works, unknown names throw `IllegalArgumentException`; jshell probe with
paper-api + adventure + guava on the classpath). `PotionEffectType` is the
opposite: an abstract class whose static fields are **registry-bound
instances** (`static {}` block consults the Bukkit registry — javap), so any
serverless use would crash or depend on a bootstrapped server — the
ADR-0015 lesson. Consequence: this layer validates, stores and stamps
`PotionType` names (enum-constant lookup, registry-free — the same
discipline as `Material` in teams/rpg and `EntityType` in rpg); the
controller resolves `PotionType` → live `PotionEffectType` at runtime where
the registry exists.

## Decision

### 1. Five tables under module key `alchemy`, every mutation a CAS or PK-guarded insert

- `rb_alchemy_progress` — `PRIMARY KEY (player_uuid)`, columns `total_xp
  BIGINT`, `updated_utc BIGINT`. **The level is derived, not stored**: a pure
  `Leveling` function walks the configured curve from `total_xp`, exactly the
  way tracking expiry is derived (ADR-0016) — no level column can drift, no
  sweep can forget to recompute it, and a config curve change re-derives
  every player's level on the next read.
- `rb_alchemy_unlocks` — `PRIMARY KEY (player_uuid, unlock_key)`. The unlock
  ledger. Idempotency is structural (the duplicate INSERT loses the PK);
  there is no UPDATE path — an unlock, like a discovery, is a fact. Columns:
  `granted_utc BIGINT`, `claim_id CHAR(36) NULL` (the purchase claim that
  paid for it; null for admin grants).
- `rb_alchemy_rules` — `PRIMARY KEY (rule_id)`, the player-defined
  auto-consumption rules. Columns: `player_uuid`, `trigger VARCHAR(16)` ∈
  {HEALTH_BELOW, BURNING, NETHER_ENTRY, FALLING}, `potion_type VARCHAR(32)`
  (the chosen potion, validated against the policy whitelist),
  `threshold INT NULL` (only HEALTH_BELOW carries one — a percent bound),
  `cooldown_seconds INT`, `priority INT` (lower = evaluated earlier; ties
  broken by `created_utc`, then `rule_id` — deterministic conflict handling),
  `storage VARCHAR(16)` ∈ {BAG, INVENTORY, ANY} (the storage requirement:
  where the rule may draw its potion from), `enabled` (the toggle),
  `created_utc`, `updated_utc`, `removed_utc BIGINT NULL` (deleted rules
  stop resolving but are kept for forensics), `last_fired_utc BIGINT NULL`
  (**the cooldown authority** — stamped only by a committed execution).
- `rb_alchemy_claims` — the transactional anchor (§3). Generalized over
  RPG's claims with a `purpose` discriminator: `UNLOCK_PURCHASE` or
  `AUTO_CONSUME`, plus `subject_key` (the unlock key or the rule id as a
  string). `state` is `RESERVED → COMMITTED | RELEASED`, all transitions
  CAS.
- `rb_alchemy_events` — append-only audit: `event_key` PK composed of
  deterministic server-side fragments (`xp:<player>:<source>`,
  `claim:<uuid>:commit`, `claim:<uuid>:release`, `rule:<uuid>:<action>`,
  `unlock:<player>:<key>`). Idempotent by construction; written inside the
  same transaction as the mutation it describes. The XP event's `outcome`
  column carries `LEVEL_UP` with the `from→to` detail — the durable answer to
  "audit/debug reason".

### 2. XP: idempotent per source, atomic accumulate, level derived

`awardBrewXp(player, amount, sourceKey)` is one transaction:

1. **Idempotency first**: insert the audit event `xp:<player>:<sourceKey>`.
   A duplicate source (a retried brew-completion event, a plugin
   re-delivery) loses the PK and resolves to the typed no-op
   `XP_NOOP` **carrying the player's current progress** — nothing was
   double-awarded, and the caller learns the live state.
2. First-ever award inserts the progress row; later awards use the atomic
   accumulate `UPDATE … SET total_xp = total_xp + ?` (single-statement
   read-modify-write — no check-then-set race). The first-row INSERT race
   (two concurrent first awards) is resolved by classifying the PK violation
   and falling through to the UPDATE inside the same transaction.
3. Pre-read and post-read `total_xp` bracket the accumulate; the pure
   `Leveling.levelFor` of both gives the `from→to` pair. The event outcome is
   `LEVEL_UP` when they differ, else `XP`.

`sourceKey` is the controller's deterministic brew identity (stand location,
contents hash, time bucket — controller-phase composition); persistence only
requires the `[a-z0-9_:\-]{1,96}` grammar and enforces the idempotency. The
curve itself (`level-xp-base`, `level-xp-growth`, `max-level`) is config;
`Leveling` is pure and unit-tested: `cost(level) = base + (level-1)·growth`,
levels clamp at `max-level`, and `totalXpForLevel` inverts the walk for
display. "Never make an infinitely replenishing potion bot" is answered by
the policy floor (§4) plus the rule-row cooldown stamp — not by XP, which
only gates *what* may be brewed stronger.

### 3. Unlock purchases: reserve → (inventory) → commit, prerequisites answered from rows

The claim protocol is the ADR-0016 move, because cost ingredients live in an
inventory (controller-owned memory) while unlock state lives in the database
— two stores that cannot share a transaction:

1. **`reserveUnlock(player, key, digest)`** — one transaction: definition
   exists + enabled; **level gate** (the *derived* level from the progress
   row must meet `required-level`); **prerequisites** (every referenced
   unlock key must already be in the player's unlock ledger — answered from
   rows, not from config); **not already owned** (PK probe); **single
   reservation** per (player, key) — the cost-race guard. INSERT the
   RESERVED claim carrying the controller's `cost_digest`.
2. *Controller phase:* remove the physical cost items. Failure →
   `releaseReservation` (typed refund path). The controller never removes
   cost items without a live reservation in hand.
3. **`commitUnlock(claim, …)`** — one transaction: re-read the claim
   (RESERVED, owned by the caller), CAS `RESERVED → COMMITTED` in the same
   transaction as the PK-guarded unlock insert and the audit event.
   All-or-none. Re-delivery resolves through the CAS to the typed no-op
   carrying the original outcome read back from the event row.

`releaseStaleClaims(olderThan)` is the crash-recovery sweep (identical
discipline to ADR-0016): RESERVED claims older than the floor are released
in one CAS; the digest on the row is the operator's refund evidence.

### 4. Auto-consumption: policy-gated definition, ownership-guarded mutation, execution claims

- **Server policy first** (`auto-rules` config family): per-trigger enable
  flags (the *whitelist of allowed triggers*), per-trigger threshold bounds
  for HEALTH_BELOW, `min-cooldown-seconds` (the anti-bot floor every player
  rule must respect), `min/max-priority`, `max-rules-per-player`, and the
  `allowed-potion-types` whitelist (PotionType enum-validated — the
  *whitelist of allowed effects*). Nothing outside the whitelist reaches a
  rule row.
- **`defineAutoRule`** — one transaction: trigger enabled by policy, potion
  whitelisted, threshold within the trigger's bounds, cooldown ≥ floor,
  priority in range, storage scope valid, per-player cap counted from live
  rows. Idempotent by `rule_id` PK (a retried define resolves to the typed
  no-op carrying the existing row). Every define/toggle/remove writes an
  event with the caller-supplied reason — the audit trail the master prompt
  demands.
- **`toggleAutoRule` / `removeAutoRule`** — ownership-guarded CAS on the
  live (non-removed) row. Removal stamps `removed_utc` instead of deleting
  — forensics survive.
- **`reserveAutoConsume(player, rule, digest)`** → controller drinks/removes
  the potion → **`commitAutoConsume(claim)`**: the reserve validates the
  rule (owned, enabled, not removed, **cooldown passed** — `last_fired_utc
  + cooldown ≤ now`, answered from the row) and inserts the claim; the
  commit CASes the claim **and stamps `last_fired_utc`** in one
  all-or-none transaction. A crash after the potion was consumed but before
  commit leaves a releasable claim (safe fallback — the cooldown was never
  bought, so the worst case is the player may re-trigger, never a lost
  potion-bot window); a crash after commit means the cooldown is durable.
  This is the §5.7 "atomic potion/cost consumption and safe fallback"
  answer for everything that is state.

Conflict handling is the deterministic order: `rulesFor(player)` returns
enabled, non-removed rules sorted by (priority, created_utc, rule_id); the
controller evaluates at most one rule per trigger per cycle in that order.
Ties can never be ambiguous.

### 5. Config: four definition families, every bound validated, collect-all issues

`AlchemyConfig` (immutable record, whole-record reload swap — the ADR-0004
pattern) carries four families from an `alchemy.` YAML shape:

- **brewing** — the curve scalars (`max-level` 1..1000, `level-xp-base`
  1..1,000,000, `level-xp-growth` 0..1,000,000) and `xp-per-brew` (the
  controller's default award). Honest defaults: max level 30, base 100,
  growth 25, 10 XP per completed brew.
- **unlocks** — keyed definitions: `kind` ∈ {POWER, EFFICIENCY};
  POWER carries `potion-type` (enum-validated), `amplifier-bonus` 0..4
  (vanilla amplifier ceiling) and `duration-multiplier` 1.0..5.0; EFFICIENCY
  carries `cost-reduction` 0..<1.0 (a 100% reduction is rejected — it
  mints free potions), `bag-capacity-bonus` 0..54 and
  `auto-cooldown-reduction` 0..3600 s (both floored by the policy at
  evaluation). Common: `enabled`, `required-level` 1..max-level (cross-family
  bound check), `prerequisites` (must reference existing keys; **cycles are
  rejected** — a cycle would make every member permanently unpurchasable),
  `cost` ingredients (valid item materials, 1..64 each). Defaults ship one
  modest POWER unlock and one modest EFFICIENCY unlock, both cheap and
  mid-level — a stock install is playable but nothing is free.
- **bags** — keyed capacity tiers: `enabled`, `capacity` 1..54,
  `material` (a valid item material — the bag item the controller mints),
  optional `required-unlock` (must reference an existing unlock key; empty =
  open to everyone). Defaults: a 9-slot `satchel` with no gate and an
  18-slot `satchel_large` gated on the efficiency unlock. Bag *contents*
  are item PDC (controller phase); definitions are all this layer needs.
- **auto-rules** — the §4 policy family. Defaults: HEALTH_BELOW enabled
  (threshold 1..99), BURNING enabled, NETHER_ENTRY enabled, FALLING
  disabled (deliberately opt-in — fall-clutch potions are a feel decision),
  `max-rules-per-player` 5, `min-cooldown-seconds` 60, priority range
  1..100, `allowed-potion-types` a conservative survival set (HEALING,
  REGENERATION, FIRE_RESISTANCE, SWIFTNESS, STRENGTH, NIGHT_VISION,
  WATER_BREATHING, SLOW_FALLING).

### 6. Deliberately not in this slice

The controller phase owns: potion-bag item minting/opening and every
inventory safety event (nesting, shift-click, death, crafting, drag, cursor,
close — §5.7's "clear inventory safety rules" are live-inventory listeners),
brewing-stand event wiring (BrewEvent fuel/results and where sourceKeys
come from), physical potion removal on auto-consume, live trigger evaluation
(health reads, fire ticks, portal entry, fall distance), commands,
`%rebellion_brewing_level%` and related placeholders, menus, and the live
PotionType→PotionEffectType mapping. Nothing here boots on a server — like
ADR-0009/0013/0016, the module ships no `plugin.yml` and is exercised
through its tested service surface only.

## Consequences

- "Atomic potion/cost consumption" is honest: the only sanctioned execution
  ordering is reserve (DB) → drink/remove (inventory, under a live
  reservation) → commit (DB, stamping the cooldown all-or-none with the
  audit), and every deviation resolves to a typed, recoverable state
  (RELEASED with a digest, or COMMITTED with a durable cooldown stamp).
- Idempotency is structural everywhere it matters: XP by event PK, unlock
  grants by unlock PK, claim transitions by CAS, rule defines by rule PK,
  audit events by composed PK.
- Levels can never drift: derived from `total_xp` through a pure function
  every read; a curve rebalance applies on the next read with zero
  migration.
- Auto-consumption can never become a potion bot: policy floor at define
  time, whitelisted triggers and potions, per-player cap, deterministic
  conflict order, and a cooldown stamp that only a committed consumption
  can buy.
- Testing: `AlchemyServiceIT` against real SQLite through Core's actual
  `DatabaseService` — XP idempotency (double-award no-op, first-row race
  classification), derived level-ups with the clamp, the unlock purchase
  protocol (refusal families, refund release, all-or-none commit, re-delivery
  no-op, stale sweep), the full auto-rule lifecycle (policy refusals, cap,
  toggle/remove ownership guards, idempotent define), the auto-consume claim
  protocol (cooldown refused until committed, re-delivery no-op, stale
  release) and the restart round-trip. `AlchemyConfigTest` pins the operator
  contract: every bound above, key grammar, cycle rejection, cross-family
  references, PotionType whitelist validation, the shipped defaults.
- The module ships **no plugin descriptor** (ADR-0009 precedent): installing
  it as a server plugin is not a supported state until the controller phase
  lands.
