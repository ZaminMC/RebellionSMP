package net.zaminmc.rebellion.dragons.experiment;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Sheep;
import org.bukkit.util.Vector;

/**
 * E1/E2/E5/E6/E7 — experiments on a plugin-spawned ("managed") dragon in
 * the overworld. Server-side halves only: the human client-feel halves of
 * E1/E2 stay interactive gates and are recorded as such.
 */
final class ManagedDragonKit {

    private ManagedDragonKit() {
    }

    private static EnderDragon require(DragonExperimentsPlugin p) {
        EnderDragon d = p.managed();
        if (d == null) {
            p.log("ERROR no managed dragon; run: dxexp spawn");
        }
        return d;
    }

    // ------------------------------------------------------------------
    // E1 — mount mechanics (server-side half)
    // ------------------------------------------------------------------
    static void mount(DragonExperimentsPlugin p) {
        EnderDragon d = require(p);
        if (d == null) {
            return;
        }
        d.setAware(true);
        d.setPhase(EnderDragon.Phase.HOVER);
        p.log("E1 BEGIN mount mechanics: dragon=%s phase=%s aware=true", d.getUniqueId(),
                d.getPhase());
        World w = d.getWorld();
        Sheep sheep = w.spawn(d.getLocation(), Sheep.class);
        p.setPassenger(sheep);
        boolean added = d.addPassenger(sheep);
        p.log("E1 addPassenger returned %s; passengers now %s", added,
                d.getPassengers().size());
        // 10 s of dual sampling: does the passenger track the dragon, and
        // does the dragon's AI keep running with a rider?
        final Location[] lastDragon = {null};
        p.sample(10, 20, i -> {
            Location dl = d.getLocation();
            Location sl = sheep.getLocation();
            String moved = lastDragon[0] == null ? "first"
                    : String.format("%.2f", lastDragon[0].distance(dl));
            lastDragon[0] = dl.clone();
            p.log("E1 sample dragon=%s sheep=%s offset=(%.1f,%.1f,%.1f) phase=%s "
                    + "dragonMoved=%s", p.shortPos(dl), p.shortPos(sl),
                    sl.getX() - dl.getX(), sl.getY() - dl.getY(),
                    sl.getZ() - dl.getZ(), d.getPhase(), moved);
        }, () -> {
            // Stable platform attempt: freeze AI + HOVER.
            d.setAware(false);
            d.setPhase(EnderDragon.Phase.HOVER);
            p.log("E1 platform: setAware(false) + setPhase(HOVER) -> aware=%s "
                    + "phase=%s", d.isAware(), d.getPhase());
            final Location[] anchor = {null};
            final double[] maxDrift = {0};
            p.sample(10, 10, i -> {
                Location l = d.getLocation();
                if (anchor[0] == null) {
                    anchor[0] = l.clone();
                } else {
                    maxDrift[0] = Math.max(maxDrift[0], anchor[0].distance(l));
                }
                p.log("E1 platform sample vel=%.3f pos=%s sheep=%s",
                        d.getVelocity().length(), p.shortPos(l),
                        p.shortPos(sheep.isValid() ? sheep.getLocation() : null));
            }, () -> {
                p.log("E1 platform result: maxDrift=%.2f blocks over 5s, "
                        + "passengerStillAboard=%s", maxDrift[0],
                        d.getPassengers().contains(sheep));
                boolean removed = d.removePassenger(sheep);
                p.log("E1 removePassenger returned %s; passengers now %s; "
                        + "sheep free at %s", removed, d.getPassengers().size(),
                        p.shortPos(sheep.isValid() ? sheep.getLocation() : null));
                p.log("E1 END (client render position of a PLAYER rider remains an "
                        + "interactive gate)");
            });
        });
    }

