package net.zaminmc.rebellion.api;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bootstrap of the RebellionAPI plugin.
 *
 * The plugin exists so the shared API classes have exactly one runtime identity:
 * every feature module declares {@code depend: [RebellionAPI]} and receives these
 * classes through the server classpath. Enabling does nothing beyond confirming
 * that the contract JAR is present and usable.
 */
public final class RebellionApiPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        RebellionApi.markLoaded(this);
        getLogger().info("RebellionAPI contracts available (v" + getPluginMeta().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        RebellionApi.markUnloaded();
    }
}
