package net.zaminmc.rebellion.wards;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.zaminmc.rebellion.api.RebellionApi;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.map.MapMarkerProvider;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
import net.zaminmc.rebellion.api.module.ModuleState;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.wards.alert.AlertRouter;
import net.zaminmc.rebellion.wards.alert.TimedMasking;
import net.zaminmc.rebellion.wards.command.WardAdminCommand;
import net.zaminmc.rebellion.wards.command.WardCommand;
import net.zaminmc.rebellion.wards.command.WardsPlaceholders;
import net.zaminmc.rebellion.wards.db.WardsSchema;
import net.zaminmc.rebellion.wards.detect.BukkitMaskingResolver;
import net.zaminmc.rebellion.wards.detect.DetectionState;
import net.zaminmc.rebellion.wards.detect.WardDetector;
import net.zaminmc.rebellion.wards.distance.DistanceService;
import net.zaminmc.rebellion.wards.feed.MovementFeed;
import net.zaminmc.rebellion.wards.index.WardSpatialIndex;
import net.zaminmc.rebellion.wards.listener.AnchorBreakListener;
import net.zaminmc.rebellion.wards.listener.WardMoveListener;
import net.zaminmc.rebellion.wards.map.WardsMapMarkers;
import net.zaminmc.rebellion.wards.task.WardSweepTask;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * RebellionWards entry point (Phase 4).
 *
 * <p>Enable order mirrors the established module pattern: load + validate
 * config.yml (fail = disable, Core keeps running), resolve Core's
 * {@link RebellionDatabase} through the shared API, apply the module's
 * namespaced migration (ADR-0005 semantics; fail-closed), load the ward
 * mirror, then build the pipeline — movement feed (O(1) event marks),
 * cadenced async sweep (spatial index + pure detector), alert router — and
 * register listeners, commands, placeholders and the privacy-gated marker
 * provider.</p>
 *
 * <p>RebellionTeams is soft-depended: without its
 * {@link TeamAffiliationService} every non-owner is an outsider and alerts
 * route to the ward owner only — wards stay fully functional.</p>
 *
 * <p>Wards are information only. Nothing in this module protects land,
 * blocks, containers or players (master prompt §5.2; ADR-0007).</p>
 */
public final class WardsPlugin extends JavaPlugin {

    private WardService service;
    private MovementFeed feed;
    private DetectionState detectionState;
    private BukkitTask sweepTask;
    private MessageCatalog messages;

