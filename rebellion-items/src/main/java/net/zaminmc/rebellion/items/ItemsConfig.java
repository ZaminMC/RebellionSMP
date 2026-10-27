package net.zaminmc.rebellion.items;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Validated snapshot of RebellionItems' {@code config.yml}.
 * Immutable; a reload swaps the whole record atomically.
 */
public record ItemsConfig(
        int schemaVersion,
        AnnotationMode annotationMode,
        LorePosition lorePosition,
        boolean joinNormalization,
        boolean pickupAnnotation,
        boolean blockDropAnnotation,
        boolean craftingOutputAnnotation,
        boolean smithingOutputAnnotation,
        boolean containerOpenAnnotation,
        boolean allowConsoleActions,
        Map<String, String> menuRegistry) {

    /** Which stacks the annotator may touch. */
    public enum AnnotationMode { CUSTOM_ONLY, MANAGED_ITEMS, ALL_ITEMS }

    /** Rarity line placement in the lore. */
    public enum LorePosition { TOP, BOTTOM }

    /**
     * Parses + validates a candidate config; collects every issue instead of
     * failing on the first. Returns the immutable snapshot or null with issues.
     */
    public record LoadResult(ItemsConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    public static LoadResult load(YamlConfiguration yaml) {
        List<String> issues = new java.util.ArrayList<>();
        int schema = yaml.getInt("schema-version", 1);
        if (schema != 1) {
            issues.add("schema-version: expected 1, got " + schema);
        }

        String modeRaw = yaml.getString("annotation.mode", "custom-only");
        AnnotationMode mode = switch (modeRaw.toLowerCase(Locale.ROOT)) {
            case "custom-only" -> AnnotationMode.CUSTOM_ONLY;
            case "managed-items" -> AnnotationMode.MANAGED_ITEMS;
            case "all-items" -> AnnotationMode.ALL_ITEMS;
            default -> null;
        };
        if (mode == null) {
            issues.add("annotation.mode: expected custom-only, managed-items or all-items (got '"
                    + modeRaw + "')");
        }

        String posRaw = yaml.getString("annotation.lore-position", "bottom");
        LorePosition position = switch (posRaw.toLowerCase(Locale.ROOT)) {
            case "top" -> LorePosition.TOP;
            case "bottom" -> LorePosition.BOTTOM;
            default -> null;
        };
        if (position == null) {
            issues.add("annotation.lore-position: expected top or bottom (got '" + posRaw + "')");
        }

        boolean joinNormalization = yaml.getBoolean("annotation.flows.join-normalization", false);
        boolean pickupAnnotation = yaml.getBoolean("annotation.flows.pickup", false);
        boolean blockDropAnnotation = yaml.getBoolean("annotation.flows.block-drops", false);
        boolean craftingOutputAnnotation = yaml.getBoolean("annotation.flows.crafting-output", false);
        boolean smithingOutputAnnotation = yaml.getBoolean("annotation.flows.smithing-output", false);
        boolean containerOpenAnnotation = yaml.getBoolean("annotation.flows.container-open", false);
        boolean allowConsoleActions = yaml.getBoolean("menus.allow-console-actions", false);

        Map<String, String> registry = new java.util.LinkedHashMap<>();
        var section = yaml.getConfigurationSection("gui_menus");
        if (section == null) {
            issues.add("gui_menus: missing section (menus are registered here)");
        } else {
            // DeluxeMenus registry shape: gui_menus.<id>.file
            for (String id : section.getKeys(false)) {
                var entry = section.getConfigurationSection(id);
                if (entry == null) {
                    issues.add("gui_menus." + id + ": must be a section with a 'file' entry");
                    continue;
                }
                String file = entry.getString("file");
                if (file == null || file.isBlank()) {
                    issues.add("gui_menus." + id + ": missing file reference");
                    continue;
                }
                registry.put(id, file);
            }
        }

        if (!issues.isEmpty()) {
            return new LoadResult(null, List.copyOf(issues));
        }
        return new LoadResult(new ItemsConfig(schema, mode, position,
                joinNormalization, pickupAnnotation, blockDropAnnotation,
                craftingOutputAnnotation, smithingOutputAnnotation,
                containerOpenAnnotation, allowConsoleActions,
                Map.copyOf(registry)), List.of());
    }
}