    // ------------------------------------------------------------------
    // E2 — steering primitives (server-side half)
    // ------------------------------------------------------------------
    static void steer(DragonExperimentsPlugin p) {
        EnderDragon d = require(p);
        if (d == null) {
            return;
        }
        d.setAware(true);
        p.log("E2 BEGIN steering primitives: dragon at %s phase=%s",
                p.shortPos(d.getLocation()), d.getPhase());

        // (a) raw velocity write on an AI-driven dragon
        Location before = d.getLocation().clone();
        d.setVelocity(new Vector(3, 0, 0));
        p.log("E2a setVelocity(3,0,0) on aware dragon");
        p.sample(5, 8, i -> p.log("E2a pos=%s vel=%.3f", p.shortPos(d.getLocation()),
                d.getVelocity().length()), () -> {
            Location after = d.getLocation();
            Vector disp = after.toVector().subtract(before.toVector());
            p.log("E2a RESULT displacement over 2s = (%.1f, %.1f, %.1f) len=%.2f "
                    + "(velocity actuation on aware dragon)", disp.getX(),
                    disp.getY(), disp.getZ(), disp.length());

            // (b) Paper pathfinder
            boolean accepted = d.getPathfinder()
                    .moveTo(new Location(d.getWorld(), 0, 100, -40), 1.0);
            p.log("E2b pathfinder.moveTo(0,100,-40, speed 1.0) returned %s", accepted);
            Location bBefore = d.getLocation().clone();
            p.sample(5, 16, i -> p.log("E2b pos=%s", p.shortPos(d.getLocation())),
                    () -> {
                Location bAfter = d.getLocation();
                p.log("E2b RESULT displacement over 4s = %.2f blocks toward -z "
                        + "(z %.1f -> %.1f); pathfinding on a flying boss",
                        bBefore.distance(bAfter), bBefore.getZ(), bAfter.getZ());

                // (c) podium + FLY_TO_PORTAL — Paper's per-dragon podium API
                Location podium = new Location(d.getWorld(), 40, 95, 0);
                d.setPodium(podium);
                d.setPhase(EnderDragon.Phase.FLY_TO_PORTAL);
                p.log("E2c setPodium(%s) + setPhase(FLY_TO_PORTAL) -> phase=%s",
                        p.shortPos(podium), d.getPhase());
                p.sample(10, 30, i -> p.log("E2c pos=%s phase=%s distToPodium=%.1f",
                        p.shortPos(d.getLocation()), d.getPhase(),
                        d.getLocation().distance(podium)), () -> {
                    p.log("E2c RESULT final distance to podium %.2f, phase=%s "
                            + "(did the dragon approach the configured point?)",
                            d.getLocation().distance(podium), d.getPhase());
                    // settle for the next experiment
                    d.setPhase(EnderDragon.Phase.HOVER);
                    d.setAware(false);
                    p.log("E2 END settled: aware=%s phase=%s", d.isAware(),
                            d.getPhase());
                });
            });
        });
    }

    // ------------------------------------------------------------------
    // E5 — pearl-heal analog: damage then heal mid-flight
    // ------------------------------------------------------------------
    static void heal(DragonExperimentsPlugin p) {
        EnderDragon d = require(p);
        if (d == null) {
            return;
        }
        d.setAware(false);
        d.setPhase(EnderDragon.Phase.HOVER);
        double max = d.getMaxHealth();
        double h0 = d.getHealth();
        p.log("E5 BEGIN heal: max=%.1f health=%.1f phase=%s aware=false (stable "
                + "mid-flight analog)", max, h0, d.getPhase());
        d.damage(30);
        double h1 = d.getHealth();
        p.log("E5 damage(30) [no source] -> health=%.1f (dragons filter "
                + "sourceless damage in 26.3 - finding)", h1);
        if (h1 >= h0) {
            // damage() was ignored; use setHealth to stage a wound so the
            // heal() arithmetic can still be verified.
            d.setHealth(Math.max(1, max - 30));
            h1 = d.getHealth();
            p.log("E5 staged wound via setHealth -> health=%.1f", h1);
        }
        d.heal(25);
        double h2 = d.getHealth();
        p.log("E5 heal(25) -> health=%.1f phase=%s (phase interference check)", h2,
                d.getPhase());
        d.heal(500);
        double h3 = d.getHealth();
        p.log("E5 heal(500) clamp check -> health=%.1f (expect == max %.1f)", h3, max);
        p.log("E5 RESULT staged %.1f + heal(25) = %.1f + heal(500) clamp = %.1f; "
                + "overhealClamped=%s; phaseStayedHover=%s", h1, h2, h3, h3 == max,
                d.getPhase() == EnderDragon.Phase.HOVER);
    }

