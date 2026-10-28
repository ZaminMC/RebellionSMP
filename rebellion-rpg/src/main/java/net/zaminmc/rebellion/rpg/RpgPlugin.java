package net.zaminmc.rebellion.rpg;

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
import net.zaminmc.rebellion.rpg.command.RitualCommand;
import net.zaminmc.rebellion.rpg.command.RpgAdminCommand;
import net.zaminmc.rebellion.rpg.command.RpgPlaceholders;
import net.zaminmc.rebellion.rpg.controller.RpgControllerConfig;
import net.zaminmc.rebellion.rpg.controller.SoulController;
import net.zaminmc.rebellion.rpg.db.RpgSchema;
import net.zaminmc.rebellion.rpg.listener.RpgCacheListener;
import net.zaminmc.rebellion.rpg.listener.SoulListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionRPG entry point (Phase 7, controller slice — ADR-0016/0017).
 * Enable order mirrors the established module pattern: load + validate
 * config.yml (definition families + the optional controller section;
 * fail = disable, Core keeps running), load the ADR-0012 message
 * catalog, resolve Core's {@link RebellionDatabase} through the shared
 * API, apply the module's namespaced migration (v1, fail-closed), then
 * wire the controller surface — the soul spawner, the soul protection/
 * interaction listener, the periodic signal pulse and reconciliation
 * sweep — and register commands, the placeholder contribution and
 * module health.
 *
 * <p>The spawner is deliberately born quiet: the shipped encounter
 * definition stays {@code enabled: false} until the operator calibrates
 * its world/biome/time data, so enabling this plugin alone never spawns
 * souls unchecked (master prompt §5.3).</p>
 *
 * <p>Repeating tasks, their owners and cancellation: the soul spawner
 * ({@code controller.spawn-interval-seconds}), the signal pulse
 * ({@code controller.signal-interval-seconds}; 0 = not registered) and
 * the reconciliation sweep ({@code controller.sweep-interval-seconds}),
 * all owned by this plugin and cancelled in onDisable after one final
 * sweep.</p>
 */
public final class RpgPlugin extends JavaPlugin {

    private SoulController controller;
    private RpgPlaceholders placeholders;
    private BukkitTask spawnTask;
    private BukkitTask signalTask;
    private BukkitTask sweepTask;

