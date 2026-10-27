package net.zaminmc.rebellion.items.menu;

/**
 * The entity a menu is evaluated against — a thin abstraction so requirement
 * and action logic is unit-testable without a live {@code Player}. The
 * Bukkit-facing host implements this against a real player.
 */
public interface MenuSubject {

    boolean hasPermission(String permission);

    /** Sends a MiniMessage-rendered message to the subject. */
    void sendMessage(String miniMessage);

    /** Plays a sound to the subject. */
    void playSound(String soundKey);

    /** Makes the subject run a command as themselves. */
    void runPlayerCommand(String command);

    /** Runs a console command under the documented console-action gate. */
    void runConsoleCommand(String command);

    /** Closes the subject's currently open menu. */
    void closeMenu();

    /** @return display name for placeholder rendering (no SQL, memory only). */
    default String displayName() {
        return "";
    }
}
