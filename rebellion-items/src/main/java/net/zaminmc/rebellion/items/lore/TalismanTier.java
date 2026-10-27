package net.zaminmc.rebellion.items.lore;

/**
 * Talisman progression tier — a separate axis from {@link
 * net.zaminmc.rebellion.items.rarity.Rarity}.
 *
 * <p>Product requirement (master prompt §5.4): {@code ARTIFACT} here is a
 * <em>talisman tier</em>, never an item rarity. The two axes must stay
 * independent in configuration and code.</p>
 */
public enum TalismanTier {
    NORMAL,
    ARTIFACT,
    RELIC;

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static java.util.Optional<TalismanTier> byKey(String key) {
        if (key == null || key.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(TalismanTier.valueOf(key.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return java.util.Optional.empty();
        }
    }
}
