package net.zaminmc.rebellion.api.placeholder;

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
@FunctionalInterface
public interface PlaceholderContributor {

    /**
     * Resolves one parameter (everything after {@code rebellion_}).
     *
     * @return the replacement text, or null when this contributor does not
     *         own the parameter
     */
    String resolve(String params);
}
