package net.zaminmc.rebellion.items;

import java.util.logging.Logger;
import net.zaminmc.rebellion.api.RebellionApi;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
import net.zaminmc.rebellion.api.module.ModuleState;
import net.zaminmc.rebellion.items.command.ItemsCommand;
import net.zaminmc.rebellion.items.lore.AnnotationListener;
import net.zaminmc.rebellion.items.menu.MenuCommandBridge;
import net.zaminmc.rebellion.items.menu.MenuListener;
import net.zaminmc.rebellion.items.menu.MenuSpec;
import net.zaminmc.rebellion.items.menu.MenuHost;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RebellionItems entry point.
 *
 * <p>Enable order: load config + rarities + menus (fail = disable, Core keeps
 * running), look up Core's {@link ModuleRegistry} via the shared API, register
 * module health, then listeners and commands. Disable mirrors the order.</p>
 */
public final class RebellionItemsPlugin extends JavaPlugin {

    private ItemsModuleState state;
    private AnnotationListener annotationListener;
    private MenuCommandBridge menuCommands;

    @Override
    public void onEnable() {
        Logger logger = getLogger();
        try {
            state = new ItemsModuleState(getDataFolder().toPath(), logger);
            var issues = state.bootstrap();
            if (!issues.isEmpty()) {
                issues.forEach(issue -> logger.severe("  " + issue));
                throw new IllegalStateException("configuration invalid; see issues above");
            }
        } catch (Exception e) {
            logger.severe("RebellionItems failed to enable: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Core integration: register module health + share the annotator
        // through the ServicesManager so other Rebellion modules can resolve it.
        try {
            ModuleRegistry modules = RebellionApi.require(ModuleRegistry.class);
            modules.register(new ModuleDescriptor("items", "RebellionItems",
                    getPluginMeta().getVersion()));
            modules.update("items", ModuleState.OPERATIONAL, "");
            getServer().getServicesManager().register(
                    net.zaminmc.rebellion.items.lore.AnnotateService.class,
                    state.annotator(), this, org.bukkit.plugin.ServicePriority.Normal);
            // Placeholder contribution: reachable through the core expansion
            // when PlaceholderAPI is installed; harmless no-op otherwise.
            getServer().getServicesManager().register(
                    net.zaminmc.rebellion.api.placeholder.PlaceholderContributor.class,
                    new net.zaminmc.rebellion.items.command.ItemsPlaceholders(state.rarities()),
                    this, org.bukkit.plugin.ServicePriority.Normal);
        } catch (Exception e) {
            logger.severe("RebellionAPI/RebellionCore services unavailable: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Listeners.
        annotationListener = new AnnotationListener(state.annotator(), state.config(), logger);
        getServer().getPluginManager().registerEvents(annotationListener, this);
        getServer().getPluginManager().registerEvents(
                new MenuListener(logger), this);

        // Commands.
        PluginCommand items = getCommand("rebellionitems");
        PluginCommand menu = getCommand("rebellionmenu");
        if (items == null || menu == null) {
            logger.severe("commands missing from plugin.yml; refusing to enable");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ModuleRegistry modules = RebellionApi.require(ModuleRegistry.class);

        // Dynamic per-menu open_command registration (Paper CommandMap API).
        // The opener resolves the live spec at execution time, so menu reloads
        // take effect without re-registering commands.
        menuCommands = new MenuCommandBridge(logger, (player, menuId) -> openMenuFor(player, menuId));
        ItemsCommand executor = new ItemsCommand(state, modules, menuCommands, logger);
        items.setExecutor(executor);
        items.setTabCompleter(executor);
        menu.setExecutor(executor);
        menu.setTabCompleter(executor);
        menuCommands.sync(getServer().getCommandMap(), state.menus().active());

        logger.info("RebellionItems " + getPluginMeta().getVersion() + " enabled (mode: "
                + state.config().annotationMode() + ", menus: "
                + state.menus().active().size() + ").");
    }

    @Override
    public void onDisable() {
        if (menuCommands != null) {
            menuCommands.unregisterAll(getServer().getCommandMap());
        }
        // Snapshot update so /rebellion modules reflects reality during shutdown.
        RebellionApi.find(ModuleRegistry.class)
                .ifPresent(registry -> registry.update("items", ModuleState.DISABLED, "shutdown"));
        getServer().getServicesManager().unregisterAll(this);
        annotationListener = null;
        state = null;
        getLogger().info("RebellionItems disabled.");
    }

    /** Live-spec menu open shared by /rebellionmenu and per-menu commands. */
    private boolean openMenuFor(Player player, String menuId) {
        MenuSpec spec = state == null ? null : state.menus().active().get(menuId);
        if (spec == null) {
            return false;
        }
        // Defense in depth: the dynamic command already checked the node.
        if (!player.hasPermission("rebellion.items.menu." + menuId)) {
            return false;
        }
        new MenuHost(spec, player, state.config().allowConsoleActions(),
                getLogger()).open();
        return true;
    }
}
