package net.zaminmc.rebellion.items;

import java.util.logging.Logger;
import net.zaminmc.rebellion.api.RebellionApi;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
import net.zaminmc.rebellion.api.module.ModuleState;
import net.zaminmc.rebellion.items.command.ItemsCommand;
import net.zaminmc.rebellion.items.lore.AnnotationListener;
import net.zaminmc.rebellion.items.menu.MenuListener;
import org.bukkit.command.PluginCommand;
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
        ItemsCommand executor = new ItemsCommand(state, modules, logger);
        items.setExecutor(executor);
        items.setTabCompleter(executor);
        menu.setExecutor(executor);
        menu.setTabCompleter(executor);

        logger.info("RebellionItems " + getPluginMeta().getVersion() + " enabled (mode: "
                + state.config().annotationMode() + ", menus: "
                + state.menus().active().size() + ").");
    }

    @Override
    public void onDisable() {
        // Snapshot update so /rebellion modules reflects reality during shutdown.
        RebellionApi.find(ModuleRegistry.class)
                .ifPresent(registry -> registry.update("items", ModuleState.DISABLED, "shutdown"));
        getServer().getServicesManager().unregisterAll(this);
        annotationListener = null;
        state = null;
        getLogger().info("RebellionItems disabled.");
    }
}
