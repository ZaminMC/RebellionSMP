package net.zaminmc.rebellion.core.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion with the stable identifier {@code rebellion}.
 *
 * <p>Delegates every lookup to {@link CorePlaceholders}, which owns the core
 * parameters and routes the rest to every module contributor registered with
 * the ServicesManager (master prompt §10: a placeholder registry contributed
 * by modules). Placeholders are pure in-memory reads — no database I/O per
 * request.</p>
 *
 * <p>This class is only loaded when PlaceholderAPI is present; the guard lives
 * in the bootstrap. Identifier, author and version are stable surface — change
 * them only with a deprecation cycle.</p>
 */
public final class RebellionExpansion extends PlaceholderExpansion {

    private final PlaceholderContributor resolver;

    public RebellionExpansion(PlaceholderContributor resolver) {
        this.resolver = resolver;
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
        return "1.0.0";
    }

    /** Registered at boot when PAPI exists; survives reloads of other plugins. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        return resolver.resolve(params); // unknown → null → PAPI leaves text untouched
    }
}
