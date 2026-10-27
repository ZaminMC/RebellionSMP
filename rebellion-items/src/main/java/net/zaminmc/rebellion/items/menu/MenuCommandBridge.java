package net.zaminmc.rebellion.items.menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Registers one live server command per menu that declares
 * {@code open_command}, via the Paper {@code Server#getCommandMap()} API
 * (verified on paper-api 26.3.226: {@code CommandMap#register(String,
 * Command)}, {@code CommandMap#getKnownCommands()},
 * {@code Command#register/unregister(CommandMap)} all exist).
 *
 * <p>Safety rules, in the order they are enforced:</p>
 * <ol>
 *   <li>Duplicate {@code open_command} across menus is refused for every
 *       claimant — file order must never decide who owns a command. The menu
 *       registry also rejects duplicates at load time, so this is defense in
 *       depth for menus registered through other paths.</li>
 *   <li>A label already taken by another command (either {@code /label} or
 *       {@code /rebellion:label}) is never registered — dynamic menus must
 *       not hijack existing commands like {@code /spawn}.</li>
 *   <li>Every re-{@link #sync} first unregisters the previous set, so reloads
 *       cannot leak stale commands.</li>
 * </ol>
 *
 * <p>Registered commands carry the per-menu permission
 * {@code rebellion.items.menu.<menu-id>}; the {@link Opener} resolves the
 * <em>live</em> menu spec at execution time, so a reloaded menu definition
 * is what actually opens.</p>
 */
public final class MenuCommandBridge {

    /** Opens a menu for a player; return false when it cannot open right now. */
    @FunctionalInterface
    public interface Opener {
        boolean open(Player player, String menuId);
    }

    private final Logger logger;
    private final Opener opener;
    /** label -> command currently registered by this bridge (only ours). */
    private final Map<String, DynamicMenuCommand> registered = new LinkedHashMap<>();

    public MenuCommandBridge(Logger logger, Opener opener) {
        this.logger = logger;
        this.opener = opener;
    }

    /**
     * Replaces the registered set: unregisters everything this bridge owns,
     * then registers the current menus. Safe to call at boot and after every
     * successful menu reload.
     */
    public synchronized void sync(CommandMap commandMap, Map<String, MenuSpec> menus) {
        unregisterAll(commandMap);

        Map<String, List<String>> claimants = new LinkedHashMap<>();
        for (MenuSpec spec : menus.values()) {
            if (spec.openCommand() == null || spec.openCommand().isBlank()) {
                continue;
            }
            claimants.computeIfAbsent(spec.openCommand(), k -> new ArrayList<>()).add(spec.id());
        }

        List<String> registeredLabels = new ArrayList<>();
        for (Map.Entry<String, List<String>> claim : claimants.entrySet()) {
            String label = claim.getKey();
            List<String> owners = claim.getValue();
            if (owners.size() > 1) {
                logger.warning("open_command '/" + label + "' declared by menus " + owners
                        + "; refusing to register it for any of them");
                continue;
            }
            if (commandMap.getKnownCommands().containsKey(label)
                    || commandMap.getKnownCommands().containsKey("rebellion:" + label)) {
                logger.warning("menu '" + owners.get(0) + "': open_command '/" + label
                        + "' collides with an existing server command; not registered");
                continue;
            }
            DynamicMenuCommand command = new DynamicMenuCommand(label, owners.get(0), opener);
            if (commandMap.register("rebellion", command)) {
                registered.put(label, command);
                registeredLabels.add(label);
            } else {
                logger.warning("menu '" + owners.get(0) + "': server refused to register '/"
                        + label + "'; not available as a command");
            }
        }
        if (registeredLabels.isEmpty()) {
            logger.info("No menu commands registered (no open_command declared, "
                    + "or all were refused).");
        } else {
            logger.info("Menu commands registered: /" + String.join(", /", registeredLabels));
        }
    }

    /** Removes every command this bridge registered. Idempotent. */
    public synchronized void unregisterAll(CommandMap commandMap) {
        if (registered.isEmpty()) {
            return;
        }
        Map<String, Command> known = commandMap.getKnownCommands();
        for (var it = registered.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, DynamicMenuCommand> entry = it.next();
            String label = entry.getKey();
            // Base Command#unregister only detaches the CommandMap reference;
            // the live known-commands map is cleaned explicitly so removal is
            // guaranteed under any CommandMap implementation.
            entry.getValue().unregister(commandMap);
            known.remove(label);
            known.remove("rebellion:" + label);
            it.remove();
        }
    }

    /** @return labels currently registered by this bridge (live copy). */
    public synchronized List<String> registeredLabels() {
        return List.copyOf(registered.keySet());
    }

    /** One per-menu dynamic command. Permission: rebellion.items.menu.&lt;id&gt;. */
    static final class DynamicMenuCommand extends Command {

        private final String menuId;
        private final Opener opener;

        DynamicMenuCommand(String label, String menuId, Opener opener) {
            super(label, "Opens the RebellionSMP menu '" + menuId + "'.",
                    "/" + label, List.of());
            this.menuId = menuId;
            this.opener = opener;
            setPermission("rebellion.items.menu." + menuId);
        }

        String menuId() {
            return menuId;
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Menus can only be opened by players."));
                return true;
            }
            if (!testPermissionSilent(sender)) {
                sender.sendMessage(Component.text("You do not have permission to do that."));
                return true;
            }
            if (!opener.open(player, menuId)) {
                sender.sendMessage(Component.text("That menu is not available right now."));
            }
            return true;
        }
    }
}
