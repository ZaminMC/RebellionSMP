package net.zaminmc.rebellion.items.menu;

import java.util.List;
import java.util.Map;

/**
 * Parsed, validated representation of one DeluxeMenus-shaped menu.
 *
 * <p>Instances are immutable snapshots produced by {@link MenuLoader};
 * invalid files never produce a spec.</p>
 */
public record MenuSpec(
        String id,
        String title,
        int size,
        String openCommand,
        List<Requirement> openRequirements,
        List<Action> openActions,
        List<ItemSpec> items) {

    /** One menu button. */
    public record ItemSpec(
            String key,
            String material,
            int amount,
            List<Integer> slots,
            String displayName,
            List<String> lore,
            List<Requirement> viewRequirements,
            List<Requirement> leftClickRequirements,
            List<Requirement> rightClickRequirements,
            List<Action> leftClickActions,
            List<Action> rightClickActions) {
    }

    /** Supported requirement type: has permission (identifier {@code has permission}). */
    public record Requirement(Type type, String value) {
        public enum Type { HAS_PERMISSION }

        public boolean satisfiedBy(MenuSubject subject) {
            return switch (type) {
                case HAS_PERMISSION -> subject.hasPermission(value);
            };
        }
    }

    /**
     * Supported action types. Console actions are gated behind
     * {@code allow-console-actions} in config.yml AND the
     * {@code rebellion.items.admin.menuconsole} permission at execution.
     */
    public record Action(Type type, String value) {
        public enum Type { CLOSE, MESSAGE, SOUND, PLAYER_COMMAND, CONSOLE_COMMAND }
    }

    /** @return slot → item map for quick lookup; validated non-overlapping. */
    public Map<Integer, ItemSpec> bySlot() {
        java.util.Map<Integer, ItemSpec> out = new java.util.HashMap<>();
        for (ItemSpec item : items) {
            for (int slot : item.slots()) {
                out.put(slot, item);
            }
        }
        return java.util.Map.copyOf(out);
    }
}
