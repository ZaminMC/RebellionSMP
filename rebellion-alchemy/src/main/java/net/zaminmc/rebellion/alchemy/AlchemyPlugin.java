package net.zaminmc.rebellion.alchemy;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.zaminmc.rebellion.alchemy.command.AlchemyAdminCommand;
import net.zaminmc.rebellion.alchemy.command.AlchemyPlaceholders;
import net.zaminmc.rebellion.alchemy.command.BrewingCommand;
import net.zaminmc.rebellion.alchemy.controller.AlchemyControllerConfig;
import net.zaminmc.rebellion.alchemy.controller.AlchemyPlayerCache;
import net.zaminmc.rebellion.alchemy.controller.AutoConsumeController;
import net.zaminmc.rebellion.alchemy.controller.Bags;
import net.zaminmc.rebellion.alchemy.db.AlchemySchema;
import net.zaminmc.rebellion.alchemy.listener.AlchemyCacheListener;
import net.zaminmc.rebellion.alchemy.listener.BagListener;
import net.zaminmc.rebellion.alchemy.listener.BrewListener;
import net.zaminmc.rebellion.alchemy.listener.DrinkListener;
import net.zaminmc.rebellion.api.RebellionApi;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
import net.zaminmc.rebellion.api.module.ModuleState;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionAlchemy entry point (Phase 8, controller slice —
 * ADR-0019/0020). Enable order mirrors the established module pattern:
 * load + validate config.yml (four definition families + the optional
 * controller section; fail = disable, Core keeps running), load the
 * ADR-0012 message catalog, resolve Core's {@link RebellionDatabase}
 * through the shared API, apply the module's namespaced migration (v1,
 * fail-closed), then wire the controller surface — the brewing-stand XP
 * listener, the drink-time POWER boost, the potion-bag surface, the
 * auto-consume evaluator — and register commands, the placeholder
 * contribution and module health.
 *
 * <p>Repeating tasks, their owners and cancellation: the auto-consume
 * evaluator ({@code controller.auto-eval-interval-seconds}) and the
 * stale-claim sweep ({@code controller.sweep-interval-seconds}), both
 * owned by this plugin and cancelled in onDisable after one final
 * sweep.</p>
 */
public final class AlchemyPlugin extends JavaPlugin {

    private AlchemyService service;
    private AlchemyPlaceholders placeholders;
    private AutoConsumeController autoConsume;
    private AlchemyControllerConfig controllerConfig;
    private BukkitTask evalTask;
    private BukkitTask sweepTask;

