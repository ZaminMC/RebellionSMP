package net.zaminmc.rebellion.items.identity;

import java.util.List;
import java.util.Optional;

/**
 * One defined custom item from {@code items.yml}.
 *
 * <p>Immutable definition; creation is a Bukkit-side factory concern. Instance
 * identity ({@code instance_id}) is stamped only when {@link #unique()} is
 * true — the contract for assets where duplication matters (talismans,
 * future artifacts).</p>
 */
public record CustomItemSpec(
        String id,
        String material,
        String rarityKey,
        String talismanTierKey,
        String displayName,
        List<String> lore,
        boolean glint,
        boolean unique) {

    /** @return whether this definition participates in instance tracking. */
    public boolean tracksInstances() {
        return unique;
    }
}
