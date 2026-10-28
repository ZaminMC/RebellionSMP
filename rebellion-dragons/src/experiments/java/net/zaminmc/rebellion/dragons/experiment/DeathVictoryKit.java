package net.zaminmc.rebellion.dragons.experiment;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * E3 — death timing on a managed dragon.
 * E4 — vanilla gateway + egg emission on the first legit battle victory.
 * E4b — repeated victory: gateway count increments, egg never re-mints.
 */
final class DeathVictoryKit {

    private DeathVictoryKit() {
    }

    // ------------------------------------------------------------------
    // E3 — death under control (managed dragon)
    // ------------------------------------------------------------------
    static void death(DragonExperimentsPlugin p) {
        EnderDragon d = p.managed();
        if (d == null) {
            p.log("ERROR no managed dragon; run: dxexp spawn");
            return;
        }
        p.managedDeath = null;
        p.managedDeathTick = -1;
        p.lastRemoval = null;
        p.lastRemovalTick = -1;
        // The dragon may sit in un-force-loaded chunks after the persist
        // experiment (first live run: the far chunk auto-unloaded 7 ticks
        // into the kill and raced it — cause=UNLOAD, no death). Bring it
        // back into the force-loaded spawn arena before killing.
        d.teleport(new Location(d.getWorld(), 0, 100, 0));
        int deathTicks = d.getDeathAnimationTicks();
        p.log("E3 BEGIN death: uuid=%s health=%.1f phase=%s aware=%s "
                + "deathAnimationTicks=%d", d.getUniqueId(), d.getHealth(),
                d.getPhase(), d.isAware(), deathTicks);
        d.damage(2000);
        p.log("E3 damage(2000) [no source] delivered; fallback to kill() in 1s "
                + "if ignored (sourceless-damage filter)");
        final EnderDragon dragon = d;
        Bukkit.getScheduler().runTaskLater(p, () -> {
            if (p.managed() != null && dragon.isValid()) {
                p.log("E3 damage(2000) ignored (health=%.1f) — using kill()",
                        dragon.getHealth());
                dragon.kill();
            }
        }, 20L);
        p.log("E3 polling removal timeline");
        p.sample(20, 25, i -> {
            EnderDragon still = p.managed();
            p.log("E3 poll %ds: managed=%s health=%s valid=%s | deathEvent=%s@%d "
                    + "remove=%s@%d", (i + 1),
                    still == null ? "gone" : "alive",
                    still == null ? "-" : String.format("%.1f", still.getHealth()),
                    still != null && still.isValid(),
                    p.managedDeath != null, p.managedDeathTick,
                    p.lastRemoval != null ? p.lastRemoval.getCause() : "none",
                    p.lastRemovalTick);
        }, () -> {
            int animationTicks = p.lastRemovalTick >= 0 && p.managedDeathTick >= 0
                    ? p.lastRemovalTick - p.managedDeathTick : -1;
            p.log("E3 RESULT deathEvent->removal = %d ticks (getDeathAnimationTicks "
                    + "reported %d); removalCause=%s", animationTicks, deathTicks,
                    p.lastRemoval != null ? p.lastRemoval.getCause() : "none");
            if (p.managedDeath != null) {
                p.log("E3 RESULT droppedExp=%d drops=%d killer=%s (console damage: "
                        + "no player attribution by design)",
                        p.managedDeath.getDroppedExp(),
                        p.managedDeath.getDrops().size(),
                        p.managedDeath.getEntity().getKiller());
            }
            p.log("E3 END");
        });
    }

