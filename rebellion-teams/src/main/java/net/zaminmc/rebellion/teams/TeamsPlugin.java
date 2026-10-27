package net.zaminmc.rebellion.teams;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import net.zaminmc.rebellion.api.RebellionApi;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
import net.zaminmc.rebellion.api.module.ModuleState;
import net.zaminmc.rebellion.teams.chat.ChatTracker;
import net.zaminmc.rebellion.teams.command.ChatCommand;
import net.zaminmc.rebellion.teams.command.TeamCommand;
import net.zaminmc.rebellion.teams.command.TeamsPlaceholders;
import net.zaminmc.rebellion.teams.db.TeamsSchema;
import net.zaminmc.rebellion.teams.listener.ChatListener;
import net.zaminmc.rebellion.teams.listener.FriendlyFireListener;
import net.zaminmc.rebellion.teams.listener.JoinLeaveListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RebellionTeams entry point (Phase 3).
 *
 * <p>Enable order: load + validate config.yml (fail = disable, Core keeps
 * running), resolve Core's {@link RebellionDatabase} through the shared API,
 * apply the module's namespaced migrations (ADR-0005; fail-closed — module
 * refuses to enable on migration failure, Core is unaffected), then build
 * the service, register listeners/commands and mark the module operational.
 * The bounded migration wait happens on the main thread during POSTWORLD
 * enable only; runtime persistence is always async.</p>
 */
public final class TeamsPlugin extends JavaPlugin {

    private TeamService service;
    private ChatTracker chat;

    @Override
    public void onEnable() {
        var logger = getLogger();
        ModuleRegistry modules;
        RebellionDatabase database;

        // 1. Configuration ----------------------------------------------------
        try {
            var dataDirectory = getDataFolder().toPath();
            Files.createDirectories(dataDirectory);
            var config = dataDirectory.resolve("config.yml");
            if (!Files.exists(config)) {
                saveResource("config.yml", false);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(Files.readString(config));
            var loaded = TeamsConfig.loadResult(yaml);
            if (!loaded.valid()) {
                loaded.issues().forEach(issue -> logger.severe("  " + issue));
                throw new IllegalStateException("config.yml invalid (see issues above)");
            }

            // 2. Core services ----------------------------------------------
            database = RebellionApi.require(RebellionDatabase.class);
            modules = RebellionApi.require(ModuleRegistry.class);

            // 3. Module migrations (fail-closed; Core keeps running) ---------
            database.applyModuleMigrations("teams", java.util.List.of(TeamsSchema.v1()))
                    .get(60, TimeUnit.SECONDS);

            // 4. Service + caches --------------------------------------------
            service = new TeamService(database, loaded.config(), new TeamCache());
            chat = new ChatTracker();
        } catch (Exception e) {
            logger.severe("RebellionTeams failed to enable: " + e.getMessage());
            markFailed(modulesRef(), e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        modules.register(new ModuleDescriptor("teams", "RebellionTeams",
                getPluginMeta().getVersion()));

        // 5. Listeners ---------------------------------------------------------
        getServer().getPluginManager().registerEvents(
                new JoinLeaveListener(service, chat, logger), this);
        getServer().getPluginManager().registerEvents(
                new FriendlyFireListener(service, logger), this);
        getServer().getPluginManager().registerEvents(
                new ChatListener(service, chat, logger), this);

        // 6. Commands -----------------------------------------------------------
        PluginCommand team = getCommand("team");
        PluginCommand chatCommand = getCommand("chat");
        if (team == null || chatCommand == null) {
            logger.severe("commands missing from plugin.yml; refusing to enable");
            markFailed(modules, "commands missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        TeamCommand teamExecutor = new TeamCommand(service, logger);
        team.setExecutor(teamExecutor);
        team.setTabCompleter(teamExecutor);
        ChatCommand channelExecutor = new ChatCommand(service, chat);
        chatCommand.setExecutor(channelExecutor);
        chatCommand.setTabCompleter(channelExecutor);

        // 7. Placeholders + module health -------------------------------------
        getServer().getServicesManager().register(
                net.zaminmc.rebellion.api.placeholder.PlaceholderContributor.class,
                new TeamsPlaceholders(service), this, ServicePriority.Normal);

        modules.update("teams", ModuleState.OPERATIONAL, "");
        logger.info("RebellionTeams " + getPluginMeta().getVersion()
                + " enabled (max members " + service.config().maxMembers()
                + ", proximity " + (service.config().proximityEnabled()
                        ? service.config().proximityRadius() + " blocks" : "disabled") + ").");
    }

    @Override
    public void onDisable() {
        // Caches drop; the database is the authority, so nothing is lost.
        if (service != null) {
            service.cache().cachedPlayers().forEach(service::evict);
        }
        RebellionApi.find(ModuleRegistry.class)
                .ifPresent(registry -> registry.update("teams", ModuleState.DISABLED, "shutdown"));
        getServer().getServicesManager().unregisterAll(this);
        service = null;
        chat = null;
        getLogger().info("RebellionTeams disabled.");
    }

    private ModuleRegistry modulesRef() {
        return RebellionApi.find(ModuleRegistry.class).orElse(null);
    }

    private void markFailed(ModuleRegistry modules, String reason) {
        if (modules != null) {
            modules.register(new ModuleDescriptor("teams", "RebellionTeams",
                    getPluginMeta().getVersion()));
            modules.update("teams", ModuleState.FAILED, reason);
        }
    }
}
