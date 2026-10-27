package net.zaminmc.rebellion.core.config;

/**
 * Validated Core configuration. Instances are immutable; reload swaps the whole
 * reference, so a failed reload can never leave a half-applied configuration.
 *
 * @param schemaVersion          config format version for future migrations
 * @param debug                  enables fine-grained diagnostic logging
 * @param startupTimeoutSeconds  bound for how long feature modules wait on the
 *                               database bootstrap at startup
 * @param diagnosticsDirectory   folder name under the Core data directory
 * @param maxReportFiles         report rotation cap
 * @param recentIncidentCapacity in-memory incident ring size
 */
public record CoreConfig(
        int schemaVersion,
        boolean debug,
        int startupTimeoutSeconds,
        String diagnosticsDirectory,
        int maxReportFiles,
        int recentIncidentCapacity) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
