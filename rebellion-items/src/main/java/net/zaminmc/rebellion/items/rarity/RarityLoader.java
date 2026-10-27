package net.zaminmc.rebellion.items.rarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads {@code rarities.yml} into a {@link RarityRegistry}.
 *
 * <p>Material-name validation is injected ({@link MaterialValidator}) so this
 * loader is unit-testable without a live server: the production validator
 * delegates to {@code Material.matchMaterial}, tests supply a fixed name set.
 * Unknown material or rarity keys are collected as issues rather than thrown,
 * so one bad entry never hides another.</p>
 */
public final class RarityLoader {

    /** Decides whether a material name exists in the current server version. */
    @FunctionalInterface
    public interface MaterialValidator {
        boolean isValid(String materialName);
    }

    /** Production validator: real Material registry. */
    public static final MaterialValidator BUKKIT_VALIDATOR =
            name -> org.bukkit.Material.matchMaterial(name) != null;

    private final MaterialValidator materialValidator;

    public RarityLoader(MaterialValidator materialValidator) {
        this.materialValidator = materialValidator;
    }

    /** Parses a candidate YAML snapshot into a registry + issues list. */
    public LoadedRarities load(YamlConfiguration yaml) {
        RarityRegistry.Builder builder = new RarityRegistry.Builder();
        List<String> issues = new ArrayList<>(builder.issues());

        ConfigurationSection rarities = yaml.getConfigurationSection("rarities");
        if (rarities == null) {
            issues.add("missing 'rarities' section");
            return new LoadedRarities(builder.build(), issues);
        }

        for (String rarityKey : rarities.getKeys(false)) {
            java.util.Optional<Rarity> rarity = Rarity.byKey(rarityKey);
            if (rarity.isEmpty()) {
                issues.add("rarities." + rarityKey + ": unknown rarity key");
                continue;
            }
            ConfigurationSection section = rarities.getConfigurationSection(rarityKey);
            if (section == null) {
                issues.add("rarities." + rarityKey + ": must be a section");
                continue;
            }
            String displayName = section.getString("display-name");
            String color = section.getString("color");
            int weight = section.getInt("weight", 1);
            boolean glint = section.getBoolean("glint", false);
            if (displayName == null) {
                issues.add("rarities." + rarityKey + ": missing display-name");
            }
            if (color == null) {
                issues.add("rarities." + rarityKey + ": missing color");
            }
            if (section.isSet("weight") && weight < 0) {
                issues.add("rarities." + rarityKey + ": weight must not be negative");
            }
            builder.style(rarity.get(), displayName, color, Math.max(weight, 0), glint);
        }

        ConfigurationSection overrides = yaml.getConfigurationSection("materials");
        if (overrides != null) {
            for (String materialName : overrides.getKeys(false)) {
                String rarityKey = overrides.getString(materialName);
                java.util.Optional<Rarity> rarity = Rarity.byKey(rarityKey);
                String canonical = materialName.toUpperCase(Locale.ROOT);
                if (!materialValidator.isValid(canonical)) {
                    issues.add("materials." + materialName + ": unknown material for this server version");
                    continue;
                }
                if (rarity.isEmpty()) {
                    issues.add("materials." + materialName + ": unknown rarity '" + rarityKey + "'");
                    continue;
                }
                builder.override(canonical, rarity.get());
            }
        }

        RarityRegistry registry = builder.build();
        issues.addAll(registry.issues());
        return new LoadedRarities(registry, issues);
    }

    /** Registry + aggregated issues; issues non-empty means the file is invalid. */
    public record LoadedRarities(RarityRegistry registry, List<String> issues) {
        public boolean valid() {
            return issues.isEmpty();
        }
    }
}
