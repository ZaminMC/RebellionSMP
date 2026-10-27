package net.zaminmc.rebellion.api.map;

import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Publishes optional web-map markers for a module (master prompt §14).
 *
 * <p>Modules that own mappable objects (wards, ritual sites, artifacts)
 * implement this and register the instance with Bukkit's
 * {@code ServicesManager}; a supported web-map adapter (Phase 10, after
 * integration checks) consumes every registered provider.</p>
 *
 * <h2>Privacy-by-default (mandatory)</h2>
 * <ul>
 *   <li>{@link #markers(String)} returns an empty list unless the provider's
 *       own admin setting explicitly enables <em>that exact category</em>.
 *       Absent setting = disabled; there is no opt-out-inverted default.</li>
 *   <li>Ward locations, player tracking data, secret artifact origins and
 *       private team data are never published through this contract without
 *       an explicit per-category admin opt-in.</li>
 *   <li>Labels must already be sanitized (no MiniMessage tags, no player
 *       provided free text beyond validated names).</li>
 * </ul>
 *
 * <h2>Performance contract</h2>
 * <p>Calls happen on adapter refresh, not on any hot event path, but
 * implementations should still be cheap in-memory reads over an immutable
 * snapshot rather than database queries.</p>
 */
public interface MapMarkerProvider {

    /**
     * Categories this provider could publish (e.g. {@code wards}). The set is
     * static metadata — a listed category may still be admin-disabled and
     * then yield no markers.
     */
    @NotNull Set<String> categories();

    /**
     * Current markers of one category. Empty when the category is unknown or
     * not admin-enabled. The returned list is an immutable snapshot.
     *
     * @param category exact category key (never null)
     */
    @NotNull List<MapMarker> markers(@NotNull String category);
}
