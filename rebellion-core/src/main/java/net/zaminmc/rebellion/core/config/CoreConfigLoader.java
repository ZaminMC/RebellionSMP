package net.zaminmc.rebellion.core.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads and validates {@code config.yml}. Validation is all-or-nothing: every
 * issue is collected, and callers only receive a {@link CoreConfig} when the list
 * is empty. A failed load or invalid content yields issues, never a half-config.
 */
public final class CoreConfigLoader {

    public record LoadResult(CoreConfig config, List<ValidationIssue> issues) {
        public boolean valid() {
            return issues.isEmpty();
        }
    }

    private CoreConfigLoader() {
    }

    public static LoadResult load(Path file, Logger logger) throws YamlFileIO.InvalidConfigException {
        YamlConfiguration yaml = YamlFileIO.loadOrCreate(file, "config.yml", logger);
        return parse(yaml);
    }

    /** Parses a candidate configuration without touching the active one (reload path). */
    public static LoadResult parse(YamlConfiguration yaml) {
        List<ValidationIssue> issues = new ArrayList<>();

        int schemaVersion = YamlFileIO.intInRange(
                yaml, "schema-version", 1, CoreConfig.CURRENT_SCHEMA_VERSION, 1, issues);
        if (schemaVersion > CoreConfig.CURRENT_SCHEMA_VERSION) {
            issues.add(new ValidationIssue("schema-version",
                    "config was written for a newer Core (" + schemaVersion + " > "
                            + CoreConfig.CURRENT_SCHEMA_VERSION
                            + "); update RebellionCore instead of downgrading"));
        }

        boolean debug = YamlFileIO.bool(yaml, "debug", false, issues);
        int startupTimeout = YamlFileIO.intInRange(
                yaml, "startup-timeout-seconds", 1, 600, 30, issues);

        String dir = YamlFileIO.string(yaml, "diagnostics.directory", 64, "diagnostics", issues);
        if (!dir.matches("[A-Za-z0-9._-]+")) {
            issues.add(new ValidationIssue("diagnostics.directory",
                    "must be a single safe folder name (letters, digits, . _ -), no slashes"));
        }

        int maxReports = YamlFileIO.intInRange(yaml, "diagnostics.max-report-files", 1, 500, 10, issues);
        int capacity = YamlFileIO.intInRange(
                yaml, "diagnostics.recent-incident-capacity", 10, 10_000, 100, issues);

        if (!issues.isEmpty()) {
            return new LoadResult(null, issues);
        }
        return new LoadResult(
                new CoreConfig(schemaVersion, debug, startupTimeout, dir, maxReports, capacity),
                issues);
    }
}
