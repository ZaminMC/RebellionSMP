# ADR-0008 — Dragon controller spike: verified Paper 26.3 surface and the live-verification gate

Date: 2026-10-28 · Status: accepted (spike + live proofs; production controller implemented on the proven surface in session 10 — see ADR-0014 for the gateway observation; the two interactive gates below remain open)

## Context

Master prompt §18 Phase 5 mandates the order of work explicitly: *"First
deliver the minimal 26.3 controller spike and test report"* and *"Do not
continue to production dragon controls until passenger/flight/death
behavior is proved on 26.3."* §5.4 additionally requires confirming
vanilla gateway behavior before building the lineage-egg issuance flow:
*"do not spawn fake gateways on every death if vanilla already handles
them."*

This environment has **no live Paper 26.3 server**, so the spike delivers
everything provable serverless — a reflection probe against the pinned
`paper-api 26.3.build.226-stable` jar (the exact dependency every module
compiles against) — and defines the precise live-server experiment set
that remains. Nothing below is guessed: every API claim is asserted by
`DragonApi263SpikeTest` (runs in `./gradlew :rebellion-dragons:test`).

## Verified API surface (paper-api 26.3.226, probe results)

### Passenger / mount — PRESENT

- `Entity.addPassenger(Entity)`, `removePassenger`, `getPassengers()`
- `EntityMountEvent`, `EntityDismountEvent` (org.bukkit.event.entity)
- `EnderDragon` is an `Entity` — the mountable surface applies to it.

### Flight / movement control — PRESENT (with a structural finding)

- `EnderDragon.getPhase()` / `setPhase(Phase)` + `EnderDragonChangePhaseEvent`
  (no `Cause` sub-enum) — vanilla dragon flight AI lives in phases.
- `Mob.setAware(boolean)` / `isAware()`; `Mob.getPathfinder()` (Paper).
- `Entity.setGravity(boolean)`, `Entity.setVelocity(Vector)`.
- `Player.setAllowFlight(boolean)`, `Player.setFlySpeed(float)`.
- **ABSENT**: `Mob.setPathfindingEnabled`; **ABSENT**: `Entity.tick()` — no
  server-driven per-tick injection on the API; control must be
  phase/pathfinder/velocity/scheduler based.
- **ABSENT**: `EnderDragon implements Steerable` — there is **no vanilla
  item-steered dragon** (the pig/strider contract does not apply).

### Death / battle / egg / gateway — mostly PRESENT, two removals

- Healing: `LivingEntity.heal(double)` (pearl healing path),
  `damage(double)`, `setHealth(double)`, `getAttribute(Attribute)`.
- `EnderDragon.getDragonBattle()`, `getBossBar()`.
- `EntityDeathEvent` **PRESENT**; **`EnderDragonDeathEvent` is ABSENT**
  from the 26.3 API (death detection must use `EntityDeathEvent` for
  EnderDragon plus `EntityRemoveEvent` (Paper, with `RemoveCause`) for
  cleanup paths).
- `org.bukkit.boss.DragonBattle` PRESENT with `hasBeenPreviouslyKilled()`,
  `getEnderDragon()`, `getRespawnPhase()`, `initiateRespawn()`,
  `DragonBattle.RespawnPhase` enum; `World.getEnderDragonBattle()` PRESENT.
- **ABSENT**: `DragonBattle.respawnDragon(List<Location>)` — the old
  crystal-list respawn entry is gone; respawn goes through
  `initiateRespawn()` + `RespawnPhase`.
- **ABSENT**: any `DragonBattle.generateGateway()` — **the API cannot ask
  the battle to emit a gateway.** Vanilla owns gateway emission (first-kill
  ring; web-verified vanilla behavior, multiple sources). Direct gateway
  creation is technically possible (`world.spawn` + `EndGateway` block
  state `setExitLocation`), but the lineage design **must not** fake the
  vanilla flow (master prompt §5.4).
- **`DragonEggFormEvent` was RELOCATED** by Paper: absent from
  `org.bukkit.event.block`, **PRESENT at `io.papermc.paper.event.block.
  DragonEggFormEvent`** (verified against the jar listing). This is the
  vanilla egg-emission hook the lineage-egg flow will listen to — had we
  coded from old Bukkit knowledge, the listener would have silently never
  fired.
- Serverless caveat (probe-caught): `org.bukkit.attribute.Attribute`'s
  static initializer requires a live `RegistryAccess`; serverless tests
  must reference it by name only (no class initialization).

### Community-behavior research (rides)

