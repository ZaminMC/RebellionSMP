package net.zaminmc.rebellion.api.module;

/**
 * Lifecycle health state of a module, as reported to the module registry.
 *
 * <ul>
 *   <li>{@link #PENDING} — enabled but not yet finished initializing.</li>
 *   <li>{@link #OPERATIONAL} — all functionality available.</li>
 *   <li>{@link #DEGRADED} — partially available; a required dependency is missing
 *       or persistence is in recovery mode. See the health detail.</li>
 *   <li>{@link #FAILED} — initialization failed; dependent modules must not enable.</li>
 *   <li>{@link #DISABLED} — cleanly disabled by configuration or shutdown.</li>
 * </ul>
 */
public enum ModuleState {
    PENDING,
    OPERATIONAL,
    DEGRADED,
    FAILED,
    DISABLED
}
