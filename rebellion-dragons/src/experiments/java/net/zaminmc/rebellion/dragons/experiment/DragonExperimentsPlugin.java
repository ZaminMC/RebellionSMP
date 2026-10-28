package net.zaminmc.rebellion.dragons.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EnderDragonFireballHitEvent;
import com.destroystokyo.paper.event.entity.EnderDragonFlameEvent;
import com.destroystokyo.paper.event.entity.EnderDragonShootFireballEvent;

import io.papermc.paper.event.block.DragonEggFormEvent;

/**
 * ADR-0008 live-verification harness (master prompt §5.6 implementation
 * gate). Console-driven scripted experiments against a real Paper 26.3
 * server. NEVER ship on a production server; this jar is deliberately not
 * named Rebellion*-version so the release pipeline can never stage it.
 *
 * <p>Experiments (see docs/decisions/ADR-0008-dragon-spike-26.3.md):
 * E1 mount mechanics, E2 steering primitives, E3 death timing,
 * E4 vanilla gateway+egg emission, E4b repeated victory, E5 pearl-heal
 * analog, E6 fireball launch, E7 chunk unload/reload persistence.</p>
 */
public final class DragonExperimentsPlugin extends JavaPlugin implements Listener {

    private Path logFile;

    // --- shared observation state (written by listeners, read by kits) ---
    private UUID managedDragonId;
    private UUID passengerId;
    /** Last EntityDeathEvent for the managed dragon (E3). */
    EntityDeathEvent managedDeath;
    int managedDeathTick = -1;
    /** Last EntityDeathEvent for a battle dragon (E4/E4b). */
    EntityDeathEvent battleDeath;
    int battleDeathTick = -1;
    EntityRemoveEvent lastRemoval;
    int lastRemovalTick = -1;
    /** Last DragonEggFormEvent, if any fired (E4). */
    DragonEggFormEvent eggEvent;
    int eggEventTick = -1;
    EnderDragonFireballHitEvent fireballHit;
    int fireballHitTick = -1;
    EnderDragonShootFireballEvent dragonShot;
    EnderDragonFlameEvent dragonFlame;
    String lastEntitiesLoad = "";
    String lastEntitiesUnload = "";
    String lastAddToWorld = "";
    int blockExplosionsNearArena;

    public static final String TAG = "[DX] ";

