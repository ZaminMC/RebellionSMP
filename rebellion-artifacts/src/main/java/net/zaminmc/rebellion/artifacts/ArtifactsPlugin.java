package net.zaminmc.rebellion.artifacts;

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
import net.zaminmc.rebellion.artifacts.command.ArtifactAdminCommand;
import net.zaminmc.rebellion.artifacts.db.ArtifactsSchema;
import net.zaminmc.rebellion.artifacts.command.ArtifactCommand;
import net.zaminmc.rebellion.artifacts.command.ArtifactsPlaceholders;
import net.zaminmc.rebellion.artifacts.event.RebellionObserver;
import net.zaminmc.rebellion.artifacts.flow.ArtifactFlowListener;
import net.zaminmc.rebellion.artifacts.item.ArtifactItems;
import net.zaminmc.rebellion.artifacts.origin.OriginListener;
import net.zaminmc.rebellion.artifacts.origin.OriginPresentation;
import net.zaminmc.rebellion.artifacts.purge.PurgeExecutor;
import net.zaminmc.rebellion.artifacts.sweep.ArtifactsSweep;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionArtifacts entry point (Phase 6, controller slice — ADR-0015).
 * Enable order mirrors the established module pattern: load + validate
 * config.yml (fail = disable, Core keeps running), load the ADR-0012
 * message catalog, resolve Core's {@link RebellionDatabase}, apply the
 * module's migrations (v1 registry + v2 purge stamp; fail-closed), seed +
 * load the mirror (exactly three rows, config anchors re-synced), then
 * wire the controller surface: the protected-origin presentation +
 * capture, the physical item flows, the Rebellion observer, the gated
 * purge, the maintenance sweep — and register commands, placeholders and
 * module health.
 *
 * <p>The sweep interval is config-owned ({@code sweep.interval-seconds},
 * bounded 5..600). The purge ships config-disabled; enabling it is
 * restart-only and every runtime gate still applies (ADR-0015 §4).</p>
 */
public final class ArtifactsPlugin extends JavaPlugin {

    private ArtifactService service;
    private MessageCatalog messages;
    private BukkitTask sweepTask;

    @Override
    public void onEnable() {
        var logger = getLogger();
        ModuleRegistry modules;
        RebellionDatabase database;
        ArtifactsConfig config;

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
            var loaded = ArtifactsConfig.loadResult(yaml);
            if (!loaded.valid()) {
                loaded.issues().forEach(issue -> logger.severe("  " + issue));
                throw new IllegalStateException("config.yml invalid (see issues above)");
            }
            config = loaded.config();

            MessageCatalog.LoadResult messagesResult = MessageCatalog.load(
                    dataDirectory.resolve("messages.yml"), "messages.yml",
                    ArtifactsPlugin.class, logger);
            messagesResult.issues().forEach(issue ->
                    logger.warning("messages.yml: " + issue));
            messages = messagesResult.catalog();

            // 2. Core services ------------------------------------------------
            database = RebellionApi.require(RebellionDatabase.class);
            modules = RebellionApi.require(ModuleRegistry.class);

            // 3. Module migrations (fail-closed; Core keeps running) -----------
            database.applyModuleMigrations("artifacts", ArtifactsSchema.migrations())
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.severe("RebellionArtifacts failed to enable: " + e.getMessage());
            markFailed(modulesRef(), e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4. Service + seed + mirror ------------------------------------------
        service = new ArtifactService(database, config, System::currentTimeMillis);
        try {
            service.loadAll().get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.severe("RebellionArtifacts could not seed/load the registry: "
                    + e.getMessage());
            markFailed(modules, "registry seed/load failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 5. Controller surface -------------------------------------------------
        ArtifactItems items = new ArtifactItems(this);
        RebellionObserver observer = new RebellionObserver(this, service, messages);
        OriginPresentation presentation = new OriginPresentation(this, service,
                config, items, messages);
        PurgeExecutor purge = new PurgeExecutor(this, service, config, messages);
        ArtifactsSweep sweep = new ArtifactsSweep(this, service, config,
                presentation, observer, messages);

        modules.register(new ModuleDescriptor("artifacts", "RebellionArtifacts",
                getPluginMeta().getVersion()));

        // 6. Listeners ----------------------------------------------------------
        var pm = getServer().getPluginManager();
        pm.registerEvents(new OriginListener(this, service, config, items,
                messages, presentation, observer), this);
        pm.registerEvents(new ArtifactFlowListener(this, service, items,
                messages, observer), this);
        pm.registerEvents(new JoinNoticeListener(observer), this);

        // 7. Task -----------------------------------------------------------------
        long intervalTicks = config.sweepIntervalSeconds() * 20L;
        sweepTask = getServer().getScheduler().runTaskTimer(this, sweep::run,
                intervalTicks, intervalTicks);

        // 8. Commands -------------------------------------------------------------
        PluginCommand artifact = getCommand("artifact");
        PluginCommand artifactAdmin = getCommand("artifactadmin");
        if (artifact == null || artifactAdmin == null) {
            logger.severe("commands missing from plugin.yml; refusing to enable");
            markFailed(modules, "commands missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ArtifactCommand artifactExecutor = new ArtifactCommand(this, service,
                config, items, messages, purge);
        artifact.setExecutor(artifactExecutor);
        artifact.setTabCompleter(artifactExecutor);
        ArtifactAdminCommand adminExecutor = new ArtifactAdminCommand(this, service,
                items, presentation, config, messages);
        artifactAdmin.setExecutor(adminExecutor);
        artifactAdmin.setTabCompleter(adminExecutor);

        // 9. Placeholders + module health ------------------------------------------
        getServer().getServicesManager().register(PlaceholderContributor.class,
                new ArtifactsPlaceholders(service), this, ServicePriority.Normal);

        modules.update("artifacts", ModuleState.OPERATIONAL, "");
        logger.info("RebellionArtifacts " + getPluginMeta().getVersion() + " enabled ("
                + service.count() + " dimension artifacts seeded, sweep every "
                + config.sweepIntervalSeconds() + "s, rebellion event "
                + (config.rebellionEventEnabled() ? "armed" : "disabled")
                + ", purge " + (config.purge().enabled()
                        ? "ENABLED (gated: event + holder + telegraph + confirm)"
                        : "disabled (default)")
                + "). Exactly three artifacts exist; possession is instance-stamped.");
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
                .ifPresent(registry -> registry.health("artifacts")
                        .filter(h -> h.state() != ModuleState.FAILED)
                        .ifPresent(h -> registry.update("artifacts",
                                ModuleState.DISABLED, "shutdown")));
        getServer().getServicesManager().unregisterAll(this);
        service = null;
        messages = null;
        getLogger().info("RebellionArtifacts disabled.");
    }

    /** Join-time notice while a rebellion occurrence is ACTIVE. */
    private record JoinNoticeListener(@NotNull RebellionObserver observer)
            implements Listener {

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            observer.notifyIfActive(event.getPlayer());
        }
    }

    private ModuleRegistry modulesRef() {
        return RebellionApi.find(ModuleRegistry.class).orElse(null);
    }

    private void markFailed(@Nullable ModuleRegistry modules, String reason) {
        if (modules != null) {
            modules.register(new ModuleDescriptor("artifacts", "RebellionArtifacts",
                    getPluginMeta().getVersion()));
            modules.update("artifacts", ModuleState.FAILED, reason);
        }
    }
}
