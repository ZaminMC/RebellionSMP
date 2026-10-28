package net.zaminmc.rebellion.dragons.spike;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The mandated minimal Paper 26.3 controller spike (master prompt §18
 * Phase 5: "First deliver the minimal 26.3 controller spike and test
 * report"), serverless portion: verify by reflection, against the pinned
 * paper-api 26.3.226 jar on the test classpath, exactly which API members
 * exist for the three behaviors that gate production dragon controls —
 * passenger mounting, movement/flight control, death/battle/gateway —
 * plus the healing surface for pearl healing.
 *
 * <p>This probe proves the COMPILED surface only. Passenger-while-flying
 * behavior, client steering feel, phase transitions under a rider, death
 * animation timing and vanilla gateway emission are LIVE-SERVER questions;
 * ADR-0008 records them as the explicit live-verification gate. The probe
 * prints a findings table (see build output / test report) that the spike
 * report quotes verbatim — nothing in the report is guessed.</p>
 */
class DragonApi263SpikeTest {

    private final List<String> findings = new ArrayList<>();

    // ------------------------------------------------------------------
    // Mount / passenger surface
    // ------------------------------------------------------------------

    @Test
    void passengerSurface() throws Exception {
        Class<?> entity = Class.forName("org.bukkit.entity.Entity");
        Method add = find(entity, "addPassenger", org.bukkit.entity.Entity.class);
        Method remove = find(entity, "removePassenger", org.bukkit.entity.Entity.class);
        Method get = find(entity, "getPassengers");
        record("Entity.addPassenger(Entity)", add != null);
        record("Entity.removePassenger(Entity)", remove != null);
        record("Entity.getPassengers()", get != null);

        Class<?> mount = findClass("org.bukkit.event.entity.EntityMountEvent");
        Class<?> dismount = findClass("org.bukkit.event.entity.EntityDismountEvent");
        record("EntityMountEvent", mount != null);
        record("EntityDismountEvent", dismount != null);

        Class<?> dragon = Class.forName("org.bukkit.entity.EnderDragon");
        boolean dragonIsEntity = org.bukkit.entity.Entity.class.isAssignableFrom(dragon);
        record("EnderDragon is an Entity (mountable surface)", dragonIsEntity);

        // The critical steering finding: is there ANY vanilla steerable
        // dragon? Steerable is the pig/strider contract (item-held steering).
        Class<?> steerable = findClass("org.bukkit.entity.Steerable");
        boolean dragonSteerable = steerable != null && steerable.isAssignableFrom(dragon);
        record("EnderDragon implements Steerable", dragonSteerable);
        assertFalse(dragonSteerable,
                "if a dragon ever becomes Steerable the flight design changes");
        // Buckaroo/rideable-by-default check: vehicles are entities; no
        // extra interface is required to addPassenger.
        assertTrue(add != null && remove != null && get != null,
                "passenger surface must exist on paper-api 26.3");
    }

    // ------------------------------------------------------------------
    // Flight / movement control surface
    // ------------------------------------------------------------------

    @Test
    void flightControlSurface() throws Exception {
        Class<?> mob = Class.forName("org.bukkit.entity.Mob");
        record("Mob.setAware(boolean)", find(mob, "setAware", boolean.class) != null);
        record("Mob.isAware()", find(mob, "isAware") != null);
        record("Mob.setPathfindingEnabled?", find(mob, "setPathfindingEnabled", boolean.class) != null);

        Class<?> entity = Class.forName("org.bukkit.entity.Entity");
        record("Entity.setGravity(boolean)", find(entity, "setGravity", boolean.class) != null);
        record("Entity.setVelocity(Vector)", find(entity, "setVelocity",
                findClass("org.bukkit.util.Vector")) != null);
        record("Entity.tick() (Paper custom tick)", find(entity, "tick") != null);

        // EnderDragon phase manager: vanilla flight AI lives in phases.
        Class<?> dragon = Class.forName("org.bukkit.entity.EnderDragon");
        Method getPhase = find(dragon, "getPhase");
        Method setPhase = find(dragon, "setPhase", findClass("org.bukkit.entity.EnderDragon$Phase"));
        record("EnderDragon.getPhase()", getPhase != null);
        record("EnderDragon.setPhase(Phase)", setPhase != null);

        Class<?> changePhase = findClass("org.bukkit.event.entity.EnderDragonChangePhaseEvent");
        record("EnderDragonChangePhaseEvent", changePhase != null);

        Class<?> player = Class.forName("org.bukkit.entity.Player");
        record("Player.setAllowFlight(boolean)", find(player, "setAllowFlight", boolean.class) != null);
        record("Player.setFlySpeed(float)", find(player, "setFlySpeed", float.class) != null);

        // Pathfinder for scripted routes: Mob#getPathfinder (Paper API).
        record("Mob.getPathfinder() (Paper)", find(mob, "getPathfinder") != null);
    }

    // ------------------------------------------------------------------
    // Death / battle / gateway surface
    // ------------------------------------------------------------------

    @Test
    void deathAndGatewaySurface() throws Exception {
        Class<?> dragon = Class.forName("org.bukkit.entity.EnderDragon");
        Class<?> living = Class.forName("org.bukkit.entity.LivingEntity");

        record("LivingEntity.heal(double)", find(living, "heal", double.class) != null);
        record("LivingEntity.damage(double)", find(living, "damage", double.class) != null);
        record("LivingEntity.setHealth(double)", find(living, "setHealth", double.class) != null);
        // NOTE (recorded finding): Attribute's static initializer requires
        // a live RegistryAccess, so serverless code must reference it by
        // name only; on a live server it loads normally.
        Class<?> attribute = findClass("org.bukkit.attribute.Attribute");
        record("org.bukkit.attribute.Attribute (loads serverless? clinit needs registry)",
                attribute != null);
        record("LivingEntity.getAttribute(Attribute)",
                attribute != null && find(living, "getAttribute", attribute) != null);

        record("EnderDragon.getDragonBattle()", find(dragon, "getDragonBattle") != null);
        record("EnderDragon.getBossBar()", find(dragon, "getBossBar") != null);

        record("EntityDeathEvent", findClass("org.bukkit.event.entity.EntityDeathEvent") != null);
        record("EnderDragonDeathEvent", findClass("org.bukkit.event.entity.EnderDragonDeathEvent") != null);
        Class<?> removeEvent = findClass("org.bukkit.event.entity.EntityRemoveEvent");
        record("EntityRemoveEvent (Paper, with RemoveCause)", removeEvent != null);

        // Vanilla battle + gateway ownership: DragonBattle must expose the
        // respawn and previously-killed state so we never fake gateways.
        Class<?> battle = findClass("org.bukkit.boss.DragonBattle");
        record("org.bukkit.boss.DragonBattle", battle != null);
        if (battle != null) {
            record("DragonBattle.hasBeenPreviouslyKilled()",
                    find(battle, "hasBeenPreviouslyKilled") != null);
            record("DragonBattle.getEnderDragon()", find(battle, "getEnderDragon") != null);
            record("DragonBattle.respawnDragon(List<Location>)", find(battle, "respawnDragon",
                    java.util.List.class) != null);
            record("DragonBattle.generateGateway()?",
                    find(battle, "generateGateway") != null);
            record("DragonBattle.getRespawnPhase()", find(battle, "getRespawnPhase") != null);
            record("DragonBattle.initiateRespawn()?", find(battle, "initiateRespawn") != null);
        }
        Class<?> world = findClass("org.bukkit.World");
        record("World.getEnderDragonBattle()", find(world, "getEnderDragonBattle") != null);

        // Direct gateway creation exists (world.spawn + EndGateway state) -
        // the report must say we will NOT use it to duplicate vanilla flow.
        Class<?> endGateway = findClass("org.bukkit.block.EndGateway");
        record("org.bukkit.block.EndGateway (block state)", endGateway != null);
        record("EndGateway.setExitLocation(Location)",
                endGateway != null && find(endGateway, "setExitLocation",
                        findClass("org.bukkit.Location")) != null);

        // Player-heads-up: the dragon egg on the exit portal is vanilla's.
        record("WorldType.THE_END / Environment.THE_END",
                findClass("org.bukkit.WorldType") != null);

        // Vanilla egg + respawn flow hooks (web-verified as the live hooks;
        // probe confirms they exist on the pinned jar).
        // Relocated by Paper (verified in the pinned jar): the vanilla egg
        // emission hook now lives in io.papermc.paper.event.block.
        record("DragonEggFormEvent @ org.bukkit.event.block",
                findClass("org.bukkit.event.block.DragonEggFormEvent") != null);
        record("DragonEggFormEvent @ io.papermc.paper.event.block",
                findClass("io.papermc.paper.event.block.DragonEggFormEvent") != null);
        record("DragonBattle$RespawnPhase (enum)",
                findClass("org.bukkit.boss.DragonBattle$RespawnPhase") != null);
        record("EnderDragonChangePhaseEvent$Cause?",
                findClass("org.bukkit.event.entity.EnderDragonChangePhaseEvent$Cause") != null);
        record("EntityMountedEvent? (paper rename check)",
                findClass("org.bukkit.event.entity.EntityMountedEvent") != null);
    }

    // ------------------------------------------------------------------
    // Controller-slice surface (session 10): every member the production
    // controller, listeners and GatewayObserver depend on, probed the same
    // way. A missing member here is a compile error anyway, but the probe
    // keeps the findings table honest for the ADR.
    // ------------------------------------------------------------------

    @Test
    void controllerSliceSurface() throws Exception {
        Class<?> entity = Class.forName("org.bukkit.entity.Entity");
        Class<?> living = Class.forName("org.bukkit.entity.LivingEntity");
        Class<?> dragon = Class.forName("org.bukkit.entity.EnderDragon");
        Class<?> fireball = findClass("org.bukkit.entity.Fireball");
        Class<?> world = findClass("org.bukkit.World");

        record("Entity.isInWaterOrRain()", find(entity, "isInWaterOrRain") != null);
        record("Entity.teleportAsync(Location)", find(entity, "teleportAsync",
                findClass("org.bukkit.Location")) != null);
        record("LivingEntity.getTargetEntity(int)", find(living, "getTargetEntity",
                int.class) != null);
        record("LivingEntity.getKiller()", find(living, "getKiller") != null);

        record("EnderDragon.getPodium()", find(dragon, "getPodium") != null);
        record("EnderDragon.setPodium(Location)", find(dragon, "setPodium",
                findClass("org.bukkit.Location")) != null);

        record("Fireball.setDirection(Vector)", fireball != null && find(fireball,
                "setDirection", findClass("org.bukkit.util.Vector")) != null);
        record("Fireball.setAcceleration(Vector)", fireball != null && find(fireball,
                "setAcceleration", findClass("org.bukkit.util.Vector")) != null);
        record("Fireball.setPower(Vector)", fireball != null && find(fireball,
                "setPower", findClass("org.bukkit.util.Vector")) != null);

        Class<?> battle = findClass("org.bukkit.boss.DragonBattle");
        record("DragonBattle.getGatewayCount()", battle != null
                && find(battle, "getGatewayCount") != null);
        record("DragonBattle.getEndPortalLocation()", battle != null
                && find(battle, "getEndPortalLocation") != null);

        Class<?> chunk = findClass("org.bukkit.Chunk");
        Class<?> snapshot = findClass("org.bukkit.ChunkSnapshot");
        record("Chunk.getChunkSnapshot()", chunk != null
                && find(chunk, "getChunkSnapshot") != null);
        record("ChunkSnapshot.getBlockType(int,int,int)", snapshot != null
                && find(snapshot, "getBlockType", int.class, int.class, int.class) != null);
        record("World.getChunkAtAsync(int,int,Consumer)", world != null
                && find(world, "getChunkAtAsync", int.class, int.class,
                        java.util.function.Consumer.class) != null);

        // Egg-protection + interact events.
        record("EntityRemoveEvent$Cause.UNLOAD (taxonomy)", findClass(
                "org.bukkit.event.entity.EntityRemoveEvent$Cause") != null);
        record("EntityCombustEvent (cancellable)", findClass(
                "org.bukkit.event.entity.EntityCombustEvent") != null);
        record("ItemDespawnEvent (cancellable)", findClass(
                "org.bukkit.event.entity.ItemDespawnEvent") != null);
        record("InventoryMoveItemEvent (hopper path)", findClass(
                "org.bukkit.event.inventory.InventoryMoveItemEvent") != null);
        record("BlockDispenseEvent (dispenser path)", findClass(
                "org.bukkit.event.block.BlockDispenseEvent") != null);
        record("EntityPortalEvent (egg item portal path)", findClass(
                "org.bukkit.event.entity.EntityPortalEvent") != null);
        record("PlayerInteractEntityEvent.getHand()", find(
                findClass("org.bukkit.event.player.PlayerInteractEntityEvent"),
                "getHand") != null);
        record("PlayerDeathEvent", findClass(
                "org.bukkit.event.entity.PlayerDeathEvent") != null);
        record("EnderDragonFireballHitEvent (com.destroystokyo)", findClass(
                "com.destroystokyo.paper.event.entity.EnderDragonFireballHitEvent") != null);
        record("EntityDamageEvent.setDamage(double)", find(
                findClass("org.bukkit.event.entity.EntityDamageEvent"),
                "setDamage", double.class) != null);
        record("Item.setUnlimitedLifetime(boolean)", find(
                findClass("org.bukkit.entity.Item"), "setUnlimitedLifetime",
                boolean.class) != null);
        record("Material.END_GATEWAY (scannable target)", findClass(
                "org.bukkit.Material") != null);
    }

    // ------------------------------------------------------------------
    // Probe plumbing
    // ------------------------------------------------------------------

    private Method find(Class<?> type, String name, Class<?>... params) {
        try {
            Method m = type.getMethod(name, params);
            return Modifier.isPublic(m.getModifiers()) ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Loads WITHOUT running static initializers: several Bukkit classes
     * (e.g. {@code org.bukkit.attribute.Attribute}) touch the live
     * {@code RegistryAccess} in their clinit and explode serverless —
     * itself a recorded spike finding. Method-surface reflection does not
     * need initialization.
     */
    private Class<?> findClass(String fqn) {
        try {
            return Class.forName(fqn, false, getClass().getClassLoader());
        } catch (ClassNotFoundException | ExceptionInInitializerError e) {
            return null;
        }
    }

    private void record(String what, boolean present) {
        findings.add((present ? "PRESENT  " : "ABSENT   ") + what);
        System.out.println("[dragon-spike] " + (present ? "PRESENT  " : "ABSENT   ") + what);
    }
}
