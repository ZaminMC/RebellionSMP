package net.zaminmc.rebellion.items.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Parses and validates one menu file (DeluxeMenus-shaped subset — see
 * docs/DELUXEMENUS_COMPATIBILITY.md for the exact supported field list).
 *
 * <p>Every problem is collected (not first-wins) with a precise YAML path.
 * An invalid file produces {@link LoadedMenu#valid()} == false and must not
 * be offered to players. Material-name validation is injected so this loader
 * is unit-testable without a live server.</p>
 */
public final class MenuLoader {

    @FunctionalInterface
    public interface MaterialValidator {
        boolean isValid(String materialName);
    }

    public static final MaterialValidator BUKKIT_VALIDATOR =
            name -> org.bukkit.Material.matchMaterial(name) != null;

    /** Deliberate subset: unknown actions/requirements are diagnostics, never guesses. */
    private static final java.util.Set<String> REQUIREMENT_TYPES = java.util.Set.of("has permission");
    private static final java.util.Set<String> ACTION_TYPES = java.util.Set.of(
            "close", "message", "sound", "player", "console");

    private final MaterialValidator materialValidator;

    public MenuLoader(MaterialValidator materialValidator) {
        this.materialValidator = materialValidator;
    }

    /** Parses one menu file. */
    public LoadedMenu load(String menuId, YamlConfiguration yaml) {
        List<String> issues = new ArrayList<>();

        String title = yaml.getString("menu_title");
        if (title == null || title.isBlank()) {
            issues.add("menu_title: missing");
            title = "";
        }

        int size = yaml.getInt("size", 9);
        if (size < 9 || size > 54 || size % 9 != 0) {
            issues.add("size: must be between 9 and 54 and a multiple of 9 (got " + size + ")");
        }

        String openCommand = yaml.getString("open_command", "");
        if (yaml.isSet("open_command") && (openCommand == null || openCommand.isBlank()
                || !openCommand.matches("[a-z0-9_]{1,32}"))) {
            issues.add("open_command: must be 1-32 chars of [a-z0-9_] or absent");
        }

        List<MenuSpec.Requirement> openRequirements = parseRequirements(
                yaml, "open_requirement.requirements", issues);
        List<MenuSpec.Action> openActions = parseActions(
                yaml, "open_commands", issues);

        List<MenuSpec.ItemSpec> items = new ArrayList<>();
        ConfigurationSection itemsSection = yaml.getConfigurationSection("items");
        if (itemsSection == null) {
            issues.add("items: missing section");
        } else {
            java.util.Set<Integer> taken = new java.util.HashSet<>();
            for (String key : itemsSection.getKeys(false)) {
                ConfigurationSection item = itemsSection.getConfigurationSection(key);
                if (item == null) {
                    issues.add("items." + key + ": must be a section");
                    continue;
                }
                parseItem(menuId, key, item, size, taken, items, issues);
            }
            if (items.isEmpty() && issues.isEmpty()) {
                issues.add("items: no valid items");
            }
        }

        MenuSpec spec = issues.isEmpty()
                ? new MenuSpec(menuId, title, size,
                        openCommand == null ? "" : openCommand,
                        List.copyOf(openRequirements), List.copyOf(openActions),
                        List.copyOf(items))
                : null;
        return new LoadedMenu(spec, issues);
    }

    private void parseItem(String menuId, String key, ConfigurationSection item,
            int menuSize, java.util.Set<Integer> taken, List<MenuSpec.ItemSpec> out,
            List<String> issues) {
        String path = "items." + key;

        String material = item.getString("material");
        if (material == null || material.isBlank()) {
            issues.add(path + ".material: missing");
            return;
        }
        String canonicalMaterial = material.toUpperCase(Locale.ROOT);
        if (!materialValidator.isValid(canonicalMaterial)) {
            issues.add(path + ".material: unknown material '" + material + "'");
            return;
        }

        int amount = item.getInt("amount", 1);
        if (amount < 1 || amount > 64) {
            issues.add(path + ".amount: must be between 1 and 64 (got " + amount + ")");
            amount = 1;
        }

        List<Integer> slots = new ArrayList<>();
        if (item.isInt("slot")) {
            slots.add(item.getInt("slot"));
        } else if (item.isList("slots")) {
            for (int slot : item.getIntegerList("slots")) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            issues.add(path + ": needs 'slot' (int) or 'slots' (int list)");
            return;
        }
        for (int slot : slots) {
            if (slot < 0 || slot >= menuSize) {
                issues.add(path + ": slot " + slot + " out of menu bounds (0.." + (menuSize - 1) + ")");
            }
            if (!taken.add(slot)) {
                issues.add(path + ": slot " + slot + " already used by another item");
            }
        }

        String displayName = item.getString("display_name", canonicalMaterial);
        List<String> lore = item.getStringList("lore");

        List<MenuSpec.Requirement> viewRequirements = parseRequirements(
                item, "view_requirement.requirements", issues);
        List<MenuSpec.Requirement> leftReq = parseRequirements(
                item, "left_click_requirement.requirements", issues);
        List<MenuSpec.Requirement> rightReq = parseRequirements(
                item, "right_click_requirement.requirements", issues);
        List<MenuSpec.Action> leftActions = parseActions(item, "left_click_commands", issues);
        List<MenuSpec.Action> rightActions = parseActions(item, "right_click_commands", issues);

        out.add(new MenuSpec.ItemSpec(key, canonicalMaterial, amount,
                List.copyOf(slots), displayName == null ? "" : displayName,
                List.copyOf(lore), viewRequirements, leftReq, rightReq,
                leftActions, rightActions));
    }

    private List<MenuSpec.Requirement> parseRequirements(ConfigurationSection section,
            String path, List<String> issues) {
        List<MenuSpec.Requirement> out = new ArrayList<>();
        ConfigurationSection reqSection = section.getConfigurationSection(path);
        if (reqSection == null) {
            return out;
        }
        for (String reqKey : reqSection.getKeys(false)) {
            ConfigurationSection entry = reqSection.getConfigurationSection(reqKey);
            if (entry == null) {
                issues.add(path + "." + reqKey + ": must be a section");
                continue;
            }
            String type = entry.getString("type");
            if (type == null) {
                issues.add(path + "." + reqKey + ": missing 'type'");
                continue;
            }
            String canonicalType = type.toLowerCase(Locale.ROOT).trim();
            if (!REQUIREMENT_TYPES.contains(canonicalType)) {
                issues.add(path + "." + reqKey + ": unsupported requirement type '" + type
                        + "' (supported: has permission)");
                continue;
            }
            String value = entry.getString("value");
            if (value == null || value.isBlank()) {
                issues.add(path + "." + reqKey + ".value: missing permission node");
                continue;
            }
            out.add(new MenuSpec.Requirement(MenuSpec.Requirement.Type.HAS_PERMISSION, value));
        }
        return out;
    }

    private List<MenuSpec.Action> parseActions(ConfigurationSection section,
            String path, List<String> issues) {
        List<MenuSpec.Action> out = new ArrayList<>();
        for (String raw : section.getStringList(path)) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            // DeluxeMenus action syntax: [type] payload
            String trimmed = raw.strip();
            if (!trimmed.startsWith("[")) {
                issues.add(path + ": '" + abbreviate(trimmed) + "' must start with [type]");
                continue;
            }
            int close = trimmed.indexOf(']');
            if (close < 1) {
                issues.add(path + ": '" + abbreviate(trimmed) + "' missing closing ]");
                continue;
            }
            String type = trimmed.substring(1, close).toLowerCase(Locale.ROOT).trim();
            String payload = trimmed.substring(close + 1).strip();
            Optional<MenuSpec.Action.Type> parsed = switch (type) {
                case "close" -> Optional.of(MenuSpec.Action.Type.CLOSE);
                case "message" -> Optional.of(MenuSpec.Action.Type.MESSAGE);
                case "sound" -> Optional.of(MenuSpec.Action.Type.SOUND);
                case "player" -> Optional.of(MenuSpec.Action.Type.PLAYER_COMMAND);
                case "console" -> Optional.of(MenuSpec.Action.Type.CONSOLE_COMMAND);
                default -> Optional.empty();
            };
            if (parsed.isEmpty()) {
                issues.add(path + ": unsupported action type '" + type
                        + "' (supported: " + String.join(", ", ACTION_TYPES) + ")");
                continue;
            }
            MenuSpec.Action.Type actionType = parsed.get();
            if (actionType != MenuSpec.Action.Type.CLOSE && payload.isEmpty()) {
                issues.add(path + ": action [" + type + "] needs a payload");
                continue;
            }
            if (actionType == MenuSpec.Action.Type.PLAYER_COMMAND && !payload.startsWith("/")) {
                issues.add(path + ": [player] action payload must start with '/'");
                continue;
            }
            if (actionType == MenuSpec.Action.Type.CONSOLE_COMMAND && !payload.startsWith("/")) {
                issues.add(path + ": [console] action payload must start with '/'");
                continue;
            }
            out.add(new MenuSpec.Action(actionType, payload));
        }
        return out;
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 37) + "..." : s;
    }

    /** Parsed menu (null spec when invalid) + every collected issue. */
    public record LoadedMenu(MenuSpec spec, List<String> issues) {
        public boolean valid() {
            return spec != null && issues.isEmpty();
        }
    }
}
