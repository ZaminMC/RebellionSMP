package net.zaminmc.rebellion.items.command;

import java.util.Locale;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import net.zaminmc.rebellion.items.rarity.RarityRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionItems' contribution to the shared {@code rebellion} expansion.
 *
 * <p>Supported parameters (pure in-memory reads, no database):</p>
 * <ul>
 *   <li>{@code %rebellion_rarity_<MATERIAL>%} — the configured rarity key of
 *       a material name (e.g. {@code %rebellion_rarity_DIAMOND_SWORD%} →
 *       {@code epic}); unmapped and unknown materials resolve to
 *       {@code common} by the registry's documented fallback.</li>
 * </ul>
 */
public final class ItemsPlaceholders implements PlaceholderContributor {

    private static final String RARITY_PREFIX = "rarity_";

    private final RarityRegistry rarities;

    public ItemsPlaceholders(RarityRegistry rarities) {
        this.rarities = rarities;
    }

    @Override
    public @Nullable String resolve(@NotNull String params) {
        String normalized = params.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(RARITY_PREFIX)) {
            return null;
        }
        String materialName = params.substring(RARITY_PREFIX.length());
        if (materialName.isEmpty() || !materialName.matches("[A-Za-z0-9_]+")) {
            return null; // never echo user-controlled junk back into placeholders
        }
        return rarities.of(materialName).key();
    }
}
