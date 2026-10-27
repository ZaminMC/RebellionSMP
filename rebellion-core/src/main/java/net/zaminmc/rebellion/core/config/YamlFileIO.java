package net.zaminmc.rebellion.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Load-or-create semantics for module YAML files.
 *
 * <p>Existing files are never rewritten by loading: a file is only written when it
 * does not exist yet, or when an explicit config version migration (see
 * {@code schema-version} handling in the loaders) requires persisting new keys.
 * This guarantees local edits, comments and ordering survive every boot.</p>
 */
public final class YamlFileIO {

    private YamlFileIO() {
    }

    /**
     * Ensures the file exists (copying the bundled resource on first boot) and
     * parses it.
     *
     * @throws InvalidConfigException when the file is not valid YAML
     */
    public static YamlConfiguration loadOrCreate(Path file, String resource, Logger logger)
            throws InvalidConfigException {
        if (Files.notExists(file)) {
            try {
                Files.createDirectories(file.getParent());
                try (InputStream in = YamlFileIO.class.getResourceAsStream("/" + resource)) {
                    if (in == null) {
                        throw new InvalidConfigException(
                                "Bundled default resource '" + resource + "' is missing from the jar");
                    }
                    Files.copy(in, file);
                }
                logger.info("Created default " + file.getFileName() + ".");
            } catch (IOException e) {
                throw new InvalidConfigException(
                        "Could not create " + file + ": " + e.getMessage(), e);
            }
        }
        return load(file);
    }

    /** Parses an existing YAML file with precise error reporting. */
    public static YamlConfiguration load(Path file) throws InvalidConfigException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException e) {
            throw new InvalidConfigException("Cannot read " + file + ": " + e.getMessage(), e);
        } catch (InvalidConfigurationException e) {
            throw new InvalidConfigException(
                    "Invalid YAML in " + file + " (line " + describeLine(e.getMessage()) + ")", e);
        }
        return yaml;
    }

    private static String describeLine(String message) {
        // SnakeYAML messages embed "line N, column M"; surface it when present.
        if (message == null) {
            return "?";
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("line (\\d+)").matcher(message);
        return m.find() ? m.group(1) : "?";
    }

    /** Saves a config, preserving comments that were present when it was loaded. */
    public static void save(YamlConfiguration yaml, Path file) throws InvalidConfigException {
        try {
            yaml.save(file.toFile());
        } catch (IOException e) {
            throw new InvalidConfigException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** Thrown with a precise, admin-readable message. */
    public static final class InvalidConfigException extends Exception {
        InvalidConfigException(String message) {
            super(message);
        }

        InvalidConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Utility: reads an int with range enforcement, appending a precise issue on error. */
    public static int intInRange(YamlConfiguration yaml, String path, int min, int max,
                                 int fallback, List<ValidationIssue> issues) {
        Object raw = yaml.get(path);
        if (raw == null) {
            issues.add(new ValidationIssue(path, "missing; expected an integer between " + min + " and " + max));
            return fallback;
        }
        if (raw instanceof Number n) {
            int v = n.intValue();
            if (v < min || v > max) {
                issues.add(new ValidationIssue(path, "must be between " + min + " and " + max + " (got " + v + ")"));
                return fallback;
            }
            return v;
        }
        issues.add(new ValidationIssue(path, "expected an integer (got '" + raw + "')"));
        return fallback;
    }

    /** Utility: reads a boolean with a precise issue on error. */
    public static boolean bool(YamlConfiguration yaml, String path, boolean fallback,
                               List<ValidationIssue> issues) {
        Object raw = yaml.get(path);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw == null) {
            issues.add(new ValidationIssue(path, "missing; expected true or false"));
            return fallback;
        }
        issues.add(new ValidationIssue(path, "expected true or false (got '" + raw + "')"));
        return fallback;
    }

    /** Utility: non-blank string with length cap. */
    public static String string(YamlConfiguration yaml, String path, int maxLen, String fallback,
                                List<ValidationIssue> issues) {
        Object raw = yaml.get(path);
        if (raw == null) {
            issues.add(new ValidationIssue(path, "missing; expected text"));
            return fallback;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            issues.add(new ValidationIssue(path, "must not be blank"));
            return fallback;
        }
        if (s.length() > maxLen) {
            issues.add(new ValidationIssue(path, "must be at most " + maxLen + " characters"));
            return fallback;
        }
        return s;
    }

    /** Utility used by loaders to log at a consistent level. */
    static void debug(Logger logger, String message) {
        logger.log(Level.FINE, message);
    }
}
