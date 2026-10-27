package net.zaminmc.rebellion.core;

import java.util.logging.Level;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
import net.zaminmc.rebellion.api.module.ModuleState;
import net.zaminmc.rebellion.core.bootstrap.BootstrapException;
import net.zaminmc.rebellion.core.command.RebellionCommand;
import net.zaminmc.rebellion.core.config.CoreConfigLoader;
import net.zaminmc.rebellion.core.config.DatabaseConfigLoader;
import net.zaminmc.rebellion.core.db.DatabaseService;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import net.zaminmc.rebellion.core.integration.CorePlaceholders;
import net.zaminmc.rebellion.core.integration.RebellionExpansion;
import net.zaminmc.rebellion.core.messages.CoreMessages;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RebellionCore entry point. The enable sequence is strictly ordered — see
 * {@code docs/ARCHITECTURE.md} for the dependency chain and failure behavior:
 *
 * <ol>
 *   <li>load + validate config.yml, messages.yml, database.yml (fail = disable);</li>
 *   <li>start diagnostics service;</li>
 *   <li>construct the database service and begin the async bootstrap
 *       (pool, SQLite pragmas, schema migrations);</li>
 *   <li>register shared services (ModuleRegistry, RebellionDatabase);</li>
 *   <li>register commands and the optional PlaceholderAPI expansion;</li>
 *   <li>mark Core operational. Feature modules enable later (POSTWORLD) and
 *       bound-wait on the database bootstrap through {@code awaitReady}.</li>
 * </ol>
 */
public final class RebellionCorePlugin extends JavaPlugin {

    private CoreState state;
    private DatabaseService database;
    private RebellionExpansion expansion;
    private volatile boolean coreOperational;

