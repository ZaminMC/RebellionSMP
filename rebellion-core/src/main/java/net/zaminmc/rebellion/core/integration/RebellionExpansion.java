package net.zaminmc.rebellion.core.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion with the stable identifier {@code rebellion}.
 *
 * <p>Core-level placeholders only; feature modules contribute their own
 * placeholders through future expansions. Every lookup reads in-memory state —
 * placeholders never touch the database (see the API contract in
 * docs/PLACEHOLDERS or RebellionSMP_Codex_Master_Prompt.md §10).</p>
 *
 * <p>This class is only loaded when PlaceholderAPI is present; the guard lives
 * in the bootstrap. Identifier, author and version are stable surface — change
 * them only with a deprecation cycle.</p>
 */
public final class RebellionExpansion extends PlaceholderExpansion {

    private final String version;
    private final java.util.function.Supplier<String> healthyRatio;
    private final java.util.function.Supplier<String> backend;
    private final java.util.function.Supplier<String> degradedModules;

    public RebellionExpansion(String version,
            java.util.function.Supplier<String> healthyRatio,
            java.util.function.Supplier<String> backend,
            java.util.function.Supplier<String> degradedModules) {
        this.version = version;
        this.healthyRatio = healthyRatio;
        this.backend = backend;
        this.degradedModules = degradedModules;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "rebellion";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Abasing";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    /** No persistence — placeholders are pure reads of in-memory state. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "version" -> version;
            case "modules_healthy" -> healthyRatio.get();
            case "database_backend" -> backend.get();
            case "modules_degraded" -> degradedModules.get();
            default -> null; // unknown placeholder: leave it untouched per PAPI contract
        };
    }

    /** Utility for feature modules: plain text of a component, null-safe. */
    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
