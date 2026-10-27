package net.zaminmc.rebellion.core.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads and validates {@code database.yml}. All-or-nothing like
 * {@link CoreConfigLoader}. Credentials are environment-expanded
 * ({@code ${VAR}} / {@code ${VAR:fallback}}) and never logged.
 */
public final class DatabaseConfigLoader {

    public record LoadResult(DatabaseConfig config, List<ValidationIssue> issues) {
        public boolean valid() {
            return issues.isEmpty();
        }
    }

    private DatabaseConfigLoader() {
    }

    public static LoadResult load(Path file, Logger logger) throws YamlFileIO.InvalidConfigException {
        YamlConfiguration yaml = YamlFileIO.loadOrCreate(file, "database.yml", logger);
        return parse(yaml);
    }

    public static LoadResult parse(YamlConfiguration yaml) {
        List<ValidationIssue> issues = new ArrayList<>();

        int schemaVersion = YamlFileIO.intInRange(
                yaml, "schema-version", 1, DatabaseConfig.CURRENT_SCHEMA_VERSION, 1, issues);

        String backendRaw = YamlFileIO.string(yaml, "backend", 16, "sqlite", issues);
        DatabaseConfig.Backend backend = switch (backendRaw.toLowerCase(java.util.Locale.ROOT)) {
            case "sqlite" -> DatabaseConfig.Backend.SQLITE;
            case "mysql" -> DatabaseConfig.Backend.MYSQL;
            default -> {
                issues.add(new ValidationIssue("backend", "must be sqlite or mysql (got '" + backendRaw + "')"));
                yield DatabaseConfig.Backend.SQLITE;
            }
        };

        String sqliteFile = YamlFileIO.string(yaml, "sqlite.file", 128, "rebellion.sqlite", issues);
        if (!sqliteFile.matches("[A-Za-z0-9._-]+")) {
            issues.add(new ValidationIssue("sqlite.file",
                    "must be a single file name (letters, digits, . _ -), no path components"));
        }

        String host = YamlFileIO.string(yaml, "mysql.host", 255, "localhost", issues);
        int port = YamlFileIO.intInRange(yaml, "mysql.port", 1, 65_535, 3306, issues);
        String database = YamlFileIO.string(yaml, "mysql.database", 64, "rebellion", issues);
        String username = YamlFileIO.string(yaml, "mysql.username", 96, "rebellion", issues);
        String password = EnvExpansion.expand(
                String.valueOf(yaml.get("mysql.password", "")), "mysql.password", issues);

        Map<String, String> props = new LinkedHashMap<>();
        ConfigurationSection propsSection = yaml.getConfigurationSection("mysql.properties");
        if (propsSection != null) {
            for (String key : propsSection.getKeys(false)) {
                String value = String.valueOf(propsSection.get(key));
                if (!key.matches("[A-Za-z0-9_.-]+")) {
                    issues.add(new ValidationIssue("mysql.properties." + key,
                            "property names may only contain letters, digits, dot, dash, underscore"));
                    continue;
                }
                props.put(key, value);
            }
        }

        int maxPool = YamlFileIO.intInRange(yaml, "pool.max-pool-size", 1, 64, 10, issues);
        int minIdle = YamlFileIO.intInRange(yaml, "pool.min-idle", 0, 64, 2, issues);
        int connTimeout = YamlFileIO.intInRange(yaml, "pool.connection-timeout-ms", 100, 60_000, 5_000, issues);
        int maxLifetime = YamlFileIO.intInRange(yaml, "pool.max-lifetime-ms", 30_000, 3_600_000, 1_800_000, issues);

        int executorPool = YamlFileIO.intInRange(yaml, "executor.pool-size", 1, 16, 4, issues);
        int queueCapacity = YamlFileIO.intInRange(yaml, "executor.queue-capacity", 1, 100_000, 2_000, issues);

        boolean strict = YamlFileIO.bool(yaml, "migrations.strict-checksums", true, issues);

        if (minIdle > maxPool) {
            issues.add(new ValidationIssue("pool.min-idle",
                    "must not exceed pool.max-pool-size (" + minIdle + " > " + maxPool + ")"));
        }
        if (schemaVersion > DatabaseConfig.CURRENT_SCHEMA_VERSION) {
            issues.add(new ValidationIssue("schema-version",
                    "database.yml was written for a newer Core; update RebellionCore"));
        }

        if (!issues.isEmpty()) {
            return new LoadResult(null, issues);
        }
        return new LoadResult(new DatabaseConfig(
                backend, sqliteFile, host, port, database, username, password, props,
                maxPool, minIdle, connTimeout, maxLifetime, executorPool, queueCapacity, strict), issues);
    }
}
