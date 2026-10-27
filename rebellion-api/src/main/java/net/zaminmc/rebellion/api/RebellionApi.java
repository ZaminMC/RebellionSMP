package net.zaminmc.rebellion.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicesManager;

/**
 * Static facade over the service lookup used by all Rebellion modules.
 *
 * <p>The default lookup consults Bukkit's {@link ServicesManager}, which is the
 * one-way registration contract between modules: implementors (RebellionCore and
 * feature modules) register services; consumers look them up. Tests and embedders
 * may {@link #install(ServiceLookup)} their own lookup; the previous lookup is
 * restored with {@link #reset()}.</p>
 *
 * <p>Never call blocking service methods from here on asynchronous threads unless
 * the documented contract of the specific service allows it.</p>
 */
public final class RebellionApi {

    private static volatile ServiceLookup lookup = new BukkitLookup();
    private static volatile boolean loaded;

    private RebellionApi() {
    }

    /** Installs a custom lookup (tests, embedding). Not for normal plugin code. */
    public static void install(ServiceLookup custom) {
        lookup = java.util.Objects.requireNonNull(custom, "custom lookup");
    }

    /** Restores the default Bukkit ServicesManager lookup. */
    public static void reset() {
        lookup = new BukkitLookup();
    }

    /** @return true once the RebellionAPI plugin has enabled on this server. */
    public static boolean isLoaded() {
        return loaded;
    }

    static void markLoaded(RebellionApiPlugin plugin) {
        loaded = true;
    }

    static void markUnloaded() {
        loaded = false;
    }

    /** Finds a registered service, or empty when the providing module is absent/disabled. */
    public static <T> java.util.Optional<T> find(Class<T> type) {
        return lookup.find(type);
    }

    /**
     * Requires a registered service.
     *
     * @throws ServiceUnavailableException when no provider is registered; the
     *         exception message is safe to show to callers/logs (no internals).
     */
    public static <T> T require(Class<T> type) {
        return lookup.find(type).orElseThrow(() -> new ServiceUnavailableException(
                "Service " + type.getName() + " is not available. Is RebellionCore (or the owning module) enabled?"));
    }

    /** Default lookup through Bukkit's ServicesManager. */
    static final class BukkitLookup implements ServiceLookup {
        @Override
        public <T> java.util.Optional<T> find(Class<T> type) {
            return java.util.Optional.ofNullable(
                    org.bukkit.Bukkit.getServicesManager().load(type));
        }
    }
}