    @Override
    public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data folder", e);
        }
        logFile = getDataFolder().toPath().resolve("experiments.log");
        log("=== DragonExperiments (ADR-0008 harness) enabling on "
                + Bukkit.getVersion() + " ===");
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("dxexp");
        if (cmd == null) {
            getLogger().severe("dxexp command missing from plugin.yml");
        } else {
            cmd.setExecutor(this);
        }
        log("harness ready; console usage: dxexp <setup|status|spawn|mount|steer|"
                + "heal|fireball|persist|death|victory|victory2|report>");
    }

    // ------------------------------------------------------------------
    // Structured logging: console + append-only file, every event stamped
    // with the server tick so timing findings survive a crash mid-run.
    // ------------------------------------------------------------------
    void log(String format, Object... args) {
        String line = "t=" + Bukkit.getServer().getCurrentTick() + " "
                + String.format(format, args).replace('\n', ' ');
        getLogger().info(line);
        try {
            Files.writeString(logFile, line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().warning("log append failed: " + e.getMessage());
        }
    }

    int tick() {
        return Bukkit.getServer().getCurrentTick();
    }

    // ------------------------------------------------------------------
    // Shared lookups / helpers used by both kits
    // ------------------------------------------------------------------
    World overworld() {
        return Bukkit.getWorlds().get(0);
    }

    World endWorld() {
        World end = Bukkit.getWorld("world_the_end");
        if (end == null) {
            log("creating world_the_end (Environment.THE_END)");
            end = WorldCreator.name("world_the_end").environment(Environment.THE_END)
                    .createWorld();
        }
        return end;
    }

    EnderDragon managed() {
        if (managedDragonId == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(managedDragonId);
        return e instanceof EnderDragon d ? d : null;
    }

    void setManaged(EnderDragon d) {
        managedDragonId = d.getUniqueId();
    }

    UUID passengerId() {
        return passengerId;
    }

    void setPassenger(Entity p) {
        passengerId = p == null ? null : p.getUniqueId();
    }

    /** Repeating sampler: runs {@code step} every {@code period} ticks for
     *  {@code n} samples, then {@code done}. */
    void sample(int period, int n, Consumer<Integer> step, Runnable done) {
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (i >= n) {
                    cancel();
                    if (done != null) {
                        done.run();
                    }
                    return;
                }
                try {
                    step.accept(i);
                } catch (RuntimeException e) {
                    log("sampler exception: %s", e);
                    cancel();
                    if (done != null) {
                        done.run();
                    }
                }
                i++;
            }
        }.runTaskTimer(this, 1L, period);
    }

    /** Sums XP orbs near a location: returns {totalExperience, orbCount}. */
    int[] xpNear(World w, Location center, double r) {
        int sum = 0;
        int count = 0;
        for (Entity e : w.getNearbyEntities(center, r, r, r)) {
            if (e instanceof ExperienceOrb orb) {
                sum += orb.getExperience();
                count++;
            }
        }
        return new int[] {sum, count};
    }

    /** Sliced band scan for END_GATEWAY blocks (|x|,|z| <= 112, y 0..130). */
    void scanGatewayBand(World end, String tag, Consumer<List<Location>> done) {
        final int radius = 112;
        final int yMax = 130;
        for (int cx = -7; cx <= 7; cx++) {
            for (int cz = -7; cz <= 7; cz++) {
                end.setChunkForceLoaded(cx, cz, true);
            }
        }
        final int[] x = {-radius};
        final List<Location> found = new ArrayList<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                int lim = Math.min(x[0] + 8, radius + 1);
                for (; x[0] < lim; x[0]++) {
                    for (int y = 0; y <= yMax; y++) {
                        for (int z = -radius; z <= radius; z++) {
                            Block b = end.getBlockAt(x[0], y, z);
                            if (b.getType() == Material.END_GATEWAY) {
                                found.add(b.getLocation());
                            }
                        }
                    }
                }
                if (x[0] > radius) {
                    cancel();
                    for (int cx = -7; cx <= 7; cx++) {
                        for (int cz = -7; cz <= 7; cz++) {
                            end.setChunkForceLoaded(cx, cz, false);
                        }
                    }
                    log(tag + ": gateway block scan finished: count=%d", found.size());
                    done.accept(found);
                }
            }
        }.runTaskTimer(this, 1L, 2L);
    }

    /** Small scan for a DRAGON_EGG block near the end portal. */
    Location findEggBlock(World end) {
        var battle = end.getEnderDragonBattle();
        Location portal = battle == null ? new Location(end, 0, 64, 0)
                : battle.getEndPortalLocation();
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                for (int dy = -20; dy <= 80; dy++) {
                    Location l = portal.clone().add(dx, dy, dz);
                    if (l.getBlock().getType() == Material.DRAGON_EGG) {
                        return l;
                    }
                }
            }
        }
        return null;
    }

    String shortPos(Location l) {
        return l == null ? "null"
                : String.format("(%.1f, %.1f, %.1f)@%s", l.getX(), l.getY(), l.getZ(),
                        l.getWorld().getName());
    }

    // ------------------------------------------------------------------
    // Listeners — every dragon-relevant event is logged and captured
    // ------------------------------------------------------------------
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Entity dead = e.getEntity();
        if (dead instanceof EnderDragon) {
            String drops = e.getDrops().stream()
                    .map(is -> is.getType() + "x" + is.getAmount())
                    .reduce((a, b) -> a + "," + b).orElse("none");
            log("EVENT EntityDeathEvent dragon=%s killedBy=%s droppedExp=%d drops=[%s]",
                    dead.getUniqueId(), e.getEntity().getKiller(), e.getDroppedExp(),
                    drops);
            if (dead.getUniqueId().equals(managedDragonId)) {
                managedDeath = e;
                managedDeathTick = tick();
            } else {
                battleDeath = e;
                battleDeathTick = tick();
            }
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveEvent e) {
        Entity entity = e.getEntity();
        if (entity instanceof EnderDragon || entity.getUniqueId().equals(passengerId)) {
            log("EVENT EntityRemoveEvent type=%s uuid=%s cause=%s valid=%s",
                    entity.getType(), entity.getUniqueId(), e.getCause(),
                    entity.isValid());
            lastRemoval = e;
            lastRemovalTick = tick();
        }
    }

    @EventHandler
    public void onPhaseChange(EnderDragonChangePhaseEvent e) {
        if (e.getEntity().getUniqueId().equals(managedDragonId)) {
            log("EVENT EnderDragonChangePhaseEvent managed: %s -> %s",
                    e.getCurrentPhase(), e.getNewPhase());
        }
    }

    @EventHandler
    public void onMount(EntityMountEvent e) {
        log("EVENT EntityMountEvent mount=%s(%s) entity=%s(%s) cancelled=%s",
                e.getMount().getType(), e.getMount().getUniqueId(),
                e.getEntity().getType(), e.getEntity().getUniqueId(),
                e.isCancelled());
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        log("EVENT EntityDismountEvent dismounted=%s(%s) entity=%s(%s)",
                e.getDismounted().getType(), e.getDismounted().getUniqueId(),
                e.getEntity().getType(), e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onEggForm(DragonEggFormEvent e) {
        eggEvent = e;
        eggEventTick = tick();
        log("EVENT DragonEggFormEvent block=%s state=%s battle.previouslyKilled=%s",
                e.getBlock().getLocation(), e.getNewState().getType(),
                e.getDragonBattle().hasBeenPreviouslyKilled());
    }

    @EventHandler
    public void onDragonShoot(EnderDragonShootFireballEvent e) {
        dragonShot = e;
        log("EVENT EnderDragonShootFireballEvent dragon=%s fireball=%s cancelled=%s",
                e.getEntity().getUniqueId(), e.getFireball().getUniqueId(),
                e.isCancelled());
    }

    @EventHandler
    public void onDragonFlame(EnderDragonFlameEvent e) {
        dragonFlame = e;
        log("EVENT EnderDragonFlameEvent cloud=%s at %s",
                e.getAreaEffectCloud().getUniqueId(),
                shortPos(e.getAreaEffectCloud().getLocation()));
    }

    @EventHandler
    public void onFireballHit(EnderDragonFireballHitEvent e) {
        fireballHit = e;
        fireballHitTick = tick();
        log("EVENT EnderDragonFireballHitEvent fireball=%s targets=%d cloud=%s loc=%s",
                e.getEntity().getUniqueId(), e.getTargets().size(),
                e.getAreaEffectCloud() != null,
                shortPos(e.getEntity().getLocation()));
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        if (Math.abs(e.getLocation().getX()) < 400 && Math.abs(e.getLocation().getZ()) < 400) {
            blockExplosionsNearArena++;
            log("EVENT EntityExplodeEvent type=%s at %s blockList=%d (arena guard)",
                    e.getEntityType(), shortPos(e.getLocation()),
                    e.blockList().size());
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        lastEntitiesLoad = "chunk=" + e.getChunk() + " entities=" + e.getEntities().size();
        for (Entity entity : e.getEntities()) {
            if (entity.getUniqueId().equals(managedDragonId)) {
                log("EVENT EntitiesLoadEvent MANAGED_DRAGON back in loaded chunks %s",
                        e.getChunk());
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        lastEntitiesUnload = "chunk=" + e.getChunk() + " entities=" + e.getEntities().size();
        for (Entity entity : e.getEntities()) {
            if (entity.getUniqueId().equals(managedDragonId)) {
                log("EVENT EntitiesUnloadEvent MANAGED_DRAGON leaving loaded chunks %s",
                        e.getChunk());
            }
        }
    }

    @EventHandler
    public void onAddToWorld(EntityAddToWorldEvent e) {
        lastAddToWorld = e.getEntity().getType() + "#" + e.getEntity().getUniqueId();
        if (e.getEntity().getUniqueId().equals(managedDragonId)) {
            log("EVENT EntityAddToWorldEvent MANAGED_DRAGON uuid=%s", e.getEntity().getUniqueId());
        }
    }

    // ------------------------------------------------------------------
    // Command surface
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label,
            String[] args) {
        if (args.length == 0) {
            sender.sendMessage(TAG + "usage: dxexp <setup|status|spawn|mount|steer|heal|"
                    + "fireball|persist|death|victory|victory2|report>");
            return true;
        }
        switch (args[0]) {
            case "setup" -> setup();
            case "setup2" -> setup2();
            case "stage" -> stageDragonForAdoption();
            case "status" -> status();
            case "spawn" -> spawnManaged();
            case "mount" -> ManagedDragonKit.mount(this);
            case "steer" -> ManagedDragonKit.steer(this);
            case "heal" -> ManagedDragonKit.heal(this);
            case "fireball" -> ManagedDragonKit.fireball(this);
            case "persist" -> ManagedDragonKit.persist(this);
            case "death" -> DeathVictoryKit.death(this);
            case "victory" -> DeathVictoryKit.victory(this);
            case "victory2" -> DeathVictoryKit.victory2(this);
            case "report" -> report();
            default -> sender.sendMessage(TAG + "unknown subcommand: " + args[0]);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // setup / status / spawn / report
    // ------------------------------------------------------------------
    private void setup() {
        World end = endWorld();
        // Keep the fight area (portal + pillar ring) loaded without players.
        for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                end.setChunkForceLoaded(cx, cz, true);
            }
        }
        log("setup: end world=%s portal chunks force-loaded", end.getName());
        var battle = end.getEnderDragonBattle();
        log("setup: battle portal=%s previouslyKilled=%s respawnPhase=%s "
                + "gatewayCount=%s dragon=%s",
                shortPos(battle.getEndPortalLocation()),
                battle.hasBeenPreviouslyKilled(), battle.getRespawnPhase(),
                battle.getGatewayCount(),
                battle.getEnderDragon() != null ? "present" : "null");
        // Give the vanilla battle 20s to spawn its dragon on world load.
        new BukkitRunnable() {
            int waited = 0;

            @Override
            public void run() {
                waited += 20;
                var b = endWorld().getEnderDragonBattle();
                EnderDragon d = b.getEnderDragon();
                if (d != null) {
                    log("setup: vanilla battle dragon PRESENT after %ds: uuid=%s "
                            + "health=%.1f phase=%s", waited / 20, d.getUniqueId(),
                            d.getHealth(), d.getPhase());
                    cancel();
                } else if (waited >= 600) {
                    log("setup: BLOCKER no battle dragon after 30s — trying "
                            + "initiateRespawn()");
                    b.initiateRespawn();
                    pollRespawn(b);
                    cancel();
                } else if (waited % 100 == 0) {
                    log("setup: waiting for vanilla dragon... (%ds, phase=%s)",
                            waited / 20, b.getRespawnPhase());
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void pollRespawn(org.bukkit.boss.DragonBattle battle) {
        new BukkitRunnable() {
            int waited = 0;
            String last = "";

            @Override
            public void run() {
                waited += 20;
                EnderDragon d = battle.getEnderDragon();
                String phase = String.valueOf(battle.getRespawnPhase());
                if (!phase.equals(last)) {
                    log("setup: respawnPhase %s -> %s", last, phase);
                    last = phase;
                }
                if (d != null) {
                    log("setup: dragon spawned via initiateRespawn after %ds: uuid=%s",
                            waited / 20, d.getUniqueId());
                    cancel();
                } else if (waited >= 3600) {
                    log("setup: BLOCKER initiateRespawn produced no dragon after 3min");
                    cancel();
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    /** Battle-dragon acquisition without a player: the vanilla battle
     *  does not start on world load (proven by setup), so try, in order:
     *  (a) a summoned dragon links to the battle,
     *  (b) setRespawnPhase(SUMMONING_DRAGON),
     *  (c) the previouslyKilled dance (enable ritual, restore flag after
     *      spawn so the kill still observes first-victory semantics). */
    private void setup2() {
        World end = endWorld();
        var battle = end.getEnderDragonBattle();
        if (battle.getEnderDragon() != null) {
            log("setup2: battle dragon already present: %s",
                    battle.getEnderDragon().getUniqueId());
            return;
        }
        log("setup2: route (a) plugin-spawn ender_dragon in the End — does it link "
                + "to the battle?");
        EnderDragon spawned = end.spawn(new Location(end, 0, 128, 0), EnderDragon.class);
        log("setup2 (a): spawned uuid=%s", spawned.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            var b = endWorld().getEnderDragonBattle();
            EnderDragon linked = b.getEnderDragon();
            log("setup2 (a): battle dragon=%s, spawned still valid=%s",
                    linked != null ? "LINKED " + linked.getUniqueId() : "null",
                    spawned.isValid());
            if (linked != null) {
                log("setup2: SUCCESS via spawn-link; previouslyKilled=%s",
                        b.hasBeenPreviouslyKilled());
                return;
            }
            if (spawned.isValid()) {
                log("setup2 (a): spawned dragon did NOT link — removing it to keep "
                        + "the arena clean");
                spawned.remove();
            }
            log("setup2: route (d) generateEndPortal(true) + previouslyKilled dance "
                    + "+ crystal ritual — the respawn needs the exit portal (which "
                    + "does not exist before the first victory) AND ritual crystals "
                    + "(the no-arg initiateRespawn no-ops without them)");
            boolean portal = b.generateEndPortal(true);
            log("setup2 (d): generateEndPortal(true)=%s, portal=%s previouslyKilled=%s",
                    portal, b.getEndPortalLocation() != null,
                    b.hasBeenPreviouslyKilled());
            b.setPreviouslyKilled(true);
            Location portalLoc = b.getEndPortalLocation();
            java.util.List<org.bukkit.entity.EnderCrystal> crystals = new ArrayList<>();
            for (int[] o : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
                crystals.add(end.spawn(
                        portalLoc.clone().add(o[0], 1, o[1]),
                        org.bukkit.entity.EnderCrystal.class));
            }
            boolean started = b.initiateRespawn(crystals);
            log("setup2 (d): 4 ritual crystals at %s + initiateRespawn(collection)=%s "
                    + "phase=%s", portalLoc, started, b.getRespawnPhase());
            pollForBattleDragon(b, 150, () -> {
                log("setup2 (d): dragon spawned — restoring previouslyKilled=false "
                        + "so the kill observes first-victory semantics");
                b.setPreviouslyKilled(false);
                log("setup2: SUCCESS via portal+dance; previouslyKilled=%s "
                        + "portal=%s", b.hasBeenPreviouslyKilled(),
                        b.getEndPortalLocation() != null);
            }, () -> log("setup2: ALL ROUTES FAILED — legit headless battle dragon "
                    + "requires a player entering the End (interactive gate for "
                    + "E4); routes a/b/c/d exhausted"));
        }, 100L);
    }

    private void pollForBattleDragon(org.bukkit.boss.DragonBattle battle, int seconds,
            Runnable onSuccess, Runnable onTimeout) {
        new BukkitRunnable() {
            int waited = 0;
            String last = "";

            @Override
            public void run() {
                waited += 10;
                EnderDragon d = battle.getEnderDragon();
                String phase = String.valueOf(battle.getRespawnPhase());
                if (!phase.equals(last)) {
                    log("setup2: respawnPhase %s -> %s (+%ds)", last, phase,
                            waited / 20);
                    last = phase;
                }
                if (d != null) {
                    cancel();
                    log("setup2: battle dragon present after ~%ds: uuid=%s "
                            + "health=%.1f", waited / 20, d.getUniqueId(),
                            d.getHealth());
                    onSuccess.run();
                } else if (waited >= seconds * 20) {
                    cancel();
                    log("setup2: no battle dragon after %ds (phase=%s)", seconds,
                            battle.getRespawnPhase());
                    onTimeout.run();
                }
            }
        }.runTaskTimer(this, 10L, 10L);
    }

    /** Route (h) — the only player-free battle-dragon acquisition that
     *  works: EnderDragonFight.init() (world load) calls findOrCreateDragon(),
     *  which ADOPTS any dragon entity present in the loaded End. So: spawn a
     *  dragon in the End, save-all flush, restart the server; the fight
     *  adopts it on load. Verified from the 26.3 server bytecode — the
     *  no-arg initiateRespawn, setRespawnPhase, previouslyKilled dance and
     *  summon-link all fail because dragonKilled (internal, un-settable)
     *  stays false on a fresh fight. */
    private void stageDragonForAdoption() {
        World end = endWorld();
        var battle = end.getEnderDragonBattle();
        log("stage: current battle dragon=%s previouslyKilled=%s portal=%s",
                battle.getEnderDragon() != null ? "present" : "null",
                battle.hasBeenPreviouslyKilled(),
                battle.getEndPortalLocation() != null);
        EnderDragon d = end.spawn(new Location(end, 0, 80, 0), EnderDragon.class);
        log("stage: dragon staged at (0,80,0) uuid=%s — dispatching save-all",
                d.getUniqueId());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        log("stage: NEXT STEPS: stop the server, start it again, then run "
                + "'dxexp status' — the fight's init() should adopt the staged "
                + "dragon (findOrCreateDragon); init() will also set "
                + "previouslyKilled=true from the active exit portal, so run "
                + "'dxexp victory' (it restores previouslyKilled=false first "
                + "for first-victory observation).");
    }

    private void status() {
        World end = Bukkit.getWorld("world_the_end");
        var battle = end == null ? null : end.getEnderDragonBattle();
        if (battle != null) {
            log("status: battle previouslyKilled=%s respawnPhase=%s gatewayCount=%d "
                    + "dragon=%s", battle.hasBeenPreviouslyKilled(),
                    battle.getRespawnPhase(), battle.getGatewayCount(),
                    battle.getEnderDragon() != null
                            ? battle.getEnderDragon().getUniqueId() : "null");
        } else {
            log("status: no end world loaded (run dxexp setup)");
        }
        EnderDragon d = managed();
        if (d != null) {
            log("status: managed uuid=%s loc=%s phase=%s health=%.1f aware=%s "
                    + "passengers=%d podium=%s deathTicks=%d",
                    d.getUniqueId(), shortPos(d.getLocation()), d.getPhase(),
                    d.getHealth(), d.isAware(), d.getPassengers().size(),
                    shortPos(d.getPodium()), d.getDeathAnimationTicks());
        } else {
            log("status: no managed dragon (run dxexp spawn)");
        }
        log("status: eggEvent=%s managedDeathTick=%d battleDeathTick=%d "
                + "removal=%s@%d fireballHit=%s explosionsNearArena=%d",
                eggEvent != null ? shortPos(eggEvent.getBlock().getLocation()) : "none",
                managedDeathTick, battleDeathTick,
                lastRemoval != null ? lastRemoval.getCause() : "none", lastRemovalTick,
                fireballHit != null, blockExplosionsNearArena);
    }

    private void spawnManaged() {
        World w = overworld();
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                w.setChunkForceLoaded(cx, cz, true);
            }
        }
        managedDeath = null;
        managedDeathTick = -1;
        EnderDragon d = w.spawn(new Location(w, 0, 100, 0), EnderDragon.class);
        setManaged(d);
        log("spawn: MANAGED_DRAGON uuid=%s at %s phase=%s health=%.1f battle=%s "
                + "deathAnimationTicks=%d", d.getUniqueId(), shortPos(d.getLocation()),
                d.getPhase(), d.getHealth(),
                d.getDragonBattle() != null ? "non-null!" : "null",
                d.getDeathAnimationTicks());
    }

    private void report() {
        log("REPORT BEGIN =========================================");
        status();
        log("REPORT lastEntitiesLoad=%s", lastEntitiesLoad);
        log("REPORT lastEntitiesUnload=%s", lastEntitiesUnload);
        log("REPORT lastAddToWorld=%s", lastAddToWorld);
        if (managedDeath != null) {
            log("REPORT E3 managedDeath droppedExp=%d drops=%d killer=%s atTick=%d",
                    managedDeath.getDroppedExp(), managedDeath.getDrops().size(),
                    managedDeath.getEntity().getKiller(), managedDeathTick);
        }
        if (battleDeath != null) {
            log("REPORT E4 battleDeath droppedExp=%d drops=%d atTick=%d",
                    battleDeath.getDroppedExp(), battleDeath.getDrops().size(),
                    battleDeathTick);
        }
        if (eggEvent != null) {
            log("REPORT egg event fired atTick=%d loc=%s", eggEventTick,
                    shortPos(eggEvent.getBlock().getLocation()));
        }
        if (lastRemoval != null) {
            log("REPORT removal cause=%s atTick=%d (removed-entity type=%s)",
                    lastRemoval.getCause(), lastRemovalTick,
                    lastRemoval.getEntity().getType());
        }
        log("REPORT END ===========================================");
    }
}
