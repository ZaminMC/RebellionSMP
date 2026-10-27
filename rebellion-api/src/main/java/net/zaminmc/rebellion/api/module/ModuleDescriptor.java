package net.zaminmc.rebellion.api.module;

/**
 * Stable identity of a Rebellion feature module.
 *
 * @param moduleKey    short unique registry key, e.g. {@code core}, {@code items},
 *                     {@code dragons}; also names the module's schema migration set
 * @param displayName  human-readable name shown in diagnostics and admin output
 * @param version      module jar version string
 */
public record ModuleDescriptor(String moduleKey, String displayName, String version) {

    public ModuleDescriptor {
        if (moduleKey == null || moduleKey.isBlank()) {
            throw new IllegalArgumentException("moduleKey must not be blank");
        }
        if (!moduleKey.equals(moduleKey.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("moduleKey must be lowercase: " + moduleKey);
        }
        if (!moduleKey.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "moduleKey must match [a-z][a-z0-9_]* (got: " + moduleKey + ")");
        }
    }
}
