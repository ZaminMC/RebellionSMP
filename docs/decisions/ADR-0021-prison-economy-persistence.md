# ADR-0021 — Phase 9 persistence groundwork: the internal economy ledger and the prison state machine

Date: 2026-10-28 · Status: accepted (persistence layers implemented + tested;
the controller slice — death listener wiring, prison structure/teleports, NPC
guards, escape objectives, commands, placeholders, menus — is future work and
remains an interactive/serverful gate)

## Context

Master prompt §5.8 fixes the prison product contract. Deaths are the entry
point: "Every valid player death is routed into the global prison system
according to configuration", with a health penalty and a death-score change
applied "once" — "Do not deduct health twice because death and respawn events
both fire or because the player logs back in." A failed teleport must retain
"a durable `pending_imprisonment` state and retry safely on respawn/join."
Release has four routes: escape objectives, external rescue, "a very
expensive paid release route" with the fee formula
`death_score × inflation_factor × 5`, and auditable staff overrides that
"must not mint currency/items or clear another player's debt silently."
Health "does not instantly revert" on release — rehabilitation restores
"health scars" idempotently with "no inventory/database race that grants
double healing."

§5.9 fixes the money contract: "a small, explicit economy service" with
"atomic ledgers, UUID account identifiers, idempotency keys, exact
decimal/integer units, transaction reasons, and audit history", a shared
provider/adapter so nothing "hard-cod[e] a third-party economy", fees
"logged as structured values: score, inflation factor, multiplier,
rounding, result, provider, and transaction ID", and the overflow rule:
"never use unchecked integer multiplication that can overflow."

This slice is the persistence-first groundwork move for Phase 9, the same
move as ADR-0009 (dragons), ADR-0013 (artifacts), ADR-0016 (RPG) and
ADR-0019 (alchemy): two new modules join the build **serverless** — no
plugin descriptors, no listeners, no teleports, no commands — and the whole
contract above that is *state* is pinned in schema, service and tests. The
controller phase rides this contract.

## Decision

### 1. The cross-module money contract lives in rebellion-api

`net.zaminmc.rebellion.api.economy.EconomyProvider` (+ `EconomyResult`,
`EconomyOutcome`) is the shared provider §5.8/§5.9 demand: `balance`,
`debit`, `credit`, all in exact **long minor units**, all atomic and
idempotent per `(account, idempotencyKey)`. The internal
`rebellion-economy` module implements it; a future optional Vault bridge
would implement the same interface and register with a different
`providerId()` — callers (prison today, shops never unless requested) bind
to the contract only. The interface javadoc is binding on every
implementation: fail-closed refusals, never a negative balance, never a
hot-path call (§5.9's main-thread rule is the caller's duty).

### 2. rebellion-economy: two tables under module key `economy`, the ledger row as idempotency anchor

- `rb_economy_accounts` — `PRIMARY KEY (account_uuid)`, `balance BIGINT`
  minor units. Rows mutate only through guarded accumulates inside
  mutation transactions; a configured `max-balance` (validated ≤ 2^62)
  plus a single-mutation ceiling (2^40) make every addition
  overflow-safe by construction.
- `rb_economy_ledger` — `PRIMARY KEY (entry_id)`, `UNIQUE (account_uuid,
  idempotency_key)`: the append-only audit history AND the idempotency
  gate in one row (the ADR-0019 discipline: the anchor writes first). A
  replayed key loses the constraint and resolves to the typed successful
  no-op **carrying the original entry id and its recorded
  `balance_after`** — a caller that crashed between charge and its own
  commit retries the same key and reconciles without a second charge.
  `balance_after` pins the exact post-mutation balance, so any account
  can be audited from the ledger alone.

One mutation = one transaction: probe (replay → typed no-op) → read
balance → invariant check (never negative, never above max; refusal rolls
back clean, no rows) → insert ledger row → insert-or-accumulate the
account. All-or-none. The named concurrency race — two identical
(account, key) mutations committing concurrently — is caught by the
constraint, rolls the loser back, and resolves on a single bounded retry
as the replay it is.

