package net.zaminmc.rebellion.api;

/**
 * Strategy for locating registered Rebellion services.
 *
 * Implemented by the default Bukkit {@link org.bukkit.plugin.ServicesManager}
 * bridge inside {@link RebellionApi}; tests may provide their own.
 */
@FunctionalInterface
public interface ServiceLookup {

    /** @return the registered provider, or empty when absent. */
    <T> java.util.Optional<T> find(Class<T> type);
}
