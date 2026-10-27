# ADR-0007 — Wards: no-claim model, calibrated distance, spatial index

Date: 2026-10-28 · Status: accepted

## Context

Phase 4 (master prompt §5.2) requires watch wards — a sentry/information
system, explicitly **not** a land-claim system. Requirements that shaped
every decision here:

- Wards report approaching outsiders with a **calibrated** distance
  ("An outsider is approaching from 2.4 km away."); kilometers must never
  be guessed, and the default state is *uncalibrated*.
- No protection of any kind: no movement denial, no block/container/damage
  interference, no claim flags or protected chunks.
- No scanning all online players per movement event or per tick; a spatial
  index or bounded candidate lookup is mandatory, with per-ward/per-player
  alert cooldowns and enter/leave state tracking against spam.
- Countermeasures (decoys, dwell delay, masking, alert channels) are
  config-driven and only exist as fully implemented, tested behavior.
- An optional, privacy-default map marker interface exists for later
  web-map integration.

## Decision

### No-claim is structural, not conventional

The `Ward` record and the `rb_wards` table have **no fields that can
express protection**: no flags, no region geometry, no member-role lists.
The detector's output type is `AlertDecision` — information only; a
protection side effect is unrepresentable in the type system. The anchor
block is an ordinary, breakable block, and breaking it (or exploding it)
removes the ward through the same listener path for everyone. Hiding and
defending anchors is the gameplay; the plugin takes sides in none of it.

### Calibration gate (kilometers disabled by default)

`distance.world-units-per-kilometer` ships as an **empty map**. An empty
entry means `DistanceService.format` can only emit the configured
`uncalibrated-unit` (`blocks` or `meters`) — the string "km" cannot appear
for an uncalibrated world. Config validation rejects non-finite,
non-positive and implausible scales (range 1.0–100,000), so a guessed
scale can never reach the formatter. Distances are horizontal-plane only
(Y ignored, by contract with the detector); cross-world has **no**
distance (empty `Optional`, never a number) and the per-world spatial
index makes cross-world detection impossible rather than merely filtered.

Formatting rule (tested): calibrated worlds print one decimal below
10 km (`2.4 km`), integers at or above (`13 km`).

### Spatial index: per-world uniform grid, one-cell queries

`WardSpatialIndex` registers each ward in **every** grid cell its radius
covers at insert time (a 256-block radius on the default 64-block grid
touches 81 cells — once per create, never on a hot path). A position query
then reads exactly one cell: O(1) map hops with a candidate set bounded by
wards overlapping that cell. Cells use copy-on-write sets, so the async
sweep iterates a stable snapshot without locks while toggles/removals
mutate concurrently. Empty-cell cleanup uses `ConcurrentHashMap.compute`
(atomic per bin) so a racing insert can never be dropped by a removal.

Correctness property (tested with a fixed-seed randomized layout):
every radius-covered ward **must** appear in the cell candidates; the
detector re-checks radius itself, so candidates may be a modest superset —
that is the bounded-candidate trade, and the test also asserts candidates
stay far below the total ward count at realistic density.

### Detection pipeline: sample, don't react

- `PlayerMoveEvent` (MONITOR) compares block coordinates; same-block moves
  return immediately, meaningful moves do one O(1) `MovementFeed` mark
  (~50 ns measured). Nothing else happens on the event path.
- The sweep (async, `detection.sweep-interval-ticks`, default 20) processes
  dirty players **plus** players with inside tracks (a dwell-delayed alert
  must commit even if the intruder stands perfectly still). Per player:
  one cell query, one pure detector pass, zero database reads.
- Alert delivery batches into a single main-thread hop per sweep.

### Detection state machine (pure, clock-injected)

`WardDetector` owns every §5.2 rule and is fully unit-tested under fixed
clocks and fake resolvers:

- **Outsider**: anyone who is neither the ward owner nor a cached teammate
  of the ward's team. Without the teams module — or on a cold cache — the
  answer is *outsider* (wards alert rather than silently trusting unknown
  state).
- **Enter/leave cycles**: one alert per cycle; moving around inside is
  silent; a leave notice fires only when the enter actually alerted.
- **Per-(ward, player) re-alert cooldown** survives the leave (a `Track`
  with `lastAlertAtMs = NEVER_ALERTED` sentinel — the tests caught that a
  zero default suppressed first alerts near epoch 0). A different ward is
  never throttled by an unrelated pair.
- **Dwell warning delay** (`detection.dwell-seconds`): the enter commits
  only after the player remained inside that long; leaving earlier
  produces *nothing at all* (edge-grazing is silent). Masking during the
  window suppresses the pending commit — that is the counter-ritual
  escape window.
- **Masking**: masked players never trigger new enter alerts; already
  delivered alerts are never undone; unmasking inside does not
  retro-detect (the sentry never saw the entry).
- **Decoys**: detection identical, alerts labeled, decoys excluded from
  own-intel (nearest-ward placeholder) and map markers.

State is ephemeral by design: a restart empties it and insiders are
re-detected as new entries on first movement. Idle (player, ward) pairs
are pruned after `max(4 × re-alert cooldown, 1 h)`.

### Cross-module contracts (API additions)

- `TeamAffiliationService` (rebellion-api): cache-only, non-blocking team
  lookups for sibling modules. Teams registers the adapter over its hot
  path cache; wards degrades to owner-only alerts without it.
- `MapMarkerProvider` / `MapMarker` (rebellion-api): the marker publication
  surface for a future web-map adapter (Phase 10). Privacy-by-default is
  double-enforced: the provider returns nothing unless
  `map-markers.publish-wards: true` (default false), and the adapter must
  re-check the category before anything public.

### Persistence (module key `wards`, migration v1)

`rb_wards`: server-issued UUID keys, portable SQLite/MySQL types, and the
race backstops as constraints — `UNIQUE (owner, name_norm)` (owner-scoped,
case-insensitive ward names) and `UNIQUE (world, x, y, z)` (one ward per
placed anchor). Caps (per-player, per-team, global) are checked **inside**
the creation transaction, so concurrent creates at a cap resolve exactly
like the teams module's joins-while-full case. The in-memory mirror
(index + anchor map) updates only after commit; `loadAll` rebuilds it at
enable.

## Alternatives rejected

- **Region/claim table with "no protection" flag**: a flag can be flipped;
  a field that doesn't exist can't. Rejected.
- **Per-tick full scans / event-driven detection**: violates the §5.2
  performance rule; the sampling pipeline is the mandated shape.
- **Persisted cooldown state**: adds crash-recovery complexity to working
  memory whose loss (one extra alert after restart) is harmless. Rejected.
- **Milestone-based (not block-based) movement marks**: block granularity
  is the master prompt's own bar ("meaningful position changes"); finer
  sampling buys nothing for sentry semantics.

## Known follow-ups (honest)

- Live Paper 26.3 verification of the full pipeline (movement sampling,
  anchor raytrace, alert rendering) is a manual gate documented in
  TEST_PLAN; this environment has no server runtime.
- The held-item masking check reads inventory from the async sweep;
  a race can flip one sweep's decision but never corrupt state (documented
  on `BukkitMaskingResolver`).
- Ritual-granted masking (cost consumption, failure chance) is Phase 7
  scope; Phase 4 ships timed grants (`/wardadmin mask`) plus the config
  gate.
