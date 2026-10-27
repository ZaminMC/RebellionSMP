package net.zaminmc.rebellion.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Validation coverage for core + database config loading. Exercises the real
 * Bukkit YamlConfiguration parser (server-free) with the bundled default files.
 */
final class ConfigLoaderTest {

    private static YamlConfiguration yamlFrom(String resource) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ConfigLoaderTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource " + resource);
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse " + resource, e);
        }
        return yaml;
    }

    @Test
    void bundledCoreDefaultsAreValid() {
        var result = CoreConfigLoader.parse(yamlFrom("/config.yml"));
        assertTrue(result.valid(), () -> result.issues().toString());
        assertEquals(1, result.config().schemaVersion());
        assertFalse(result.config().debug());
        assertEquals(30, result.config().startupTimeoutSeconds());
        assertEquals("diagnostics", result.config().diagnosticsDirectory());
        assertEquals(10, result.config().maxReportFiles());
    }

    @Test
    void bundledDatabaseDefaultsAreValid() {
        var result = DatabaseConfigLoader.parse(yamlFrom("/database.yml"));
        assertTrue(result.valid(), () -> result.issues().toString());
        assertEquals(DatabaseConfig.Backend.SQLITE, result.config().backend());
        assertEquals("rebellion.sqlite", result.config().sqliteFile());
        assertEquals(1, result.config().effectiveMaxPoolSize()); // SQLite forced to one
        assertEquals(10, result.config().maxPoolSize());         // MySQL value kept
        assertTrue(result.config().strictChecksums());
        assertTrue(result.config().jdbcUrl().startsWith("jdbc:sqlite:rebellion.sqlite"));
    }

    @Test
    void negativeAndOutOfRangeNumbersAreRejected() {
        YamlConfiguration yaml = yamlFrom("/config.yml");
        yaml.set("startup-timeout-seconds", -5);
        yaml.set("diagnostics.max-report-files", 0);
        yaml.set("debug", "not-a-boolean");
        var result = CoreConfigLoader.parse(yaml);
        assertFalse(result.valid());
        List<String> paths = result.issues().stream().map(ValidationIssue::path).toList();
        assertTrue(paths.contains("startup-timeout-seconds"));
        assertTrue(paths.contains("diagnostics.max-report-files"));
        assertTrue(paths.contains("debug"));
    }

    @Test
    void pathTraversalInSqliteFileIsRejected() {
        YamlConfiguration yaml = yamlFrom("/database.yml");
        yaml.set("sqlite.file", "../../etc/passwd");
        var result = DatabaseConfigLoader.parse(yaml);
        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.path().equals("sqlite.file")));
    }

    @Test
    void unknownBackendIsRejected() {
        YamlConfiguration yaml = yamlFrom("/database.yml");
        yaml.set("backend", "postgres");
        var result = DatabaseConfigLoader.parse(yaml);
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i -> i.path().equals("backend")));
    }

    @Test
    void futureSchemaVersionIsRejected() {
        YamlConfiguration yaml = yamlFrom("/config.yml");
        yaml.set("schema-version", 99);
        var result = CoreConfigLoader.parse(yaml);
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i -> i.path().equals("schema-version")));
    }

    @Test
    void minIdleAboveMaxPoolIsRejected() {
        YamlConfiguration yaml = yamlFrom("/database.yml");
        yaml.set("pool.min-idle", 20);
        var result = DatabaseConfigLoader.parse(yaml);
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i -> i.path().equals("pool.min-idle")));
    }

    @Test
    void mysqlUrlContainsHostPortDatabaseAndProperties() {
        YamlConfiguration yaml = yamlFrom("/database.yml");
        yaml.set("backend", "mysql");
        yaml.set("mysql.host", "db.example.org");
        yaml.set("mysql.port", 3307);
        yaml.set("mysql.database", "rebellion_prod");
        yaml.set("mysql.properties.sslMode", "REQUIRED");
        var result = DatabaseConfigLoader.parse(yaml);
        assertTrue(result.valid(), () -> result.issues().toString());
        assertEquals("jdbc:mysql://db.example.org:3307/rebellion_prod?sslMode=REQUIRED",
                result.config().jdbcUrl());
    }

    @Test
    void environmentExpansionUsesFallbackAndReportsMissing() {
        YamlConfiguration yaml = yamlFrom("/database.yml");
        yaml.set("mysql.password", "${REBELLION_TEST_UNSET_VAR_9182:fallbackpw}");
        var withFallback = DatabaseConfigLoader.parse(yaml);
        assertTrue(withFallback.valid());
        assertEquals("fallbackpw", withFallback.config().mysqlPassword());

        yaml.set("mysql.password", "${REBELLION_TEST_UNSET_VAR_9182}");
        var withoutFallback = DatabaseConfigLoader.parse(yaml);
        assertFalse(withoutFallback.valid());
        assertTrue(withoutFallback.issues().stream()
                .anyMatch(i -> i.path().equals("mysql.password")));
    }

    @Test
    void propertyNamesAreValidated() {
        YamlConfiguration yaml = yamlFrom("/database.yml");
        yaml.set("mysql.properties.bad name", "x");
        var result = DatabaseConfigLoader.parse(yaml);
        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.path().equals("mysql.properties.bad name")));
    }

    @Test
    void sqlitePoolIsForcedToSingleConnection() {
        YamlConfiguration yaml = yamlFrom("/database.yml");
        yaml.set("pool.max-pool-size", 32);
        var result = DatabaseConfigLoader.parse(yaml);
        assertTrue(result.valid());
        assertEquals(1, result.config().effectiveMaxPoolSize());
        assertEquals(1, result.config().effectiveMinIdle());
        // The MySQL setting itself is preserved for potential backend switches.
        assertEquals(32, result.config().maxPoolSize());
    }

    @Test
    void envExpansionSemantics() {
        List<ValidationIssue> issues = new java.util.ArrayList<>();
        assertEquals("plain", EnvExpansion.expand("plain", "p", issues));
        assertEquals("", EnvExpansion.expand("${REBELLION_TEST_UNSET_VAR_9182:}", "p", issues));
        Map<String, String> env = System.getenv();
        String firstKey = env.keySet().stream()
                .filter(k -> k.matches("[A-Za-z_][A-Za-z0-9_]*")).findFirst().orElse(null);
        if (firstKey != null) {
            assertEquals(env.get(firstKey), EnvExpansion.expand("${" + firstKey + "}", "p", issues));
            assertEquals("x" + env.get(firstKey) + "y",
                    EnvExpansion.expand("x${" + firstKey + "}y", "p", issues));
        }
        EnvExpansion.expand("${REBELLION_TEST_UNSET_VAR_9182}", "p", issues);
        assertEquals(1, issues.size());
        assertEquals("p", issues.get(0).path());
    }
}
