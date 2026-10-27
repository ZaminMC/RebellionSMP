# ADR-0008 — Dragon controller spike: verified Paper 26.3 surface and the live-verification gate

Date: 2026-10-28 · Status: accepted (spike report; production controls gated)

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
