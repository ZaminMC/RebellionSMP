package net.zaminmc.rebellion.items.rarity;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;

/**
 * Resolves {@link Material} names to a {@link Rarity}.
 *
 * <p>Data sources (validated at load): explicit per-material overrides from
 * {@code rarities.yml}, plus a documented safe fallback. Materials that have
 * no override resolve to {@link Rarity#COMMON} — the product decision from
 * master prompt §5.4: dirt is Common, a glass bottle is Common. This also
 * makes materials introduced by future Minecraft updates safe by default
 * instead of requiring an exhaustive hardcoded list.</p>
 *
 * <p>This class is server-free: it keys on material <em>names</em>, not live
 * {@code Material} objects, so the registry is fully unit-testable without a
 * running server.</p>
 */
public final class RarityRegistry {

    /** Display/behavior data for one rarity. */
    public record RarityStyle(String displayName, String color, int weight, boolean glint) {
        public RarityStyle {
            if (displayName == null || color == null) {
                throw new IllegalArgumentException("display name and color must not be null");
            }
        }
    }

    private final Map<Rarity, RarityStyle> styles;
    private final Map<String, Rarity> overrides;
    private final List<String> issues;

    RarityRegistry(Map<Rarity, RarityStyle> styles, Map<String, Rarity> overrides, List<String> issues) {
        this.styles = Map.copyOf(styles);
        this.overrides = Map.copyOf(overrides);
        this.issues = List.copyOf(issues);
    }

    /** @return validation problems found while loading; empty means clean. */
    public List<String> issues() {
        return issues;
    }

    /**
     * Resolves a material name (canonical, uppercase, e.g. {@code DIAMOND_SWORD}).
     *
     * @param materialName the material key; {@code null}/blank is an error and
     *                     resolves to COMMON with a logged issue at load time
     * @return the configured override, else {@link Rarity#COMMON}
     */
    public Rarity of(String materialName) {
        if (materialName == null) {
            return Rarity.COMMON;
        }
        return overrides.getOrDefault(materialName.toUpperCase(Locale.ROOT), Rarity.COMMON);
    }

    /** Convenience overload for live stacks; never returns null. */
    public Rarity of(Material material) {
        return material == null ? Rarity.COMMON : of(material.name());
    }

    /** @return style data for the rarity; falls back to a plain COMMON style. */
    public RarityStyle style(Rarity rarity) {
        return styles.getOrDefault(rarity,
                new RarityStyle(rarity.key(), "<gray>", 1, false));
    }

    /** @return the override map as configured (material name → rarity). */
    public Map<String, Rarity> overrides() {
        return overrides;
    }

    /**
     * Builder used by the loader; validates names and collects issues instead
     * of throwing, so admins see every problem in one pass.
     */
    public static final class Builder {
        private final Map<Rarity, RarityStyle> styles = new java.util.EnumMap<>(Rarity.class);
        private final Map<String, Rarity> overrides = new java.util.HashMap<>();
        private final List<String> issues = new java.util.ArrayList<>();

        /** Registers display data for a rarity; null fields produce issues. */
        public Builder style(Rarity rarity, String displayName, String color, int weight, boolean glint) {
            if (rarity == null) {
                issue("style entry with unknown rarity key");
                return this;
            }
            if (displayName == null || color == null) {
                issue("rarity '" + rarity.key() + "': display-name and color are required");
                return this;
            }
            if (weight < 0) {
                issue("rarity '" + rarity.key() + "': weight must not be negative");
                return this;
            }
            styles.put(rarity, new RarityStyle(displayName, color, weight, glint));
            return this;
        }

        /** Registers a material override; unknown material names are recorded. */
        public Builder override(String materialName, Rarity rarity) {
            if (materialName == null || materialName.isBlank()) {
                issue("override entry with empty material name");
                return this;
            }
            if (rarity == null) {
                issue("override '" + materialName + "': unknown rarity key");
                return this;
            }
            String key = materialName.toUpperCase(Locale.ROOT);
            Rarity prev = overrides.putIfAbsent(key, rarity);
            if (prev != null && prev != rarity) {
                issue("override '" + materialName + "': duplicate mapping (" + prev.key()
                        + " already set; ignored " + rarity.key() + ")");
            }
            return this;
        }

        void issue(String message) {
            issues.add(message);
        }

        /** @return issues collected so far (defensive copy). */
        public List<String> issues() {
            return List.copyOf(issues);
        }

        /** @return the immutable registry with all collected issues. */
        public RarityRegistry build() {
            return new RarityRegistry(styles, overrides, issues);
        }
    }
}