Riding ender dragons via the Bukkit passenger API is known to work only
roughly out of the box: community implementations (SpigotMC threads,
RideDragon datapack, rideable-dragon plugins) consistently report that the
passenger's **mounted position/animation is wrong by default** and fixing
it needs NMS position overrides or ProtocolLib client packets. This is
exactly the class of behavior the master prompt forbids guessing about —
it goes on the live-verification list below, with the fallback decision
(if the mounted feel is unusable without protocol hacks: pet dragons
follow/call/teleport instead of being ridden, documented honestly).

## The live-server experiment set (production gate)

These cannot be answered from the API jar; each is a scripted experiment
on a clean Paper 26.3 server whose results must be recorded in
`docs/TEST_PLAN.md` before any production dragon control code lands:

1. **Mount feel**: `addPassenger(player)` on an EnderDragon — where does
   the client render the rider? Does the dragon's phase AI keep running
   with a rider, and does `setAware(false)` + `setPhase(HOVER)` give a
   stable mountable platform?
2. **Steering options**: player-forward steering via velocity writes vs.
   pathfinder targets vs. phase manipulation — which yields controllable
   flight without NMS/protocol hacks?
3. **Death under control**: an API-spawned/managed dragon's
   `EntityDeathEvent` timing, drops/XP behavior, and `EntityRemoveEvent`
   cause ordering during the death animation (safe despawn window).
4. **Vanilla gateway + egg emission**: on first legit victory — do
   `DragonEggFormEvent` and the vanilla gateway appear as expected, and
   does `hasBeenPreviouslyKilled()` gate correctly? Repeated victories must
   NOT mint fake gateways or eggs (lineage-slot logic observes vanilla,
   never replaces it).
5. **Pearl healing**: `heal(double)` on an EnderDragon mid-flight — any
   phase/animation interference.

## Consequences

- `rebellion-dragons` currently contains **only** the spike (probe test +
  this report) — no production controls, per the mandated order.
- Design implications already locked in by the probe: death flow uses
  `EntityDeathEvent` (not the removed `EnderDragonDeathEvent`); egg flow
  listens at the Paper FQN `io.papermc.paper.event.block.
  DragonEggFormEvent`; respawn interplay goes through `DragonBattle.
  initiateRespawn()`/`RespawnPhase`; no gateway synthesis — vanilla
  emission is observed, `hasBeenPreviouslyKilled()` is a lineage guard,
  and at the configured lineage cap the module simply mints nothing extra.
- The 20-lineage registry schema (ADR-0009, planned) is persistence-only
  work that does not depend on live behavior proofs and can proceed in a
  later session alongside the live experiments.

---

## Live verification results (Paper 26.3 build 226, 2026-10-28, session 9)

The experiment set above was executed against a real, clean Paper 26.3
build 226 server using the committed `DragonExperiments` harness
(`rebellion-dragons/src/experiments`, built by
`./gradlew :rebellion-dragons:dragonExperimentJar`; console-driven via
`scripts/boot-smoke.sh`; the run log is reproduced in
`docs/evidence/adr0008-experiments.log`). Every server-side question is
now answered with measured values; the two client-side halves are the
only remaining interactive gates.

### Corrections to the serverless probe (the API surface was richer than probed)

- `org.bukkit.boss.DragonBattle` in 26.3 ALSO exposes
  `getGatewayCount()`, `spawnNewGateway()`, `spawnNewGateway(Position)`,
  `generateEndPortal(boolean)`, `setPreviouslyKilled(boolean)`,
  `resetCrystals()`, `getRespawnCrystals()` and `getHealingCrystals()`.
  The probe statement "the API cannot ask the battle to emit a gateway"
  is therefore outdated for 26.3 — the design decision is unchanged
  (the module observes vanilla emission; it must never synthesize
  gateways, now for policy rather than capability reasons).
- `EnderDragon.getPodium()` / `setPodium(Location)` exists — this turned
  out to be the key NMS-free steering primitive (E2c below).
- `EntityRemoveEvent` lives at `org.bukkit.event.entity.EntityRemoveEvent`
  (promoted out of the `io.papermc.paper` namespace in 26.3) with causes
  `DEATH`, `DESPAWN`, `DROP`, `ENTER_BLOCK`, `EXPLODE`, `HIT`, `MERGE`,
  `OUT_OF_WORLD`, `PICKUP`, `PLAYER_QUIT`, `PLUGIN`, `TRANSFORMATION`,
  `UNLOAD`, `DISCARD`.
- `DragonBattle.RespawnPhase` values: `START`, `PREPARING_TO_SUMMON_PILLARS`,
  `SUMMONING_PILLARS`, `SUMMONING_DRAGON`, `END`, `NONE`.