Deliberately NOT here: transfers, shops, salaries, faucets (§5.9: "do not
create an unrequested sprawling shop/auction economy"). Balances move
only through the audited provider calls.

### 3. rebellion-prison: six tables under module key `prison`

- `rb_prison_profiles` — `PRIMARY KEY (player_uuid)`: `death_score
  BIGINT`, `scar_points INT`, `restored_points INT`. Both scar columns
  are **write-clamped inside single UPDATE statements** (portable CASE
  expressions — `MIN`/`LEAST` differ across dialects): scars can never
  exceed the configured active-penalty ceiling (max-health − floor);
  restoration can never exceed scars. The **active penalty is derived**
  (never stored, never swept) exactly like derived brewing levels; the
  deaths table keeps the full forensic history, so clamping the active
  ledger loses nothing.
- `rb_prison_deaths` — `PRIMARY KEY (death_key)`, stored namespaced as
  `player:key`: the §5.8 "apply once" anchor. The controller composes
  one deterministic key per physical death; death+respawn double-fire
  and relog re-delivery resolve to the typed no-op carrying the live
  profile.
- `rb_prison_sentences` — `PRIMARY KEY (sentence_id)` with the portable
  partial-unique emulation: `live_guard CHAR(36) NULL` carries the
  player UUID while PENDING/JAILED and is nulled on release;
  `UNIQUE (live_guard)` therefore enforces **exactly one live sentence
  per player** (a death in prison still scores and scars, but can never
  book a second sentence) while unlimited released history rows
  survive. The jail anchor (world name + block coords + facing,
  integers all the way down) is stamped from config at creation — a
  config change never rewrites an existing sentence's destination.
- `rb_prison_fees` — `PRIMARY KEY (fee_id)`, `UNIQUE (sentence_id)`,
  states `QUOTED → PAID | VOID` (all CAS). The row IS the §5.9 audit
  AND the claim of the pay protocol: score, factor (exact string),
  multiplier, rounding, clamp, amount, then provider + ledger entry
  (transaction id) stamped at payment.
- `rb_prison_rehab` — `PRIMARY KEY (player_uuid, task_key,
  completion_key)`: structurally idempotent completions with their
  clamped heal amount and occurrence number.
- `rb_prison_events` — append-only audit keyed by deterministic
  fragments (`death:<player>:<key>`, `sentence:<uuid>:jailed`,
  `fee:<uuid>:paid`, `rehab:<player>:<task>:<key>` …), idempotent by
  construction, written inside the same transaction as its mutation.

### 4. Death processing: one transaction, one verdict

`applyDeath(player, deathKey)` inserts the event gate first (replay →
typed no-op), then in the same transaction: the forensic death row, the
score accumulate + scar clamp (one statement), and the routing verdict —
`NEVER` (scores, never jails), `SCORE_THRESHOLD` (jails when the
accumulated score reaches the threshold), `ALWAYS` (the shipped default)
creating the durable **PENDING sentence** — the §5.8
`pending_imprisonment` state the controller confirms into JAILED after
the teleport lands (`confirmJailed` CAS; idempotent re-confirm is a typed
no-op) and retries on respawn/join while it stays pending. Routing
refusals never block the death itself: the score and scars always apply.

### 5. The release fee: a claim protocol over the frozen quote

`payRelease` is the one deliberately **blocking** orchestration — it
spans three stores in time (prison rows → the provider's ledger → prison
rows), so it joins two bounded persistence round-trips on the caller's
thread; the javadoc pins the caller's thread duties (off the main thread
per §5.9, never a persistence-executor callback). The protocol:

1. The live sentence must be JAILED (PENDING must be confirmed or
   reverted first).
2. **Find-or-freeze** the QUOTED fee row for the sentence. The quote is
   computed once by the pure `FeeMath` — `death_score × factor ×
   multiplier` in `BigDecimal` (never unchecked long math), explicit
   rounding mode, then clamped into the configured `[min, max]` bounds
   *in arbitrary-precision space before any long conversion* — that is
   the §5.9 overflow answer: a hostile score or fat factor cannot
   overflow, the clamp fires first. Once written, the quote is frozen:
   retries never recompute, so a death in prison between quote and
   payment cannot inflate a half-paid release.
3. The provider debit under the idempotency key `prison-fee:<feeId>`
   with reason `prison.release-fee`. `INSUFFICIENT_FUNDS` surfaces typed
   carrying the unchanged quote — the row stays QUOTED and reusable.
   Any other provider refusal surfaces `PROVIDER_REFUSED`.
4. One all-or-none transaction: CAS the fee QUOTED→PAID (stamping
   provider id + ledger entry) together with the sentence JAILED→
   RELEASED(PAID) plus both audit events.

Every crash window reconciles: crash after (2) leaves a reusable quote,
no money moved; crash after (3) leaves the debit durably charged under
the fee's key — the retry's debit resolves as the typed no-op carrying
the original entry id and the commit finishes with that id; money moved
exactly once. `PAID_NOOP` answers the concurrent-race window inside (4).

### 6. Non-paid releases and staff overrides: recorded, never silent