    @Override
    public void onEnable() {
        var logger = getLogger();
        ModuleRegistry modules;
        RebellionDatabase database;
        RpgConfig config;
        RpgControllerConfig controllerConfig;

        // 1. Configuration + messages ----------------------------------------
        try {
            var dataDirectory = getDataFolder().toPath();
            Files.createDirectories(dataDirectory);
            var configPath = dataDirectory.resolve("config.yml");
            if (!Files.exists(configPath)) {
                saveResource("config.yml", false);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            // loadFromString, NOT load(String): the String overload resolves
            // a PATH, not YAML content (defect caught live on Paper 26.3).
            yaml.loadFromString(Files.readString(configPath));
            var loaded = RpgConfig.loadResult(yaml);
            if (!loaded.valid()) {
                loaded.issues().forEach(issue -> logger.severe("  " + issue));
                throw new IllegalStateException("config.yml invalid (see issues above)");
            }
            config = loaded.config();
            var controllerLoaded = RpgControllerConfig.load(yaml);
            if (!controllerLoaded.valid()) {
                controllerLoaded.issues().forEach(issue -> logger.severe("  " + issue));
                throw new IllegalStateException(
                        "config.yml controller section invalid (see issues above)");
            }
            controllerConfig = controllerLoaded.config();

            // messages.yml (ADR-0012): unreadable = enable failure (caught
            // below); missing keys / invalid templates are warnings.
            MessageCatalog.LoadResult messagesResult = MessageCatalog.load(
                    dataDirectory.resolve("messages.yml"), "messages.yml",
                    RpgPlugin.class, logger);
            messagesResult.issues().forEach(issue -> logger.warning("messages.yml: " + issue));
            MessageCatalog messages = messagesResult.catalog();

            // 2. Core services ------------------------------------------------
            database = RebellionApi.require(RebellionDatabase.class);
            modules = RebellionApi.require(ModuleRegistry.class);

            // 3. Module migration (fail-closed; Core keeps running) ----------
            database.applyModuleMigrations("rpg", List.of(RpgSchema.v1()))
                    .get(60, TimeUnit.SECONDS);

            // 4. Service + controller surface ----------------------------------
            RpgService service = new RpgService(database, config,
                    System::currentTimeMillis, java.util.concurrent.ThreadLocalRandom
                            .current()::nextDouble);
            controller = new SoulController(this, service, config, controllerConfig,
                    messages);
            placeholders = new RpgPlaceholders(service, messages);

            modules.register(new ModuleDescriptor("rpg", "RebellionRPG",
                    getPluginMeta().getVersion()));

            // 5. Listeners + tasks ------------------------------------------------
            var pm = getServer().getPluginManager();
            pm.registerEvents(new SoulListener(controller), this);
            pm.registerEvents(new RpgCacheListener(placeholders), this);
            placeholders.loadAllOnline(
                    new java.util.ArrayList<>(getServer().getOnlinePlayers()));

            sweepTask = getServer().getScheduler().runTaskTimer(this, controller::sweep,
                    controllerConfig.sweepIntervalSeconds() * 20L,
                    controllerConfig.sweepIntervalSeconds() * 20L);
            if (controllerConfig.spawnIntervalSeconds() > 0) {
                spawnTask = getServer().getScheduler().runTaskTimer(
                        this, controller::spawnTick,
                        controllerConfig.spawnIntervalSeconds() * 20L,
                        controllerConfig.spawnIntervalSeconds() * 20L);
            }
            if (controllerConfig.signalIntervalSeconds() > 0) {
                signalTask = getServer().getScheduler().runTaskTimer(
                        this, controller::signalPulse,
                        controllerConfig.signalIntervalSeconds() * 20L,
                        controllerConfig.signalIntervalSeconds() * 20L);
            }

            // 6. Commands ------------------------------------------------------------
            PluginCommand ritual = getCommand("ritual");
            PluginCommand rpgAdmin = getCommand("rpgadmin");
            if (ritual == null || rpgAdmin == null) {
                logger.severe("commands missing from plugin.yml; refusing to enable");
                markFailed(modules, "commands missing from plugin.yml");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            RitualCommand ritualExecutor = new RitualCommand(this, service, config,
                    messages, controller);
            ritual.setExecutor(ritualExecutor);
            ritual.setTabCompleter(ritualExecutor);
            RpgAdminCommand adminExecutor = new RpgAdminCommand(service, ritualExecutor);
            rpgAdmin.setExecutor(adminExecutor);
            rpgAdmin.setTabCompleter(adminExecutor);

            // 7. Placeholders + module health ---------------------------------------
            getServer().getServicesManager().register(PlaceholderContributor.class,
                    placeholders, this, ServicePriority.Normal);

            int enabledEncounters = (int) config.encounters().values().stream()
                    .filter(EncounterDefinition::enabled).count();
            modules.update("rpg", ModuleState.OPERATIONAL, "");
            logger.info("RebellionRPG " + getPluginMeta().getVersion() + " enabled ("
                    + config.rituals().size() + " ritual definitions, "
                    + enabledEncounters + "/" + config.encounters().size()
                    + " encounter definitions enabled - the spawner only runs "
                    + "enabled definitions, so no soul spawns until the "
                    + "operator calibrates them).");
        } catch (Exception e) {
            logger.severe("RebellionRPG failed to enable: " + e.getMessage());
            markFailed(RebellionApi.find(ModuleRegistry.class).orElse(null),
                    e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (signalTask != null) {
            signalTask.cancel();
            signalTask = null;
        }
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        // Final reconciliation while Core's database is still up (plugins
        // disable in reverse order): stale claims, expired encounters and
        // leftover soul entities are settled before shutdown.
        if (controller != null) {
            try {
                controller.sweep();
            } catch (Exception e) {
                getLogger().warning("final reconciliation sweep failed: "
                        + e.getMessage());
            }
        }
        RebellionApi.find(ModuleRegistry.class)
                .ifPresent(registry -> registry.health("rpg")
                        .filter(h -> h.state() != ModuleState.FAILED)
                        .ifPresent(h -> registry.update("rpg", ModuleState.DISABLED,
                                "shutdown")));
        getServer().getServicesManager().unregisterAll(this);
        controller = null;
        placeholders = null;
        getLogger().info("RebellionRPG disabled.");
    }

    private void markFailed(@Nullable ModuleRegistry modules, String reason) {
        if (modules != null) {
            modules.register(new ModuleDescriptor("rpg", "RebellionRPG",
                    getPluginMeta().getVersion()));
            modules.update("rpg", ModuleState.FAILED, reason);
        }
    }
}