- `DragonBattle.getEndPortalLocation()` returns **null** until an exit
  portal exists — on a fresh battle that means "until the first victory".
  Consumers must null-check; the portal is not pre-generated.

### E1 — mount mechanics (server-side half: verified)

- `addPassenger(livingEntity)` returns true and fires
  `EntityMountEvent` (uncancelled); `removePassenger` returns true and
  fires `EntityDismountEvent`; the passenger is released free (+5y nudge).
- The server-side passenger anchor is **dragon origin + (0, 3.0, 0)**
  (stable from the second tick).
- The dragon's phase logic keeps running with a rider (no forced
  dismount, no phase reset).
- **Stable mountable platform**: `setAware(false)` +
  `setPhase(HOVER)` holds position with **0.00 blocks drift over 5 s**
  and zero velocity. This is the mount/steer chassis for pet dragons.
- Interactive gate (unchanged): where a real *player* rider renders
  client-side on a large entity, and whether that feel is acceptable
  without protocol work (community reports it is not, by default).

### E2 — steering primitives (server-side half: verified, two dead ends closed)

- (a) `setVelocity(...)` on an AI-driven dragon: **displacement 0.00
  blocks over 2 s** — phase AI swallows velocity writes. Dead end.
- (b) Paper `getPathfinder().moveTo(location, speed)`: returns **false**
  and moves the dragon **0.00 blocks over 4 s** — the ground pathfinder
  is inert on flying bosses. Dead end (as suspected, but now proven).
- (c) `setPodium(target)` + `setPhase(FLY_TO_PORTAL)`: **works**. The
  dragon flew from (0,100,0) toward the configured podium ~16 blocks/s
  (distance 37.3 → 13.7 in 3 s), overshot by ~16 blocks, then parked
  without further phase transitions (15 s observation). Steering model
  for the controller: **closed-loop waypoint steering** — periodic
  `setPodium` re-targeting + phase, with `EnderDragonChangePhaseEvent`
  (cancellable, `setNewPhase`) as the control hook. No NMS required.

### E3 — death under control (verified)

- **Sourceless `damage()` does not hurt EnderDragons in 26.3** —
  `damage(30)` and `damage(2000)` with no source left health at 200.0
  (confirmed in three independent runs). Unit tests and the controller
  must never assume generic `damage()` works on dragons; use
  `damage(amount, entity)`, `kill()` or `setHealth`.
- `kill()`: `EntityDeathEvent` fires the same tick with `killer=null`,
  `droppedExp=500` (the repeat-kill reward amount, NOT 12000 — the
  first-kill 12000 is granted by the battle flow, not the entity event),
  `drops` empty (dragons drop no items).
- The corpse lingers for the death animation: entity removal
  (`EntityRemoveEvent`) fires **199 ticks after** the death event,
  cause=`DEATH`. For a summoned dragon `getDeathAnimationTicks()` reads
  0 while alive, yet the ~200-tick animation still runs.
- Removal-cause taxonomy verified live: `DEATH` (kill), `UNLOAD`
  (chunk unload, entity saved), `PLUGIN` (`remove()`).
- Consequence for ADR-0009 wiring: hook `EntityDeathEvent` for the
  lineage death-CAS (immediate, idempotent); treat the ~200-tick corpse
  window as the death-presentation period; do not wait for removal to
  run lineage logic.

### E4 / E4b — vanilla gateway + egg emission (headless-blocked; bytecode-proven; interactive)

The harness could not obtain a battle dragon without a player, and the
reason is now proven from the 26.3 server bytecode rather than assumed:

- The End battle does **not** spawn a dragon on world load.
  `EnderDragonFight.tick()` — which calls `findOrCreateDragon()` (adopts
  any dragon in the End, else `createNewDragon()`) — is invoked by
  `ServerLevel.tick` **only when at least one player is in the End**
  (`ServerLevel.tick` bytecode: the `dragonFight` branch is guarded by
  the players-present flag).
- Every headless acquisition route was tried and measured:
  plugin-spawned dragon in the End does not link (fight never scans
  without ticking); `setRespawnPhase(SUMMONING_DRAGON)` returns false on
  a fresh battle; the no-arg `initiateRespawn()` no-ops without ritual
  crystals; `generateEndPortal(true)` works (portal blocks +
  `TheEndPortalBlockEntity`) but the previouslyKilled dance still fails
  because `respawnDragon()` additionally requires the internal
  `dragonKilled` flag (no Bukkit setter; interlock:
  `if (!previouslyKilled && dragonKilled) dragonKilled = false`);
  staging a dragon on disk and restarting does not get it adopted for
  the same player-gated reason.