    @Override
    public void onEnable() {
        var logger = getLogger();
        ModuleRegistry modules;
        RebellionDatabase database;
        AlchemyConfig config;
        AlchemyControllerConfig controllerConfig;

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
            var loaded = AlchemyConfig.loadResult(yaml);
            if (!loaded.valid()) {
                loaded.issues().forEach(issue -> logger.severe("  " + issue));
                throw new IllegalStateException("config.yml invalid (see issues above)");
            }
            config = loaded.config();
            var controllerLoaded = AlchemyControllerConfig.load(yaml);
            if (!controllerLoaded.valid()) {
                controllerLoaded.issues().forEach(issue ->
                        logger.severe("  " + issue));
                throw new IllegalStateException(
                        "config.yml controller section invalid (see issues above)");
            }
            controllerConfig = controllerLoaded.config();
            this.controllerConfig = controllerConfig;

            // messages.yml (ADR-0012): unreadable = enable failure (caught
            // below); missing keys / invalid templates are warnings.
            MessageCatalog.LoadResult messagesResult = MessageCatalog.load(
                    dataDirectory.resolve("messages.yml"), "messages.yml",
                    AlchemyPlugin.class, logger);
            messagesResult.issues().forEach(issue ->
                    logger.warning("messages.yml: " + issue));
            MessageCatalog messages = messagesResult.catalog();

            // 2. Core services ------------------------------------------------
            database = RebellionApi.require(RebellionDatabase.class);
            modules = RebellionApi.require(ModuleRegistry.class);

            // 3. Module migration (fail-closed; Core keeps running) ----------
            database.applyModuleMigrations("alchemy", AlchemySchema.migrations())
                    .get(60, TimeUnit.SECONDS);

            // 4. Service + controller surface ----------------------------------
            service = new AlchemyService(database, config,
                    System::currentTimeMillis);
            AlchemyPlayerCache cache = new AlchemyPlayerCache(service);
            Bags bags = new Bags(this);
            autoConsume = new AutoConsumeController(this, service, config,
                    controllerConfig, cache, bags, messages);
            placeholders = new AlchemyPlaceholders(cache, messages);

            modules.register(new ModuleDescriptor("alchemy", "RebellionAlchemy",
                    getPluginMeta().getVersion()));

            // 5. Listeners + tasks ------------------------------------------------
            var pm = getServer().getPluginManager();
            pm.registerEvents(new BrewListener(this, service, cache,
                    config.brewing()), this);
            pm.registerEvents(new DrinkListener(config, cache), this);
            pm.registerEvents(new BagListener(this, config, cache, bags, messages),
                    this);
            pm.registerEvents(new AlchemyCacheListener(cache), this);
            placeholders.loadAllOnline(
                    new java.util.ArrayList<>(getServer().getOnlinePlayers()));

            long sweepTicks = controllerConfig.sweepIntervalSeconds() * 20L;
            sweepTask = getServer().getScheduler().runTaskTimer(this,
                    this::sweep, sweepTicks, sweepTicks);
            long evalTicks = controllerConfig.autoEvalIntervalSeconds() * 20L;
            evalTask = getServer().getScheduler().runTaskTimer(this, autoConsume,
                    evalTicks, evalTicks);

            // 6. Commands ------------------------------------------------------------
            PluginCommand brewing = getCommand("brewing");
            PluginCommand admin = getCommand("alchemyadmin");
            if (brewing == null || admin == null) {
                logger.severe("commands missing from plugin.yml; refusing to enable");
                markFailed(modules, "commands missing from plugin.yml");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            BrewingCommand brewingExecutor = new BrewingCommand(this, service,
                    config, cache, messages);
            brewing.setExecutor(brewingExecutor);
            brewing.setTabCompleter(brewingExecutor);
            AlchemyAdminCommand adminExecutor = new AlchemyAdminCommand(this,
                    service, config, cache, bags, messages);
            admin.setExecutor(adminExecutor);
            admin.setTabCompleter(adminExecutor);

            // 7. Placeholders + module health ---------------------------------------
            getServer().getServicesManager().register(PlaceholderContributor.class,
                    placeholders, this, ServicePriority.Normal);

            int enabledUnlocks = (int) config.unlocks().values().stream()
                    .filter(UnlockDefinition::enabled).count();
            modules.update("alchemy", ModuleState.OPERATIONAL, "");
            logger.info("RebellionAlchemy " + getPluginMeta().getVersion()
                    + " enabled (" + config.unlocks().size()
                    + " unlock definitions (" + enabledUnlocks + " enabled), "
                    + config.bags().size() + " bag tiers, "
                    + config.autoPolicy().allowedPotionTypes().size()
                    + " whitelisted auto-consume potions, evaluator every "
                    + controllerConfig.autoEvalIntervalSeconds() + "s).");
        } catch (Exception e) {
            logger.severe("RebellionAlchemy failed to enable: " + e.getMessage());
            markFailed(RebellionApi.find(ModuleRegistry.class).orElse(null),
                    e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (evalTask != null) {
            evalTask.cancel();
            evalTask = null;
        }
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        // Final stale-claim release while Core's database is still up
        // (plugins disable in reverse order).
        try {
            sweep();
        } catch (Exception e) {
            getLogger().warning("final stale-claim sweep failed: " + e.getMessage());
        }
        RebellionApi.find(ModuleRegistry.class)
                .ifPresent(registry -> registry.health("alchemy")
                        .filter(h -> h.state() != ModuleState.FAILED)
                        .ifPresent(h -> registry.update("alchemy", ModuleState.DISABLED,
                                "shutdown")));
        getServer().getServicesManager().unregisterAll(this);
        service = null;
        placeholders = null;
        autoConsume = null;
        getLogger().info("RebellionAlchemy disabled.");
    }

    /** The stale-claim sweep (ADR-0019 crash-recovery contract). */
    private void sweep() {
        if (service == null) {
            return;
        }
        try {
            long floorSeconds = controllerConfig == null
                    ? AlchemyControllerConfig.defaults().reservationStaleSeconds()
                    : controllerConfig.reservationStaleSeconds();
            var released = service.releaseStaleClaims(floorSeconds * 1000L)
                    .get(10, TimeUnit.SECONDS);
            if (released > 0) {
                getLogger().info("stale-claim sweep released " + released
                        + " reservation(s) - digests retained as refund evidence");
            }
        } catch (Exception e) {
            getLogger().warning("stale-claim sweep failed: " + e.getMessage());
        }
    }

    private void markFailed(@Nullable ModuleRegistry modules, String reason) {
        if (modules != null) {
            modules.register(new ModuleDescriptor("alchemy", "RebellionAlchemy",
                    getPluginMeta().getVersion()));
            modules.update("alchemy", ModuleState.FAILED, reason);
        }
    }
}