`markReleased(player, ESCAPE|RESCUE|ADMIN_OVERRIDE, actor, note)` CASes
the live sentence out, voids any frozen quote (CAS QUOTED→VOID), and
writes the audit with the actor and note. `ADMIN_OVERRIDE` **requires a
non-null staff UUID** — anonymous overrides are refused (§5.8). The
death score is never touched: an override ends the sentence on the
record; the debt ledger is a fact, not a sentence property.
`revertPending` is the fail-closed recovery twin for a stuck PENDING
sentence (missing prison world, misconfiguration): same CAS family,
same mandatory actor, same audit.

### 7. Rehabilitation: structural idempotency, clamped healing

`completeRehab(player, taskKey, completionKey)`: the event insert is the
gate (replay → typed no-op); occurrences are counted from rows and
capped per task (`REHAB_EXHAUSTED`); the heal is `min(task.healPoints,
scar − restored)` applied through the clamped single-statement
restoration — double healing is impossible by structure, over-healing is
impossible by the CASE guard, and config-validation caps every task's
heal at the active-penalty ceiling. Refusals roll back traceless.

### 8. Config: four families, every bound validated, collect-all issues

`PrisonConfig` (immutable record, whole-record reload — ADR-0004) reads
an `prison.` YAML shape: **death** (score-per-death, health-penalty
points, hard floor, vanilla max-health reference, route, threshold —
floor must stay strictly below the bar), **release** (exact-decimal
factor ≤ 6 fractional digits and bounded, multiplier 1..100, any
`java.math.RoundingMode` name, min/max clamps with max ≤ 2^62),
**rehab.tasks** (keyed, enabled, heal bounded by the ceiling, occurrence
cap), **prison** (the jail anchor: world-name grammar, |x|/|z| ≤ 30M, y
∈ −2048..4096, yaw/pitch bounded). Defaults ship the product contract:
every death jails, 1 heart per death down to a 3-heart floor, the exact
§5.8 formula at factor 1.00 × 5, two rehab objectives.
`EconomyConfig` reads the `economy.` shape: currency display identity
(name/symbol/scale) and the `max-balance` bound (≤ 2^62, the overflow
ceiling); a missing section falls back to shipped defaults silently —
only present-but-invalid values collect issues.

### 9. Deliberately not in this slice

The controller phase owns: the death listener (what a "valid player
death" is, where deathKeys come from), respawn/join retry wiring, prison
structure generation/anchoring in a live world, NPC guards, escape
objectives, external-rescue mechanics, teleport + confirmJailed wiring,
max-health attribute application, commands (`/prison status|escape`,
staff inspect/unlock/recover), placeholders, menus, the Vault bridge
module (if ever requested), and the economy admin grant surface. Nothing
here boots on a server — both modules ship no `plugin.yml` and are
exercised through their tested service surfaces only (ADR-0009
precedent, sixth serverless slice).

## Consequences

- "Apply once" is structural: death rows, rehab rows and audit events are
  all PK-guarded; sentence and fee transitions are CAS; a balance never
  moves without its ledger row.
- The §5.9 audit is complete and frozen: every fee row reproduces the
  exact calculation (inputs, rounding, clamp, result) plus the provider
  identity and the durable transaction id of the charge.
- The one-live-sentence invariant is a database constraint, not
  application discipline — death loops inside prison can never stack
  sentences, and release routes can never double-free.
- The overflow rule holds three ways: BigDecimal fee math with
  clamp-before-conversion; an economy max-balance validated ≤ 2^62 with a
  2^40 single-mutation ceiling; write-clamped scar ledgers.
- Staff power is audited, never silent: overrides without an actor are
  refused; overrides never clear death scores.
- Testing: `EconomyServiceIT` (real SQLite through Core's actual
  DatabaseService) pins replay no-ops carrying original entry ids,
  refusal families leaving no rows, the ledger-reconstructs-balance
  audit walk, the first-mutation race, and the restart round-trip.
  `PrisonServiceIT` injects the **real** economy service as the
  provider — the cross-module fee contract is proven over both modules'
  actual persistence — and pins death idempotency, the scar ceiling,
  all three routes, death-in-prison, confirm idempotency, staff-actor
  enforcement, the frozen-quote protocol (insufficient funds keeps the
  quote, the crash-window reconciliation through the debit key, the
  all-or-none commit), quote-voiding on other release routes with debt
  intact, rehab idempotency/clamping/caps, and the restart round-trip.
  `PrisonConfigTest` pins every bound, grammar and default;
  `EconomyConfigTest` pins the currency/bounds families; `FeeMathTest`
  pins the formula, rounding modes, and clamp-before-conversion.
- Full suite: **606 tests / 0 failures** (54 new), counted from the
  result XMLs after a forced clean run.
- Neither module ships a plugin descriptor: installing them as server
  plugins is not a supported state until the controller phase lands.
