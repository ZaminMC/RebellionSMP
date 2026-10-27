package net.zaminmc.rebellion.items.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.zaminmc.rebellion.items.lore.TalismanTier;
import net.zaminmc.rebellion.items.rarity.Rarity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads {@code items.yml} — the definitions of named custom items and
 * talisman families.
 *
 * <p>Validation collects every issue with a precise path: duplicate ids,
 * malformed ids, unknown materials/rarities/tiers are all reported in one
 * pass. Material validation is injected (serverless tests).</p>
 */
public final class CustomItemLoader {

    @FunctionalInterface
    public interface MaterialValidator {
        boolean isValid(String materialName);
    }

    public static final MaterialValidator BUKKIT_VALIDATOR =
            name -> org.bukkit.Material.matchMaterial(name) != null;

    /** Custom item ids: lowercase letters, digits, underscore, 1..64 chars. */
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_]{1,64}");

    private final MaterialValidator materialValidator;

    public CustomItemLoader(MaterialValidator materialValidator) {
        this.materialValidator = materialValidator;
    }

    public LoadedCustomItems load(YamlConfiguration yaml) {
        List<String> issues = new ArrayList<>();
        Map<String, CustomItemSpec> items = new LinkedHashMap<>();

        ConfigurationSection section = yaml.getConfigurationSection("items");
        if (section == null) {
            issues.add("items: missing section");
            return new LoadedCustomItems(Map.of(), List.copyOf(issues));
        }

        for (String id : section.getKeys(false)) {
            String path = "items." + id;
            if (!ITEM_ID.matcher(id).matches()) {
                issues.add(path + ": invalid id (expected [a-z0-9_]{1,64})");
                continue;
            }
            ConfigurationSection def = section.getConfigurationSection(id);
            if (def == null) {
                issues.add(path + ": must be a section");
                continue;
            }
            String material = def.getString("material");
            if (material == null || material.isBlank()) {
                issues.add(path + ".material: missing");
                continue;
            }
            String canonicalMaterial = material.toUpperCase(Locale.ROOT);
            if (!materialValidator.isValid(canonicalMaterial)) {
                issues.add(path + ".material: unknown material '" + material + "'");
                continue;
            }

            String rarityKey = def.getString("rarity", "common");
            Optional<Rarity> rarity = Rarity.byKey(rarityKey);
            if (rarity.isEmpty()) {
                issues.add(path + ".rarity: unknown rarity key '" + rarityKey + "'");
                continue;
            }

            String tierKey = def.getString("talisman-tier", null);
            if (tierKey != null && TalismanTier.byKey(tierKey).isEmpty()) {
                issues.add(path + ".talisman-tier: unknown tier '" + tierKey
                        + "' (normal, artifact, relic)");
                continue;
            }

            String displayName = def.getString("display-name", id);
            List<String> lore = List.copyOf(def.getStringList("lore"));
            boolean glint = def.getBoolean("glint", false);
            boolean unique = def.getBoolean("unique", false);

            if (items.putIfAbsent(id, new CustomItemSpec(id, canonicalMaterial,
                    rarity.get().key(), tierKey == null ? null
                            : tierKey.toLowerCase(Locale.ROOT),
                    displayName, lore, glint, unique)) != null) {
                issues.add(path + ": duplicate id (already defined earlier)");
            }
        }

        return new LoadedCustomItems(java.util.Map.copyOf(items), List.copyOf(issues));
    }

    /** Definitions + issues; valid() means the file is usable. */
    public record LoadedCustomItems(Map<String, CustomItemSpec> items, List<String> issues) {
        public boolean valid() {
            return issues.isEmpty();
        }
    }
}
