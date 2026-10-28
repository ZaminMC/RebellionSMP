# ADR-0014 — Boss-victory gateway observation: scan-diff minting keyed on the real vanilla gateway

Date: 2026-10-28 · Status: accepted (implemented; emergence timing + ring geometry carry an explicit interactive gate)

## Context

ADR-0009 keys lineage minting on "the observed vanilla gateway a victory
opened" but deliberately deferred *how* the gateway is observed
("What this ADR deliberately does not decide: how the vanilla gateway is
observed in the event layer"). The live ADR-0008 experiments (E4/E4b)
proved a negative and left one gate open:

- The End fight only ticks while a player is in the End, so the vanilla
  first-victory emission could not be observed headlessly — the committed
  `dxexp victory` harness is ready for the interactive pass.
- Paper 26.3 exposes no "gateway spawned" event, and
  `DragonBattle.getGatewayCount()` is the only announcement vanilla makes.
  `spawnNewGateway()` exists but using it would synthesize the vanilla flow,
  which master prompt §5.6 forbids ("do not spawn fake gateways on every
  death if vanilla already handles them" — policy since ADR-0008 E4).

The mint path therefore must *discover* the real END_GATEWAY block that
emerged, attribute it to exactly one victory, and never mint twice — with
no live observation of the emergence timing available yet.

## Decision

One `GatewayObserver` window per legitimate boss death (an EnderDragon with
a live `DragonBattle`, excluding any dragon bound to a lineage — the vanilla
fight adopts stray dragons in a ticking End, and a pet's death must never
mint):

1. **Baseline** at the death event: read `getGatewayCount()` and scan the
   configured annulus around the battle's exit portal (falling back to the
   main island center pre-victory, where `getEndPortalLocation()` is null —
   verified live, ADR-0008). Chunks load through the async chunk API, are
   snapshotted on the main thread, and the block-type sweep runs off-thread:
   a boss victory is rare and the scan is bounded (~170 chunks at default
   bounds), so nothing blocks a tick.
2. **Poll** the cheap gateway count every `poll-interval-ticks` inside
   `window-seconds`; do nothing until vanilla announces an increase.
3. **Discovery scan + diff** (`GatewayDiff`, pure and unit-tested):
   candidates = (current scan) − (baseline scan) − (registered gateway
   anchors). Only gateways that *emerged inside this window* and back no
   existing lineage are mintable.
4. **Cross-check against the announced count**: candidates must equal the
   count delta. A mismatch is an ambiguous attribution → refuse everything
   (fail-closed). Candidates empty with a positive delta → scan miss →
   loud WARN, no mint, never a guessed position.
5. **Mint** each surviving candidate through
   `LineageService.issueFromGateway(world, x, y, z, victor)` — the UNIQUE
   gateway constraint (ADR-0009) settles every residual race — and deliver
   the fresh stamped egg to the credited victor; at the lineage cap the
   service returns `CAP_REACHED` and vanilla keeps its own reward policy.

**No credited player killer → no observation at all.** The kill
attribution comes only from `EntityDeathEvent#getKiller()`; when vanilla
credits no player, the module logs a WARN and mints nothing. Inventing an
attribution (nearest player, last damager) is exactly the kind of guess the
master prompt forbids ("Never assume that a Bukkit event provides a killer
for every damage/death chain").

## Known limitations (deliberate, fail-closed)

- If the gateway emerges *before* the baseline scan completes (chunk loads
  take a few seconds), the set-diff misses it forever: the count rises, no
  candidate appears, the WARN fires, and that gateway can never mint — its
  lineage-slot potential is lost. The emergence timing relative to the
  death event is precisely what the E4b interactive pass will measure; if
  it is tighter than the baseline can win, the fallback is a registry-seeded
  baseline (positions backing lineages) rather than a scan baseline. No
  wrong mint is possible either way — only a missed one, which is the
  correct side to fail on.
- A restart during the observation window loses the window (in-memory by
  design; the gateway stays unminted, same WARN path on the next victory).
- Scan bounds (`radius-min/radius-max`, `y-min/y-max`) are configured
  generously with defaults that cover the documented vanilla ring
  (~96 blocks out); the exact 26.3 geometry is confirmed interactively.
  Wrong bounds degrade to a warned scan miss, never a wrong attribution.

## Consequences

- Boss-victory minting is now a complete flow: victory → observation →
  diff → mint → delivery, with every race settled by the ADR-0009 backstops
  and every ambiguity resolved fail-closed.
- `GatewayDiffTest` pins the pure decision table (emerging-only, registered
  excluded, ambiguity refused, miss warned, multi-gateway windows).
- The E4b interactive pass (one player entering the End with the committed
  harness) gains a concrete checklist: emergence timing vs. the baseline
  scan, gateway ring coordinates vs. the default bounds, and the
  `DragonEggFormEvent` trophy flow alongside the lineage mint.