    // ------------------------------------------------------------------
    // E6 — fireball launch (plugin-aimed DragonFireball)
    // ------------------------------------------------------------------
    static void fireball(DragonExperimentsPlugin p) {
        EnderDragon d = require(p);
        if (d == null) {
            return;
        }
        d.setAware(false);
        d.setPhase(EnderDragon.Phase.HOVER);
        World w = d.getWorld();
        Location head = d.getLocation().clone().add(0, 2, 0);
        // Aim into the GROUND: flat-world surface is y~4; an air-point target
        // never collides (first live run froze the fireball mid-air after ~50
        // blocks with no hit event).
        Location target = new Location(w, head.getX() + 30, 1, head.getZ());
        Material groundBefore = target.getBlock().getType();
        p.log("E6 BEGIN fireball: spawn at %s aim %s; ground block before=%s",
                p.shortPos(head), p.shortPos(target), groundBefore);
        DragonFireball fb = w.spawn(head, DragonFireball.class);
        Vector dir = target.toVector().subtract(head.toVector()).normalize();
        // setDirection alone left a DragonFireball motionless in the first
        // live run (t=4160..4361); Paper drives these projectiles through
        // the acceleration/power vector, so set both.
        fb.setDirection(dir);
        fb.setAcceleration(dir.clone().multiply(1.0));
        fb.setPower(dir.clone().multiply(1.0));
        fb.setVelocity(dir.clone().multiply(1.5));
        p.log("E6 DragonFireball uuid=%s direction+acceleration+velocity set",
                fb.getUniqueId());
        p.sample(10, 20, i -> {
            String state = fb.isValid() ? "flying " + p.shortPos(fb.getLocation())
                    : "gone (valid=" + fb.isValid() + ")";
            p.log("E6 sample fireball=%s hit=%s", state, p.fireballHit != null);
        }, () -> {
            Material groundAfter = target.getBlock().getType();
            Location impact = p.fireballHit != null
                    ? p.fireballHit.getEntity().getLocation() : null;
            p.log("E6 RESULT hitEvent=%s atTick=%d impact=%s cloud=%s "
                    + "groundBlock %s -> %s (block damage check) arenaExplosions=%d",
                    p.fireballHit != null, p.fireballHitTick, p.shortPos(impact),
                    p.fireballHit != null && p.fireballHit.getAreaEffectCloud() != null,
                    groundBefore, groundAfter, p.blockExplosionsNearArena);
            p.log("E6 END (natural dragon-shot events observed separately: "
                    + "shoot=%s flame=%s)", p.dragonShot != null, p.dragonFlame != null);
        });
    }

    // ------------------------------------------------------------------
    // E7 — chunk unload/reload persistence
    // ------------------------------------------------------------------
    static void persist(DragonExperimentsPlugin p) {
        EnderDragon d = require(p);
        if (d == null) {
            return;
        }
        World w = d.getWorld();
        p.log("E7 BEGIN persistence: dragon uuid=%s health=%.1f phase=%s",
                d.getUniqueId(), d.getHealth(), d.getPhase());
        Location far = new Location(w, 2000, 100, 2000);
        boolean tp = d.teleport(far);
        if (!tp) {
            p.log("E7 teleport returned false — respawning at far location instead");
            d.remove();
            EnderDragon fresh = w.spawn(far, EnderDragon.class);
            p.setManaged(fresh);
            d = fresh;
        }
        final EnderDragon dragon = d;
        double healthBefore = dragon.getHealth();
        var phaseBefore = dragon.getPhase();
        int cx = 2000 >> 4;
        int cz = 2000 >> 4;
        p.sample(10, 4, i -> p.log("E7 settle pos=%s", p.shortPos(dragon.getLocation())),
                () -> {
            // Unload the 5x5 chunk area around the far position.
            int refused = 0;
            for (int x = cx - 2; x <= cx + 2; x++) {
                for (int z = cz - 2; z <= cz + 2; z++) {
                    if (!w.unloadChunk(x, z, true)) {
                        refused++;
                    }
                }
            }
            boolean chunkLoaded = w.isChunkLoaded(cx, cz);
            Entity lookup = Bukkit.getEntity(dragon.getUniqueId());
            p.log("E7 unload 5x5 around chunk(%d,%d): refused=%d centerLoaded=%s "
                    + "entityLookup=%s", cx, cz, refused, chunkLoaded,
                    lookup == null ? "null (gone from loaded world)" : lookup.getType());
            // Reload the center chunk and look for the dragon.
            w.getChunkAt(cx, cz).load();
            Bukkit.getScheduler().runTaskLater(p, () -> {
                Entity back = Bukkit.getEntity(dragon.getUniqueId());
                if (back instanceof EnderDragon rd) {
                    p.log("E7 RESULT reloaded same uuid: health %.1f -> %.1f "
                            + "phase %s -> %s (persistence verified)",
                            healthBefore, rd.getHealth(), phaseBefore, rd.getPhase());
                } else {
                    p.log("E7 RESULT dragon NOT found after chunk reload: %s",
                            back == null ? "null" : back.getType());
                }
                p.log("E7 END entitiesLoad='%s' entitiesUnload='%s' addToWorld='%s'",
                        p.lastEntitiesLoad, p.lastEntitiesUnload, p.lastAddToWorld);
            }, 40L);
        });
    }
}