- `initiateRespawn(Collection<EnderCrystal>)` is the real ritual entry
  (crystal positions are recorded, not validated — they are beam
  sources for the pillar-healing animation), but it sits behind the same
  `dragonKilled` precondition, which only a real death in a ticking
  fight can satisfy.
- **Interactive gate**: one player entering the End starts the vanilla
  fight (dragon spawns on first entry); the committed harness then
  observes the first legit victory (`dxexp victory`) and the repeat
  (`dxexp victory2`): egg emission via `DragonEggFormEvent` at the Paper
  FQN, gateway count delta per victory, `hasBeenPreviouslyKilled`
  transitions, first-kill 12000 vs repeat 500 XP, and the gateway ring
  coordinates the ADR-0009 mint path keys on.

### E5 — heal (verified)

- `heal(25)` on a staged wound (170/200) yields exactly 195; `heal(500)`
  clamps to max (200) — no overshoot, no phase interference (stayed
  HOVER), no exception. Pearl-feeding arithmetic is safe on this API.

### E6 — fireball launch (verified)

- `setDirection(...)` alone leaves a spawned `DragonFireball`
  **motionless** (10 s, zero displacement) — Paper drives these
  projectiles through the acceleration/power vector. The launch recipe
  that works: `setDirection` + `setAcceleration` + `setPower` +
  `setVelocity`.
- With acceleration set, the fireball flies and impact fires
  **`EnderDragonFireballHitEvent` with `getAreaEffectCloud()` present
  (cancellable) and a `getTargets()` list** — the exact hooks for the
  fireball budget and friendly-fire checks.
- **No block damage**: impact block unchanged, zero
  `EntityExplodeEvent`, dragon fireballs do not break blocks (vanilla
  semantics hold — no special handling needed beyond never switching to
  a block-breaking projectile).
- Natural-shot hooks confirmed present for the budget:
  `EnderDragonShootFireballEvent`, `EnderDragonFlameEvent` (both
  cancellable; the flame event exposes the `AreaEffectCloud`).

### E7 — chunk unload/reload persistence (verified)

- `teleport` works on dragons (2000, 100, 2000 measured).
- Unloading the dragon's chunk fires `EntityRemoveEvent(cause=UNLOAD,
  valid=false)`; `Bukkit.getEntity(uuid)` is null while unloaded.
- Reloading the chunk re-adds the **same UUID**
  (`EntityAddToWorldEvent` + `EntitiesLoadEvent`) with health and phase
  preserved (200/HOVER → 200/HOVER). The entity-level persistence
  contract for pet dragons across chunk boundaries is proven; ADR-0009's
  entity-UUID binding survives unload/reload by construction.
- Harness caution recorded: a far dragon's manually reloaded chunk is
  subject to normal auto-unload (~seconds without a ticket) — the
  controller must keep or re-acquire tickets around pet dragons (the
  first live kill attempt raced an auto-unload and lost, cause=UNLOAD).

### Harness/environment findings worth keeping

- `scripts/boot-smoke.sh`'s flat-world profile produces an **empty
  (void) flat world** on 26.3 — the generator logs
  `No key layers in MapLike[{}]` and applies no layers. Harmless for
  boot/module smoke tests; future harnesses that need ground should not
  rely on this preset.
- The End world is created on demand (not at boot) in 26.3; the
  harness's `WorldCreator` fallback is therefore required, not optional.
- `EntityRemoveEvent` fires with `valid=true` for `PLUGIN` removals and
  `valid=false` for `DEATH`/`UNLOAD` — consumers must not conflate the
  flag with persistence.

## Consequences (updated by the live results)

- The §5.6 implementation gate is satisfied at the server-side level
  for all five mandated proofs (mounting, flight control, fireball
  launch, unload/reload, death behavior). Production controller work
  may proceed on that basis.
- Locked-in controller decisions from the measurements: mount platform
  = `setAware(false)` + `HOVER`; steering = closed-loop podium
  re-targeting (`setPodium` + `FLY_TO_PORTAL`, `EnderDragonChangePhaseEvent`
  as the guard); fire attack = plugin-launched `DragonFireball` with
  acceleration + `EnderDragonFireballHitEvent` budget hooks (never a
  block-breaking projectile); death = `EntityDeathEvent`-triggered
  lineage CAS with the ~200-tick corpse window reserved for the death
  presentation; damage/health manipulation via `damage(amount, source)`,
  `setHealth` or `kill()` — never bare `damage()`.
- The ride *feel* (client-side rider rendering) and the vanilla
  first-victory emission (E4/E4b) remain the two interactive gates; the
  harness is committed and ready for both. Until the ride-feel gate
  passes, rideable behavior ships disabled by configuration.
