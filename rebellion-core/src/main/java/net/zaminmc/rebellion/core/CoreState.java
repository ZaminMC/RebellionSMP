package net.zaminmc.rebellion.core;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.zaminmc.rebellion.core.config.CoreConfig;
import net.zaminmc.rebellion.core.config.CoreConfigLoader;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.config.DatabaseConfigLoader;
import net.zaminmc.rebellion.core.config.ValidationIssue;
import net.zaminmc.rebellion.core.db.DatabaseService;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import net.zaminmc.rebellion.core.messages.CoreMessages;
import net.zaminmc.rebellion.core.module.ModuleManager;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Mutable runtime state of RebellionCore. Configuration and messages are held in
 * immutable snapshots behind {@link AtomicReference}s so a failed reload never
 * leaves a half-applied configuration, and readers always see a consistent view.
 */
public final class CoreState {

    private final Path dataDirectory;
    private final String coreVersion;
    private final String apiVersion;
    private final Instant startedAt;

    private final AtomicReference<CoreConfig> config;
    private final AtomicReference<CoreMessages> messages;
    private final AtomicReference<DatabaseConfig> databaseConfig;
    private final AtomicReference<String> lastConfigValidation;

    private final ModuleManager modules = new ModuleManager();
    private final DiagnosticsService diagnostics;
    private final DatabaseService database;

    public CoreState(Path dataDirectory, String coreVersion, String apiVersion,
            CoreConfig config, CoreMessages messages, DatabaseConfig databaseConfig,
            DiagnosticsService diagnostics, DatabaseService database) {
        this.dataDirectory = dataDirectory;
        this.coreVersion = coreVersion;
        this.apiVersion = apiVersion;
        this.startedAt = Instant.now();
        this.config = new AtomicReference<>(Objects.requireNonNull(config));
        this.messages = new AtomicReference<>(Objects.requireNonNull(messages));
        this.databaseConfig = new AtomicReference<>(Objects.requireNonNull(databaseConfig));
        this.lastConfigValidation = new AtomicReference<>("ok at startup");
        this.diagnostics = diagnostics;
        this.database = database;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public String coreVersion() {
        return coreVersion;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public CoreConfig config() {
        return config.get();
    }

    public CoreMessages messages() {
        return messages.get();
    }

    public DatabaseConfig databaseConfig() {
        return databaseConfig.get();
    }

    public ModuleManager modules() {
        return modules;
    }

    public DiagnosticsService diagnostics() {
        return diagnostics;
    }

    public DatabaseService database() {
        return database;
    }

    /**
     * Reloads {@code config.yml} and {@code messages.yml} atomically: both files
     * are fully parsed and validated first; only when both succeed are the active
     * snapshots swapped. {@code database.yml} is validated for early feedback but
     * deliberately not re-applied (restart-only, as documented).
     *
     * @return validation issues; empty list means the reload succeeded
     */
    public ReloadResult reloadConfigAndMessages() {
        List<ValidationIssue> issues = new java.util.ArrayList<>();

        var configResult = CoreConfigLoader.parse(loadYaml("config.yml"));
        issues.addAll(configResult.issues());

        var messagesResult = CoreMessages.parse(loadYaml("messages.yml"));
        issues.addAll(messagesResult.issues());

        var dbResult = DatabaseConfigLoader.parse(loadYaml("database.yml"));
        issues.addAll(dbResult.issues());

        if (!issues.isEmpty()) {
            lastConfigValidation.set("last reload rejected with " + issues.size() + " issue(s)");
            return new ReloadResult(false, issues);
        }

        config.set(configResult.config());
        messages.set(messagesResult.messages());
        // databaseConfig stays as-is on purpose: connection pools cannot be swapped
        // safely at runtime; the restart-only behavior is documented and reported.
        lastConfigValidation.set("ok (reloaded " + Instant.now() + ")");
        return new ReloadResult(true, List.of());
    }

    private YamlConfiguration loadYaml(String file) {
        try {
            return net.zaminmc.rebellion.core.config.YamlFileIO.load(dataDirectory.resolve(file));
        } catch (net.zaminmc.rebellion.core.config.YamlFileIO.InvalidConfigException e) {
            // Represent a broken file as a validation issue via an empty config;
            // parse() then reports every missing key precisely.
            return new YamlConfiguration();
        }
    }

    /** Short human-readable summary for diagnostic reports. */
    public List<String> configSummary() {
        return List.of(
                "config: " + lastConfigValidation.get(),
                "debug: " + config.get().debug(),
                "backend: " + databaseConfig.get().backend(),
                "startup-timeout-seconds: " + config.get().startupTimeoutSeconds());
    }

    public record ReloadResult(boolean valid, List<ValidationIssue> issues) {
    }
}
