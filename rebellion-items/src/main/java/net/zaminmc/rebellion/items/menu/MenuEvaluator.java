package net.zaminmc.rebellion.items.menu;

import java.util.List;

/**
 * Evaluates requirements and executes actions against a {@link MenuSubject}.
 * Pure, testable orchestration — the Bukkit host only forwards events here.
 *
 * <p>Ordering contract (master prompt §9): requirement evaluation precedes
 * click actions; a click whose requirements fail produces no action side
 * effects at all. Visibility checks run server-side again at action time —
 * menu visibility alone is never authorization.</p>
 */
public final class MenuEvaluator {

    /**
     * @return true when every requirement passes (empty list passes).
     */
    public static boolean requirementsMet(List<MenuSpec.Requirement> requirements, MenuSubject subject) {
        for (MenuSpec.Requirement requirement : requirements) {
            if (!requirement.satisfiedBy(subject)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Executes actions in declaration order. Console actions are refused
     * unless the host allows them (config flag + permission checked by the
     * host's implementation of {@link MenuSubject#runConsoleCommand}).
     */
    public static void execute(List<MenuSpec.Action> actions, MenuSubject subject) {
        for (MenuSpec.Action action : actions) {
            switch (action.type()) {
                case CLOSE -> subject.closeMenu();
                case MESSAGE -> subject.sendMessage(action.value());
                case SOUND -> subject.playSound(action.value());
                case PLAYER_COMMAND -> subject.runPlayerCommand(action.value());
                case CONSOLE_COMMAND -> subject.runConsoleCommand(action.value());
            }
        }
    }

    /**
     * Full click flow: requirements first, then actions. Returns whether the
     * click was authorized; callers must cancel the inventory event either way
     * for menu inventories (decorative items are never removable).
     */
    public static boolean handleClick(List<MenuSpec.Requirement> requirements,
            List<MenuSpec.Action> actions, MenuSubject subject) {
        if (!requirementsMet(requirements, subject)) {
            return false;
        }
        execute(actions, subject);
        return true;
    }
}
