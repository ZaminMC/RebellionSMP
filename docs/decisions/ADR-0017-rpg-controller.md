# ADR-0017 — RPG controller slice: RebellionRPG becomes the eighth installable plugin

Date: 2026-10-28 · Status: accepted (implemented + boot-smoke verified on
Paper 26.3 build 226; player-level flows remain interactive, see scope)

## Context

ADR-0016 delivered the rebellion-rpg persistence layer (ritual claim
protocol, discoveries, tracking sessions, masks, encounters) as a
serverless, non-installable module — the ADR-0009/0013 precedent for
persistence-first slices. Master prompt §18 Phase 7 requires the actual
controller surface on top: souls, casting, signals, commands and
placeholders, all riding the committed ADR-0016 contract with **no
schema changes**. Session 12 started this slice; session 14 finished it
(and fixed the compile-error WIP along the way: `Math.floorMod` has no
double overload — `compass8` rounds into a `long` first).

## Decision

### The entity half: souls are replaceable, ledger rows are authority

The spawner never treats a spawned entity as state. One cycle per
`controller.spawn-interval` evaluates each **enabled** encounter
definition through the pure `SpawnRules` verdicts (day-phase bands,
biome list, player-count bounds — all unit-tested serverless), picks a
random eligible anchor player, draws a site inside the anchor's loaded
surroundings (radius-bounded, anchor-ground Y, biome re-checked at the
drawn column; an unreadable column never spawns), and only then asks the
service to `beginEncounter`. Every rule that is state — concurrency cap,
encounter cooldown, world allowlist — is answered by the ledger CAS
inside `beginEncounter`; a refusal spawns nothing. The entity is spawned
only against a begun row, configured persistent + never-faraway-removed
+ aware-off, and bound by the idempotent `attachSoul` CAS. Damage on a
live soul is cancelled (the listener resolves through the in-memory
entity→encounter map, never a world scan). Right-click completes through
the one-winner `completeEncounter` CAS: losers resolve to the typed
no-op, the entity leaves either way. An entity that vanishes for any
cause except chunk-unload despawns its row (the ADR-0008 E7 lesson);
TTL expiry is the sweep's job, and the sweep also removes entities still
standing for expired rows.

### The spawner is born quiet

The shipped `wandering_soul` definition stays `enabled: false` until the
operator calibrates `worlds`/`biomes`/time-window for the live map —
§5.3 forbids "random unchecked entity spawning" on uncalibrated world
data. The soul entity type is config-driven (`soul-entity`, any living
EntityType, registry-free validated): no NPC framework dependency was
added because none was needed for the first slice; the §5.3 NPC
integration question stays open only for quest scripts richer than
right-click-help, which is honestly out of scope today.

### The casting half: the command IS the claim-protocol driver

`/ritual` (cast/track/mask/status/signal/release) implements the §5.3
transactional requirement as the closed sequence
**verify → reserve → remove → commit**:

1. reagent verification on the main thread (pure `Ingredients` math over
   storage contents — armor and offhand are deliberately out of scope so
   a ritual can never eat worn gear);
2. `reserveRitual` (rate limit + cooldown + duplicate-reservation CAS)
   with the definition digest as the durable refund-evidence row;
3. reagent removal through the sanctioned `Inventory.removeItem` with
   the leftover map as the mid-cast race detector — a leftover releases
   the claim and returns exactly what the removal took (full-inventory
   refunds drop at the feet);
4. the kind-specific commit (`commitTracking` / `commitCounterMask` /
   `commitKnowledge`), each an all-or-none ledger CAS. Fail-closed is
   structural: every refused path leaves no claim, no inventory change,
   or a typed refund state — the DB-error path consumes nothing.

Targets resolve to **known UUIDs only**: online exact match, then
Paper's non-blocking `getOfflinePlayerIfCached` — tracking never
guesses a name and never blocks the main thread on a web lookup.

### Signals reveal exactly the effective tier

`resolveTracking` is the only sanctioned read (ADR-0016 §3); the
renderer may show at most the mask-degraded effective precision. REGION
wording is the pure `SignalMath` vocabulary — an eight-way compass
bearing plus a bucketed distance label ("close / a moderate pull away /
far away / impossibly far") — never a coordinate, never an exact vector.
Targets not currently online render as "beyond sensing" (no offline
position exists to leak — the schema stores none, §5.3 forbids external
location data). The pulse task renders per online caster every
`signal-interval`; 0 disables it (on-demand `/ritual signal` always
renders).

### Placeholders: contract-compliant via a join-loaded cache

`%rebellion_ritual_<key>_discovered%` resolves from an in-memory
discovery cache: one async load per join, evicted on quit, refreshed by
every granting path — placeholder resolution never touches the database
(PlaceholderContributor contract; §10).

### Reconciliation sweep

Every `sweep-interval` plus once at disable: stale-claim release
(`reservation-stale-seconds`, digest rows kept as refund evidence),
encounter TTL expiry, and soul-entity cleanup for non-ACTIVE rows.
Session-12's live NPE lesson is baked in defensively: a bad row logs its
slot and the loop continues — one row never aborts the sweep for the
rest.

## Evidence

- `./gradlew build` green (full suite).
- Boot smoke on Paper 26.3 build 226 (clean first boot, all EIGHT
  installable jars in order): RebellionRPG extracts config.yml +
  messages.yml, applies schema v1, enables with the quiet-spawner log
  line, `rebellion modules` reports `rpg operational`, console `/ritual`
  is denied as player-only through the message catalog, 3+ sweep cycles
  with zero exceptions, graceful reverse-order shutdown with the final
  sweep. The only ERROR line in the server log is the pre-documented
  flat-world generator warning (ADR-0008 harness finding).
- Serverless: RpgMessagesTest (contract pinned), SpawnRulesTest,
  SignalMathTest, RpgConfigTest (WIP controller-section additions),
  RpgServiceIT (ADR-0016 invariants, still green — schema unchanged).

## Consequences

- rebellion-rpg ships `plugin.yml` + `messages.yml` + `config.yml` and
  becomes the **eighth installable jar** (RebellionRPG). The release
  pipeline stages it automatically.
- No schema changes: ADR-0016's migration v1 still covers everything;
  the controller adds zero tables.
- Interactive scope left open (recorded in docs/WORKLOG): the ride-feel
  dragon pass, E4b first-victory emission, real-player ritual flows
  (reagent feel, signal pulse timing), and the §5.3 NPC question for
  anything richer than right-click-help. None of these are claimed
  verified.
- Known honest limitation: the spawner draws sites around the anchor's
  ground Y (surface assumptions are the operator's world data); the
  flat-void smoke world cannot exercise the spawner at all — enabling
  `wandering_soul` requires a real calibrated map, by design.
