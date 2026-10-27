package net.zaminmc.rebellion.items.rarity;

/**
 * Canonical rarity axis for every item in the game.
 *
 * <p>Keys are stable configuration identifiers (lowercase enum name). Display
 * names, colors, weights and glints are data-driven in {@code rarities.yml};
 * user-facing spelling may differ from the canonical key, e.g. a localized
 * display name for {@code MYTHIC}.</p>
 *
 * <p>Do not conflate this axis with the talisman progression tier
 * ({@code NORMAL}/{@code ARTIFACT}/{@code RELIC}); they are separate concepts
 * by product requirement.</p>
 */
public enum Rarity {
    COMMON,
    UNCOMMON,
    RARE,
    EPIC,
    LEGENDARY,
    MYTHIC,
    SPECIAL,
    VERY_SPECIAL,
    GOD;

    /** Stable configuration key; never change existing values. */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Case-insensitive lookup; unknown keys produce {@code Optional.empty()}. */
    public static java.util.Optional<Rarity> byKey(String key) {
        if (key == null || key.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Rarity.valueOf(key.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return java.util.Optional.empty();
        }
    }
}