    // ------------------------------------------------------------------
    // E4 — first legit victory: egg + gateway + previouslyKilled
    // ------------------------------------------------------------------
    static void victory(DragonExperimentsPlugin p) {
        World end = p.endWorld();
        DragonBattle battle = end.getEnderDragonBattle();
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon == null) {
            p.log("E4 ERROR no battle dragon present; run: dxexp setup (and wait for "
                    + "the vanilla battle dragon) before victory");
            return;
        }
        p.eggEvent = null;
        p.eggEventTick = -1;
        p.battleDeath = null;
        p.battleDeathTick = -1;
        p.lastRemoval = null;
        p.lastRemovalTick = -1;
        int gatewaysBefore = battle.getGatewayCount();
        Location eggBefore = p.findEggBlock(end);
        Location portal = battle.getEndPortalLocation();
        p.log("E4 BEGIN victory: dragon=%s health=%.1f phase=%s | previouslyKilled=%s "
                + "gatewayCount=%d eggBlockBefore=%s portal=%s",
                dragon.getUniqueId(), dragon.getHealth(), dragon.getPhase(),
                battle.hasBeenPreviouslyKilled(), gatewaysBefore, eggBefore != null,
                p.shortPos(portal));
        if (battle.hasBeenPreviouslyKilled()) {
            // The staged-dragon route makes init() derive previouslyKilled=true
            // from the active exit portal; restore false so this kill observes
            // FIRST-victory semantics (harness documents the dance).
            battle.setPreviouslyKilled(false);
            p.log("E4 restored previouslyKilled=false for first-victory observation");
        }
        killLoop(p, dragon, () -> observeVictory(p, gatewaysBefore));
    }

    // ------------------------------------------------------------------
    // E4b — repeated victory: respawn, kill again, verify no egg re-mint
    // ------------------------------------------------------------------
    static void victory2(DragonExperimentsPlugin p) {
        World end = p.endWorld();
        DragonBattle battle = end.getEnderDragonBattle();
        if (!battle.hasBeenPreviouslyKilled()) {
            p.log("E4b ERROR battle not previously killed; run: dxexp victory first");
            return;
        }
        if (battle.getEnderDragon() != null) {
            p.log("E4b ERROR a battle dragon is still alive; unexpected state");
            return;
        }
        p.eggEvent = null;
        p.eggEventTick = -1;
        p.battleDeath = null;
        p.battleDeathTick = -1;
        p.lastRemoval = null;
        p.lastRemovalTick = -1;
        int gatewaysBefore = battle.getGatewayCount();
        p.log("E4b BEGIN repeated victory: gatewayCount=%d respawnPhase=%s — "
                + "spawning 4 ritual crystals + initiateRespawn(collection)",
                gatewaysBefore, battle.getRespawnPhase());
        Location portalLoc = battle.getEndPortalLocation();
        java.util.List<org.bukkit.entity.EnderCrystal> crystals = new java.util.ArrayList<>();
        for (int[] o : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            crystals.add(end.spawn(portalLoc.clone().add(o[0], 1, o[1]),
                    org.bukkit.entity.EnderCrystal.class));
        }
        boolean started = battle.initiateRespawn(crystals);
        p.log("E4b initiateRespawn(4 crystals) returned %s, respawnPhase=%s", started,
                battle.getRespawnPhase());
        if (!started) {
            p.log("E4b ERROR respawn refused (dragonKilled internal state?) — "
                    + "running dxexp victory first is required");
            return;
        }
        new BukkitRunnable() {
            int waited = 0;
            String lastPhase = String.valueOf(battle.getRespawnPhase());
            EnderDragon spawned;
            int settleTicks;

            @Override
            public void run() {
                waited += 20;
                String phase = String.valueOf(battle.getRespawnPhase());
                if (!phase.equals(lastPhase)) {
                    p.log("E4b respawnPhase %s -> %s (+%ds)", lastPhase, phase,
                            waited / 20);
                    lastPhase = phase;
                }
                EnderDragon d = battle.getEnderDragon();
                if (d == null) {
                    if (waited >= 3600) {
                        p.log("E4b BLOCKER no dragon after 3min of respawn phases");
                        cancel();
                    }
                    return;
                }
                if (spawned == null) {
                    spawned = d;
                    p.log("E4b dragon respawned after ~%ds: uuid=%s — settling 5s "
                            + "before killing", waited / 20, d.getUniqueId());
                    return;
                }
                settleTicks += 20;
                if (settleTicks < 100) {
                    return;
                }
                // dragon is alive and settled: kill it
                if (spawned.isValid()) {
                    p.log("E4b killing respawned dragon (health=%.1f)",
                            spawned.getHealth());
                    spawned.damage(2000);
                    if (spawned.isValid()) {
                        p.log("E4b sourceless damage filtered — using kill()");
                        spawned.kill();
                    }
                    if (p.battleDeath != null) {
                        cancel();
                        p.log("E4b respawned dragon dead at t=%d; observing",
                                p.battleDeathTick);
                        observeVictory(p, gatewaysBefore);
                    }
                } else if (waited >= 3600) {
                    p.log("E4b BLOCKER respawned dragon would not die");
                    cancel();
                }
            }
        }.runTaskTimer(p, 20L, 20L);
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------
    private static void killLoop(DragonExperimentsPlugin p, EnderDragon dragon,
            Runnable onDead) {
        final int[] attempts = {0};
        new BukkitRunnable() {
            @Override
            public void run() {
                if (p.battleDeath != null) {
                    cancel();
                    p.log("E4/E4b battle dragon dead (death event t=%d; kill "
                            + "attempts used: damage x%d then kill()); "
                            + "observation window starting", p.battleDeathTick,
                            attempts[0]);
                    onDead.run();
                    return;
                }
                if (!dragon.isValid()) {
                    cancel();
                    p.log("E4/E4b dragon invalid before death event fired");
                    onDead.run();
                    return;
                }
                if (attempts[0] < 2) {
                    attempts[0]++;
                    double before = dragon.getHealth();
                    dragon.damage(250);
                    p.log("E4/E4b damage(250) [no source]: health %.1f -> %.1f",
                            before, dragon.getHealth());
                    return;
                }
                if (attempts[0] == 2) {
                    attempts[0] = 3;
                    p.log("E4/E4b sourceless damage filtered — using kill()");
                    dragon.kill();
                }
            }
        }.runTaskTimer(p, 10L, 10L);
    }

    /** 50s observation window after a battle dragon dies: egg, gateways,
     *  XP orbs, previouslyKilled. */
    private static void observeVictory(DragonExperimentsPlugin p,
            int gatewaysBefore) {
        World end = p.endWorld();
        final int[] maxXp = {0};
        final int[] orbCount = {0};
        p.sample(20, 25, i -> {
            DragonBattle b = end.getEnderDragonBattle();
            int[] xp = p.xpNear(end, b.getEndPortalLocation(), 32);
            maxXp[0] = Math.max(maxXp[0], xp[0]);
            orbCount[0] = Math.max(orbCount[0], xp[1]);
            p.log("OBS %ds: gatewayCount=%d eggEvent=%s eggBlock=%s "
                    + "previouslyKilled=%s xpOrbs(sum=%d count=%d) deathEvent=%s",
                    (i + 1), b.getGatewayCount(),
                    p.eggEvent != null ? "FIRED@" + p.eggEventTick : "none",
                    p.findEggBlock(end) != null,
                    b.hasBeenPreviouslyKilled(), xp[0], xp[1],
                    p.battleDeath != null);
        }, () -> {
            p.scanGatewayBand(end, "OBS-gatewayscan", found -> {
                DragonBattle b = end.getEnderDragonBattle();
                p.log("VICTORY_RESULT gatewayCount %d -> %d (delta %d, expect +1)",
                        gatewaysBefore, b.getGatewayCount(),
                        b.getGatewayCount() - gatewaysBefore);
                p.log("VICTORY_RESULT gatewayScanBlocks=%d", found.size());
                int ring = 0;
                for (Location l : found) {
                    double dist = Math.hypot(l.getX(), l.getZ());
                    if (dist >= 90 && dist <= 102) {
                        ring++;
                    }
                    p.log("VICTORY_RESULT gateway %s ringDist=%.1f", p.shortPos(l),
                            dist);
                }
                p.log("VICTORY_RESULT gatewaysOnExpectedRing(r=90..102)=%d", ring);
                p.log("VICTORY_RESULT eggEventFired=%s eggBlock=%s previouslyKilled=%s",
                        p.eggEvent != null,
                        p.findEggBlock(end) != null,
                        b.hasBeenPreviouslyKilled());
                p.log("VICTORY_RESULT xpOrbs maxSum=%d maxCount=%d "
                        + "(vanilla: 12000 first kill, 500 repeats)", maxXp[0],
                        orbCount[0]);
                if (p.battleDeath != null) {
                    p.log("VICTORY_RESULT deathEvent droppedExp=%d drops=%d",
                            p.battleDeath.getDroppedExp(),
                            p.battleDeath.getDrops().size());
                }
                p.log("VICTORY_RESULT removalCause=%s@%d",
                        p.lastRemoval != null ? p.lastRemoval.getCause() : "none",
                        p.lastRemovalTick);
                p.log("VICTORY_OBSERVATION_WINDOW_END");
            });
        });
    }
}
