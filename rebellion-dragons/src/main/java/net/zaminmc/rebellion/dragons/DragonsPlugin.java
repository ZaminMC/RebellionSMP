package net.zaminmc.rebellion.dragons;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.zaminmc.rebellion.api.RebellionApi;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
import net.zaminmc.rebellion.api.module.ModuleState;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.dragons.command.DragonAdminCommand;
import net.zaminmc.rebellion.dragons.command.DragonCommand;
import net.zaminmc.rebellion.dragons.command.DragonsPlaceholders;
import net.zaminmc.rebellion.dragons.control.GatewayObserver;
import net.zaminmc.rebellion.dragons.control.PetController;
import net.zaminmc.rebellion.dragons.db.DragonsSchema;
import net.zaminmc.rebellion.dragons.egg.EggItems;
import net.zaminmc.rebellion.dragons.listener.BossVictoryListener;
import net.zaminmc.rebellion.dragons.listener.EggProtectionListener;
import net.zaminmc.rebellion.dragons.listener.PetInteractListener;
import net.zaminmc.rebellion.dragons.listener.PetLifecycleListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionDragons entry point (Phase 5, controller slice). Enable order
 * mirrors the established module pattern: load + validate config.yml (fail
 * = disable, Core keeps running), load the ADR-0012 message catalog, resolve
 * Core's {@link RebellionDatabase} through the shared API, apply the module's
 * namespaced migrations (v1 registry + v2 controller columns; fail-closed),
 * load the lineage mirror, then wire the controller surface — boss-victory
 * gateway observation (ADR-0014), pet lifecycle, egg protection, pet
 * interaction, the periodic persistence sweep — and register commands,
 * placeholders and module health.
 *
 * <p>RebellionTeams is soft-depended: without its
 * {@link TeamAffiliationService} only the owner is shielded from friendly
 * fireball hits; everything else stays fully functional. Riding ships
 * disabled by configuration until the client-side ride-feel gate
 * (ADR-0008) passes.</p>
 *
 * <p>Repeating tasks, their owners and cancellation: the persistence sweep
 * (30 s, this plugin, cancelled in onDisable after one final flush) and —
 * only when riding is enabled — the closed-loop steering task
 * ({@code ride.steer-interval-ticks}, this plugin, cancelled in onDisable).</p>
 */
public final class DragonsPlugin extends JavaPlugin {

    /** Persistence sweep cadence: 600 ticks = 30 s (documented in docs/). */
    static final long SWEEP_INTERVAL_TICKS = 600L;

    private LineageService service;
    private PetController controller;
    private GatewayObserver observer;
    private MessageCatalog messages;
    private BukkitTask sweepTask;
    private BukkitTask steerTask;

