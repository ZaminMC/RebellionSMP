package net.zaminmc.rebellion.api.placeholder;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One module's contribution to the shared {@code rebellion} PlaceholderAPI
 * expansion.
 *
 * <p>Modules implement this and register the instance with Bukkit's
 * {@code ServicesManager}; RebellionCore's expansion routes every lookup to
 * the registered contributors. Contracts (master prompt §10):</p>
 *
 * <ul>
 *   <li>Resolution is a pure in-memory read — never query the database.</li>
 *   <li>Return {@code null} for unknown parameters (PlaceholderAPI leaves the
 *       text untouched).</li>
 *   <li>Never expose exact tracking data, secret artifact origins, database
 *       errors or staff-only state.</li>
 * </ul>
 */
public interface PlaceholderContributor {

    /**
     * Resolves one player-independent parameter (everything after
     * {@code rebellion_}).
     *
     * @return the replacement text, or null when this contributor does not
     *         own the parameter
     */
    @Nullable String resolve(@NotNull String params);

    /**
     * Resolves one parameter for a specific viewer. Player-dependent
     * contributors (e.g. team membership) override this; the default
     * ignores the player, preserving the player-independent contract for
     * existing contributors.
     *
     * @param player the viewer PAPI resolved the placeholder for, or null
     *               when a non-player source requested it
     * @return the replacement text, or null when this contributor does not
     *         own the parameter
     */
    default @Nullable String resolveFor(@Nullable Player player, @NotNull String params) {
        return resolve(params);
    }
}