    @Override
    public void onEnable() {
        try {
            enable();
            coreOperational = true;
        } catch (BootstrapException e) {
            coreOperational = false;
            // Fail loud, fail closed: log everything actionable, then disable Core.
            getSLF4JLogger().error("Core failed to enable: {}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enable() throws BootstrapException {
        var dataDirectory = getDataFolder().toPath();

        // 1. Configuration ---------------------------------------------------
        CoreConfigLoader.LoadResult coreResult;
        DatabaseConfigLoader.LoadResult dbConfigResult;
        try {
            coreResult = CoreConfigLoader.load(
                    dataDirectory.resolve("config.yml"), getLogger());
            dbConfigResult = DatabaseConfigLoader.load(
                    dataDirectory.resolve("database.yml"), getLogger());
        } catch (net.zaminmc.rebellion.core.config.YamlFileIO.InvalidConfigException e) {
            throw new BootstrapException("configuration file unreadable: " + e.getMessage(), e);
        }
        if (!coreResult.valid()) {
            throw new BootstrapException("config.yml is invalid: "
                    + coreResult.issues().stream()
                            .map(Object::toString).reduce((a, b) -> a + "; " + b).orElse("?"));
        }
        if (!dbConfigResult.valid()) {
            throw new BootstrapException("database.yml is invalid: "
                    + dbConfigResult.issues().stream()
                            .map(Object::toString).reduce((a, b) -> a + "; " + b).orElse("?"));
        }

        CoreMessages messages;
        try {
            CoreMessages.LoadResult messagesResult = CoreMessages.load(
                    dataDirectory.resolve("messages.yml"), getLogger());
            messages = messagesResult.messages();
            // Missing message keys are non-fatal (defaults fill in) but reported.
            messagesResult.issues().forEach(issue ->
                    getLogger().warning("messages.yml: " + issue));
        } catch (net.zaminmc.rebellion.core.config.YamlFileIO.InvalidConfigException e) {
            throw new BootstrapException("messages.yml is unreadable: " + e.getMessage(), e);
        }

        if (coreResult.config().debug()) {
            getLogger().setLevel(Level.FINE);
        }

        // 2. Diagnostics -----------------------------------------------------
        DiagnosticsService diagnostics = new DiagnosticsService(
                coreResult.config(), dataDirectory, getLogger());

        // 3. Database service ------------------------------------------------
        // Constructed here; the bootstrap starts in step 4 so its completion
        // handlers can safely reference the fully built CoreState (the async
        // completion would otherwise race the field assignment).
        database = new DatabaseService(
                dbConfigResult.config(), dataDirectory, getLogger(), diagnostics);

        // 4. State + services ------------------------------------------------
        Plugin apiPlugin = getServer().getPluginManager().getPlugin("RebellionAPI");
        String apiVersion = apiPlugin == null ? "?" : apiPlugin.getPluginMeta().getVersion();
        state = new CoreState(dataDirectory, getPluginMeta().getVersion(), apiVersion,
                coreResult.config(), messages, dbConfigResult.config(), diagnostics, database);

        state.modules().register(new ModuleDescriptor("core", "RebellionCore", getPluginMeta().getVersion()));
        state.modules().update("core", ModuleState.OPERATIONAL, "");

        getServer().getServicesManager().register(
                ModuleRegistry.class, state.modules(), this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                RebellionDatabase.class, database, this, ServicePriority.Normal);

        // Core itself owns no versioned tables yet; migrations of feature
        // modules are registered before their modules enable. Bootstrap starts
        // now — whenComplete fires regardless of whether the async work already
        // finished, and every handler sees a non-null state.
        database.bootstrap().whenComplete((v, err) -> {
            if (err != null) {
                getSLF4JLogger().error("Database bootstrap failed: {}", err.getMessage());
                String incident = diagnostics.incident();
                diagnostics.error(incident, "database bootstrap failed", err instanceof RuntimeException re
                        ? re : new RuntimeException(err));
                state.modules().update("core", ModuleState.FAILED, "database bootstrap failed (" + incident + ")");
            }
        });

        // 5. Commands ---------------------------------------------------------
        PluginCommand command = getCommand("rebellion");
        if (command != null) {
            RebellionCommand executor = new RebellionCommand(state);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            throw new BootstrapException("command 'rebellion' missing from plugin.yml");
        }

        // 6. Optional integrations --------------------------------------------
        CorePlaceholders placeholders = new CorePlaceholders(
                getPluginMeta().getVersion(),
                () -> state.modules().healthyRatio(),
                () -> database.healthy()
                        ? database.backend().name().toLowerCase(java.util.Locale.ROOT)
                        : "offline",
                () -> String.valueOf(state.modules().unhealthy().size()));
        // Core's own parameters are reachable both through the expansion and
        // directly as a registered contributor for tests/embedders.
        getServer().getServicesManager().register(
                net.zaminmc.rebellion.api.placeholder.PlaceholderContributor.class,
                placeholders, this, ServicePriority.Normal);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            expansion = new RebellionExpansion(placeholders);
            expansion.register();
            getLogger().info("PlaceholderAPI detected; expansion 'rebellion' registered.");
        } else {
            getLogger().info("PlaceholderAPI not installed; placeholders stay inactive.");
        }

        getLogger().info("RebellionCore " + getPluginMeta().getVersion()
                + " enabled (backend: " + dbConfigResult.config().backend() + ").");
    }

    @Override
    public void onDisable() {
        coreOperational = false;
        if (state != null) {
            state.modules().update("core", ModuleState.DISABLED, "shutdown");
        }
        if (expansion != null) {
            try {
                expansion.unregister();
            } catch (Throwable t) {
                getLogger().warning("PlaceholderAPI expansion unregister failed: " + t.getMessage());
            }
            expansion = null;
        }
        if (database != null) {
            database.shutdown(); // flushes bounded queue, closes pool, reports leftovers
            database = null;
        }
        if (state != null) {
            getServer().getServicesManager().unregisterAll(this);
            state = null;
        }
        getLogger().info("RebellionCore disabled.");
    }
}