    @Override
    public void onEnable() {
        var logger = getLogger();
        ModuleRegistry modules;
        RebellionDatabase database;
        DragonsConfig config;

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
            // PATH, not YAML content (defect caught live on Paper 26.3).
            yaml.loadFromString(Files.readString(configPath));
            var loaded = DragonsConfig.loadResult(yaml);
            if (!loaded.valid()) {
                loaded.issues().forEach(issue -> logger.severe("  " + issue));
                throw new IllegalStateException("config.yml invalid (see issues above)");
            }
            config = loaded.config();

            // messages.yml (ADR-0012): unreadable = enable failure (caught
            // below); missing keys / invalid templates are warnings.
            MessageCatalog.LoadResult messagesResult = MessageCatalog.load(
                    dataDirectory.resolve("messages.yml"), "messages.yml",
                    DragonsPlugin.class, logger);
            messagesResult.issues().forEach(issue -> logger.warning("messages.yml: " + issue));
            messages = messagesResult.catalog();

            // 2. Core services ------------------------------------------------
            database = RebellionApi.require(RebellionDatabase.class);
            modules = RebellionApi.require(ModuleRegistry.class);

            // 3. Module migrations (fail-closed; Core keeps running) -----------
            database.applyModuleMigrations("dragons",
                    List.of(DragonsSchema.v1(), DragonsSchema.v2()))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.severe("RebellionDragons failed to enable: " + e.getMessage());
            markFailed(modulesRef(), e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4. Service + mirror -------------------------------------------------
        service = new LineageService(database, config, System::currentTimeMillis);
        try {
            service.loadAll().get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.severe("RebellionDragons could not load persisted lineages: "
                    + e.getMessage());
            markFailed(modules, "lineage load failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 5. Controller surface ------------------------------------------------
        EggItems eggs = new EggItems(this);
        TeamAffiliationService teams = RebellionApi.find(TeamAffiliationService.class)
                .orElse(null);
        controller = new PetController(this, service, config, eggs, messages, teams);
        observer = new GatewayObserver(this, service, controller, config);

        modules.register(new ModuleDescriptor("dragons", "RebellionDragons",
                getPluginMeta().getVersion()));

        // 6. Listeners ----------------------------------------------------------
        var pm = getServer().getPluginManager();
        pm.registerEvents(new BossVictoryListener(service, observer), this);
        pm.registerEvents(new PetLifecycleListener(this, service, controller), this);
        pm.registerEvents(new EggProtectionListener(service, eggs, this), this);
        pm.registerEvents(new PetInteractListener(service, controller), this);

        // 7. Tasks ---------------------------------------------------------------
        sweepTask = getServer().getScheduler().runTaskTimer(this, controller::sweep,
                SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS);
        if (config.rideEnabled()) {
            // Registered only while riding is enabled: the client-side ride
            // feel is an ADR-0008 interactive gate; enabling is restart-only.
            steerTask = getServer().getScheduler().runTaskTimer(this, controller::steerTick,
                    config.steerIntervalTicks(), config.steerIntervalTicks());
        }

        // 8. Commands ------------------------------------------------------------
        PluginCommand dragon = getCommand("dragon");
        PluginCommand dragonAdmin = getCommand("dragonadmin");
        if (dragon == null || dragonAdmin == null) {
            logger.severe("commands missing from plugin.yml; refusing to enable");
            markFailed(modules, "commands missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        DragonCommand dragonExecutor =
                new DragonCommand(service, controller, config, messages);
        dragon.setExecutor(dragonExecutor);
        dragon.setTabCompleter(dragonExecutor);
        DragonAdminCommand adminExecutor =
                new DragonAdminCommand(service, controller, messages);
        dragonAdmin.setExecutor(adminExecutor);
        dragonAdmin.setTabCompleter(adminExecutor);

        // 9. Placeholders + module health ---------------------------------------
        getServer().getServicesManager().register(PlaceholderContributor.class,
                new DragonsPlaceholders(service), this, ServicePriority.Normal);

        modules.update("dragons", ModuleState.OPERATIONAL, "");
        logger.info("RebellionDragons " + getPluginMeta().getVersion() + " enabled ("
                + service.count() + "/" + service.cap() + " lineages ever minted, "
                + "victory observation " + (config.victoryObservationEnabled() ? "on" : "off")
                + ", riding " + (config.rideEnabled() ? "ENABLED (experimental)"
                        : "disabled (ADR-0008 ride-feel gate)")
                + (teams == null ? ", teams module absent: owner-only fireball shield" : "")
                + "). Lineages are finite and transfer-only; nothing mints past the cap.");
    }

    @Override
    public void onDisable() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        if (steerTask != null) {
            steerTask.cancel();
            steerTask = null;
        }
        // Final flush while Core's database is still up (plugins disable in
        // reverse order): last health/position snapshots for loaded pets.
        if (controller != null) {
            try {
                controller.sweep();
            } catch (Exception e) {
                getLogger().warning("final persistence sweep failed: " + e.getMessage());
            }
        }
        RebellionApi.find(ModuleRegistry.class)
                // A FAILED module keeps its failure reason; onDisable must not
                // rewrite it to "shutdown" (module failure would be hidden).
                .ifPresent(registry -> registry.health("dragons")
                        .filter(h -> h.state() != ModuleState.FAILED)
                        .ifPresent(h -> registry.update("dragons", ModuleState.DISABLED,
                                "shutdown")));
        getServer().getServicesManager().unregisterAll(this);
        service = null;
        controller = null;
        observer = null;
        messages = null;
        getLogger().info("RebellionDragons disabled.");
    }

    private ModuleRegistry modulesRef() {
        return RebellionApi.find(ModuleRegistry.class).orElse(null);
    }

    private void markFailed(@Nullable ModuleRegistry modules, String reason) {
        if (modules != null) {
            modules.register(new ModuleDescriptor("dragons", "RebellionDragons",
                    getPluginMeta().getVersion()));
            modules.update("dragons", ModuleState.FAILED, reason);
        }
    }
}