    @Override
    public void onEnable() {
        var logger = getLogger();
        ModuleRegistry modules;
        RebellionDatabase database;
        WardsConfig config;

        // 1. Configuration + messages ----------------------------------------
        try {
            var dataDirectory = getDataFolder().toPath();
            Files.createDirectories(dataDirectory);
            var configPath = dataDirectory.resolve("config.yml");
            if (!Files.exists(configPath)) {
                saveResource("config.yml", false);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            // loadFromString, NOT load(String): the String overload resolves a
            // PATH, not YAML content (caught live on Paper 26.3 build 226,
            // 2026-10-28 — teams had the identical defect).
            yaml.loadFromString(Files.readString(configPath));
            var loaded = WardsConfig.loadResult(yaml);
            if (!loaded.valid()) {
                loaded.issues().forEach(issue -> logger.severe("  " + issue));
                throw new IllegalStateException("config.yml invalid (see issues above)");
            }
            config = loaded.config();

            // messages.yml (ADR-0012): unreadable = enable failure (caught
            // below, Core keeps running); missing keys or invalid templates
            // are warnings — the bundled default fills in.
            MessageCatalog.LoadResult messagesResult = MessageCatalog.load(
                    dataDirectory.resolve("messages.yml"), "messages.yml",
                    WardsPlugin.class, logger);
            messagesResult.issues().forEach(issue -> logger.warning("messages.yml: " + issue));
            messages = messagesResult.catalog();

            // 2. Core services ------------------------------------------------
            database = RebellionApi.require(RebellionDatabase.class);
            modules = RebellionApi.require(ModuleRegistry.class);

            // 3. Module migrations (fail-closed; Core keeps running) -----------
            database.applyModuleMigrations("wards", List.of(WardsSchema.v1()))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.severe("RebellionWards failed to enable: " + e.getMessage());
            markFailed(modulesRef(), e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4. Pipeline ---------------------------------------------------------
        WardSpatialIndex index = WardSpatialIndex.withDefaultCellSize();
        // Prune horizon: 4x the re-alert cooldown, floored at 1h — idle pairs
        // survive re-entry cooldowns, nothing survives forever.
        long pruneAfterMs = Math.max(config.realertCooldownSeconds() * 4000L, 3_600_000L);
        detectionState = new DetectionState(pruneAfterMs);
        feed = new MovementFeed();
        TeamAffiliationService teams = RebellionApi.find(TeamAffiliationService.class).orElse(null);
        DistanceService distances = new DistanceService(config);
        TimedMasking timedMasking = new TimedMasking();
        service = new WardService(database, config, index, detectionState, teams,
                System::currentTimeMillis);
        WardDetector detector = new WardDetector(config, detectionState, teams,
                new BukkitMaskingResolver(config, timedMasking, getServer()));
        AlertRouter router = new AlertRouter(this, config, distances, messages, teams);

        // 5. Load persisted wards into the mirror ------------------------------
        try {
            service.loadAll().get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.severe("RebellionWards could not load persisted wards: " + e.getMessage());
            markFailed(modules, "ward load failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        modules.register(new ModuleDescriptor("wards", "RebellionWards",
                getPluginMeta().getVersion()));

        // 6. Listeners + sweep -------------------------------------------------
        getServer().getPluginManager().registerEvents(
                new WardMoveListener(feed, service), this);
        getServer().getPluginManager().registerEvents(
                new AnchorBreakListener(this, service, config, messages), this);
        WardSweepTask sweep = new WardSweepTask(config, index, feed, detector,
                detectionState, router);
        sweepTask = getServer().getScheduler().runTaskTimerAsynchronously(this, sweep,
                config.sweepIntervalTicks(), config.sweepIntervalTicks());

        // 7. Commands -----------------------------------------------------------
        PluginCommand ward = getCommand("ward");
        PluginCommand wardAdmin = getCommand("wardadmin");
        if (ward == null || wardAdmin == null) {
            logger.severe("commands missing from plugin.yml; refusing to enable");
            markFailed(modules, "commands missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        WardCommand wardExecutor = new WardCommand(service, config, distances, teams, messages);
        ward.setExecutor(wardExecutor);
        ward.setTabCompleter(wardExecutor);
        WardAdminCommand adminExecutor = new WardAdminCommand(service, config, timedMasking, messages);
        wardAdmin.setExecutor(adminExecutor);
        wardAdmin.setTabCompleter(adminExecutor);

        // 8. Placeholders + markers + module health ---------------------------
        getServer().getServicesManager().register(
                net.zaminmc.rebellion.api.placeholder.PlaceholderContributor.class,
                new WardsPlaceholders(service, distances, teams), this, ServicePriority.Normal);
        getServer().getServicesManager().register(MapMarkerProvider.class,
                new WardsMapMarkers(service, config), this, ServicePriority.Normal);

        modules.update("wards", ModuleState.OPERATIONAL, "");
        logger.info("RebellionWards " + getPluginMeta().getVersion() + " enabled ("
                + index.size() + " wards loaded, sweep every "
                + config.sweepIntervalTicks() + " ticks, anchor "
                + config.anchorMaterial() + ", km calibration "
                + (config.worldUnitsPerKilometer().isEmpty()
                        ? "disabled (reporting " + config.uncalibratedUnit() + ")"
                        : "configured for " + config.worldUnitsPerKilometer().keySet())
                + (teams == null ? ", teams module absent: owner-only routing" : "")
                + "). Wards report information only; they do not protect land.");
    }

    @Override
    public void onDisable() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        RebellionApi.find(ModuleRegistry.class)
                // A FAILED module keeps its failure reason; onDisable must not
                // rewrite it to "shutdown" (module failure would be hidden).
                .ifPresent(registry -> registry.health("wards")
                        .filter(h -> h.state() != ModuleState.FAILED)
                        .ifPresent(h -> registry.update("wards", ModuleState.DISABLED, "shutdown")));
        getServer().getServicesManager().unregisterAll(this);
        // Detection state is working memory; the database is the authority.
        service = null;
        feed = null;
        detectionState = null;
        messages = null;
        getLogger().info("RebellionWards disabled.");
    }

    private ModuleRegistry modulesRef() {
        return RebellionApi.find(ModuleRegistry.class).orElse(null);
    }

    private void markFailed(ModuleRegistry modules, String reason) {
        if (modules != null) {
            modules.register(new ModuleDescriptor("wards", "RebellionWards",
                    getPluginMeta().getVersion()));
            modules.update("wards", ModuleState.FAILED, reason);
        }
    }
}
