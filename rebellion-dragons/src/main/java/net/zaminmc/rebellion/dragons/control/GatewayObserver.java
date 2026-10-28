package net.zaminmc.rebellion.dragons.control;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.zaminmc.rebellion.dragons.DragonsConfig;
import net.zaminmc.rebellion.dragons.Lineage;
import net.zaminmc.rebellion.dragons.LineageOutcome;
import net.zaminmc.rebellion.dragons.LineageService;
import net.zaminmc.rebellion.dragons.control.PetController.Delivery;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Boss-victory gateway observation (ADR-0014). Vanilla owns gateway
 * emission; this observer never synthesizes one. On a legitimate boss
 * death (a dragon with a live {@link DragonBattle}) it:
 *
 * <ol>
 *   <li>records {@code getGatewayCount()} and takes a <em>baseline scan</em>
 *       of the END_GATEWAY blocks in the configured annulus (chunk
 *       snapshots, scanned off the main thread — a victory is rare, the
 *       scan is bounded, and nothing blocks a tick);</li>
 *   <li>polls the (cheap) gateway count every {@code poll-interval-ticks}
 *       inside the observation window;</li>
 *   <li>when vanilla announces new gateways, takes one <em>discovery
 *       scan</em> and asks {@link GatewayDiff} which positions are
 *       legitimately mintable — only positions that emerged inside this
 *       window and do not already back a lineage;</li>
 *   <li>mints each candidate through {@link LineageService#issueFromGateway}
 *       for the credited victor (UNIQUE gateway backstop settles every
 *       race) and delivers the fresh egg; at the lineage cap the outcome is
 *       {@code CAP_REACHED} and vanilla keeps its own reward policy.</li>
 * </ol>
 *
 * <p>Fail-closed everywhere: no credited player killer means no observation
 * (no attribution is invented — the gateway simply stays unminted and a
 * WARN is logged); a scan miss or an ambiguous count/candidate mismatch
 * means no mint, never a guessed position. The exact vanilla gateway
 * emergence timing and ring geometry on 26.3 remain the ADR-0008 E4b
 * interactive gate; the defaults are deliberately generous and a miss is a
 * loud WARN, not a wrong mint.</p>
 */
public final class GatewayObserver {

    private final Plugin plugin;
    private final LineageService lineages;
    private final PetController controller;
    private final DragonsConfig config;

    public GatewayObserver(@NotNull Plugin plugin, @NotNull LineageService lineages,
            @NotNull PetController controller, @NotNull DragonsConfig config) {
        this.plugin = plugin;
        this.lineages = lineages;
        this.controller = controller;
        this.config = config;
    }

    /**
     * Entry point from the boss-victory listener: a dragon with a live
     * battle died. Starts one observation window when observing is enabled
     * and a player is credited with the kill.
     */
    public void onVictory(@NotNull EnderDragon boss, @Nullable Player victor) {
        if (!config.victoryObservationEnabled()) {
            return;
        }
        DragonBattle battle = boss.getDragonBattle();
        if (battle == null) {
            return;
        }
        if (victor == null) {
            plugin.getLogger().warning("an Ender Dragon boss died with no player "
                    + "credited - no lineage egg minted for this victory "
                    + "(fail-closed; the gateway stays unminted)");
            return;
        }
        World world = boss.getWorld();
        Location portal = battle.getEndPortalLocation();
        // Pre-victory the exit portal does not exist (verified live,
        // ADR-0008): the vanilla ring centers on the main island.
        final Location center = portal == null
                ? new Location(world, 0, 64, 0) : portal.clone();
        int baselineCount = battle.getGatewayCount();
        List<Lineage.GatewayKey> registered = registeredAnchors(world.getName());

        scan(world, center, baseline -> startPolling(world, center, battle, victor,
                baselineCount, baseline, registered));
    }

    private List<Lineage.GatewayKey> registeredAnchors(@NotNull String worldName) {
        List<Lineage.GatewayKey> keys = new ArrayList<>();
        for (Lineage lineage : lineages.all()) {
            if (lineage.originWorld().equals(worldName)) {
                keys.add(lineage.gateway());
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // Polling window
    // ------------------------------------------------------------------

    private void startPolling(World world, Location center, DragonBattle battle,
            Player victor, int baselineCount, List<Lineage.GatewayKey> baseline,
            List<Lineage.GatewayKey> registered) {
        long deadline = System.currentTimeMillis() + config.scanWindowMs();
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (System.currentTimeMillis() > deadline) {
                cancel(task);
                plugin.getLogger().info("gateway observation window closed with no "
                        + "new gateway announced (no mint)");
                return;
            }
            int count = battle.getGatewayCount();
            if (count <= baselineCount) {
                return; // vanilla has not announced the new gateway yet
            }
            cancel(task);
            int delta = count - baselineCount;
            scan(world, center, current -> {
                GatewayDiff.Decision decision = GatewayDiff.decide(baseline, current,
                        registered, delta);
                if (decision.warning() != null) {
                    plugin.getLogger().warning("victory observation: " + decision.warning());
                    if (!decision.mintAnything()) {
                        return;
                    }
                }
                mintAll(decision.mint(), victor.getUniqueId());
            });
        }, config.scanPollIntervalTicks(), config.scanPollIntervalTicks());
    }

    private void cancel(BukkitTask[] task) {
        if (task[0] != null) {
            task[0].cancel();
            task[0] = null;
        }
    }

    private void mintAll(List<Lineage.GatewayKey> candidates, UUID victor) {
        for (Lineage.GatewayKey key : candidates) {
            lineages.issueFromGateway(key.world(), key.x(), key.y(), key.z(), victor)
                    .thenAccept(result -> {
                        if (result.outcome() == LineageOutcome.CAP_REACHED) {
                            Player player = Bukkit.getPlayer(victor);
                            if (player != null) {
                                player.sendMessage(controller.messages().msg("victory.cap",
                                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                                                .unparsed("cap",
                                                        String.valueOf(lineages.cap()))));
                            }
                            return;
                        }
                        if (!result.success() || result.lineage() == null) {
                            plugin.getLogger().warning("gateway mint refused: "
                                    + result.outcome() + " (" + result.detail() + ")");
                            return;
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> deliver(victor, result));
                    })
                    .exceptionally(t -> {
                        plugin.getLogger().warning("gateway mint failed: " + t.getMessage());
                        return null;
                    });
        }
    }

    private void deliver(UUID victor, net.zaminmc.rebellion.dragons.LineageResult result) {
        Lineage lineage = result.lineage();
        Delivery outcome = controller.deliverEgg(victor, lineage);
        Player player = Bukkit.getPlayer(victor);
        switch (outcome) {
            case DELIVERED -> {
                if (player != null) {
                    player.sendMessage(controller.messages().msg(result.outcome()
                            == LineageOutcome.ISSUED ? "victory.minted" : "egg.delivered",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                                    .unparsed("slot", String.valueOf(lineage.slot()))));
                }
            }
            case DROPPED -> {
                if (player != null) {
                    player.sendMessage(controller.messages().msg("egg.dropped",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                                    .unparsed("slot", String.valueOf(lineage.slot()))));
                }
            }
            case UNDELIVERABLE -> lineages.markEggUndeliverable(lineage.lineageId(),
                    "victor offline at delivery");
            default -> {
            }
        }
        if (result.mutated()) {
            plugin.getLogger().info("lineage #" + lineage.slot() + " minted for "
                    + victor + " from gateway " + lineage.originWorld() + " ("
                    + lineage.gatewayX() + "," + lineage.gatewayY() + ","
                    + lineage.gatewayZ() + ")");
        }
    }

    // ------------------------------------------------------------------
    // Annulus scan (chunk snapshots, block reads off the main thread)
    // ------------------------------------------------------------------

    /**
     * Scans the configured annulus around {@code center} for END_GATEWAY
     * blocks. Chunk loads are requested through the async chunk API; each
     * loaded chunk is snapshotted (immutable copy) on the main thread, and
     * the block-type sweep runs in one async task. Results are handed to
     * {@code done} on the main thread. Failure to load any chunk degrades
     * to an empty scan — callers see a scan miss and fail closed.
     */
    private void scan(World world, Location center, Consumer<List<Lineage.GatewayKey>> done) {
        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();
        int rMin = config.scanRadiusMinBlocks();
        int rMax = config.scanRadiusMaxBlocks();
        int cMinX = (centerX - rMax) >> 4;
        int cMaxX = (centerX + rMax) >> 4;
        int cMinZ = (centerZ - rMax) >> 4;
        int cMaxZ = (centerZ + rMax) >> 4;

        List<ChunkSnapshot> snapshots = new ArrayList<>();
        List<int[]> pending = new ArrayList<>();
        for (int cx = cMinX; cx <= cMaxX; cx++) {
            for (int cz = cMinZ; cz <= cMaxZ; cz++) {
                // Only chunks whose center can intersect the annulus band.
                double dist = Math.hypot((cx << 4) + 8 - centerX, (cz << 4) + 8 - centerZ);
                if (dist > rMax + 11.4 || dist < rMin - 11.4) {
                    continue;
                }
                pending.add(new int[]{cx, cz});
            }
        }
        AtomicInteger remaining = new AtomicInteger(pending.size());
        if (pending.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> done.accept(List.of()));
            return;
        }
        for (int[] at : pending) {
            world.getChunkAtAsync(at[0], at[1], chunk -> {
                synchronized (snapshots) {
                    snapshots.add(chunk.getChunkSnapshot());
                }
                if (remaining.decrementAndGet() == 0) {
                    List<ChunkSnapshot> copy;
                    synchronized (snapshots) {
                        copy = new ArrayList<>(snapshots);
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        List<Lineage.GatewayKey> found = sweep(world.getName(), copy,
                                centerX, centerZ, rMin, rMax);
                        Bukkit.getScheduler().runTask(plugin, () -> done.accept(found));
                    });
                }
            });
        }
    }

    /** Block sweep over the snapshots; runs off the main thread. */
    private List<Lineage.GatewayKey> sweep(String worldName, List<ChunkSnapshot> snapshots,
            int centerX, int centerZ, int rMin, int rMax) {
        Set<Lineage.GatewayKey> found = new HashSet<>();
        int yMin = Math.max(0, config.scanYMin());
        int yMax = Math.min(255, config.scanYMax());
        for (ChunkSnapshot snapshot : snapshots) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int wx = (snapshot.getX() << 4) + x;
                    int wz = (snapshot.getZ() << 4) + z;
                    double dist = Math.hypot(wx - centerX, wz - centerZ);
                    if (dist < rMin || dist > rMax) {
                        continue;
                    }
                    for (int y = yMin; y <= yMax; y++) {
                        if (snapshot.getBlockType(x, y, z) == Material.END_GATEWAY) {
                            found.add(Lineage.GatewayKey.of(worldName, wx, y, wz));
                            break; // one entry per column is enough for minting
                        }
                    }
                }
            }
        }
        return new ArrayList<>(found);
    }
}
