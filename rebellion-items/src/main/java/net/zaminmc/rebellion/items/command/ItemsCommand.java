package net.zaminmc.rebellion.items.command;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
import net.zaminmc.rebellion.api.module.ModuleState;
import net.zaminmc.rebellion.items.menu.MenuHost;
import net.zaminmc.rebellion.items.ItemsModuleState;
import net.zaminmc.rebellion.items.menu.MenuSpec;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /rebellionitems} + {@code /rebellionmenu}.
 *
 * <p>Every subcommand re-checks its own permission node; the parent
 * {@code rebellion.items.admin} guard in plugin.yml is defense in depth.</p>
 */
public final class ItemsCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<String> SUBS = List.of("inspect", "annotate", "reload", "status");

    private final ItemsModuleState state;
    private final ModuleRegistry modules;
    private final Logger logger;

    public ItemsCommand(ItemsModuleState state, ModuleRegistry modules, Logger logger) {
        this.state = state;
        this.modules = modules;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        String name = command.getName();
        if (name.equals("rebellionmenu")) {
            return openMenu(sender, args);
        }
        if (args.length == 0) {
            return usage(sender);
        }
        return switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "inspect" -> inspect(sender);
            case "annotate" -> annotate(sender);
            case "reload" -> reload(sender);
            case "status" -> status(sender);
            default -> usage(sender);
        };
    }

    private boolean usage(CommandSender sender) {
        send(sender, "<red>Usage: /rebellionitems <inspect|annotate|reload|status></red>");
        return true;
    }

    private boolean inspect(CommandSender sender) {
        if (!sender.hasPermission("rebellion.items.admin.inspect")) {
            return deny(sender);
        }
        if (!(sender instanceof Player player)) {
            send(sender, "<red>Only players can hold a stack to inspect.</red>");
            return true;
        }
        var held = player.getInventory().getItemInMainHand();
        var identity = state.annotator().identity(held);
        if (identity.isEmpty()) {
            send(sender, "<gray>No RebellionSMP identity on the held stack.</gray>");
            return true;
        }
        var id = identity.get();
        send(sender, "<gold>RebellionSMP item identity</gold>");
        send(sender, "<gray>schema: <white>" + id.schema() + "</white>, rarity: <white>"
                + id.rarityKey() + "</white>, item id: <white>"
                + (id.itemId() == null ? "-" : id.itemId()) + "</white>, instance: <white>"
                + (id.instanceId() == null ? "-" : id.instanceId()) + "</white>, tier: <white>"
                + (id.talismanTierKey() == null ? "-" : id.talismanTierKey()) + "</white></gray>");
        return true;
    }

    private boolean annotate(CommandSender sender) {
        if (!sender.hasPermission("rebellion.items.admin.annotate")) {
            return deny(sender);
        }
        if (!(sender instanceof Player player)) {
            send(sender, "<red>Only players can annotate a held stack.</red>");
            return true;
        }
        var held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            send(sender, "<red>Hold a stack to annotate.</red>");
            return true;
        }
        boolean applied = state.annotator().annotate(held);
        send(sender, applied
                ? "<green>Annotated held stack (idempotent; existing metadata preserved).</green>"
                : "<red>Not annotated: the stack has no ItemMeta or an unknown item schema.</red>");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("rebellion.items.admin.reload")) {
            return deny(sender);
        }
        var result = state.reloadAll();
        if (result.isEmpty()) {
            send(sender, "<green>Reloaded configuration, rarities, messages and menus.</green>");
        } else {
            send(sender, "<red>Reload rejected; previous state stays active. Issues:</red>");
            result.forEach(issue -> send(sender, "<red>- " + escape(issue) + "</red>"));
        }
        return true;
    }

    private boolean status(CommandSender sender) {
        if (!sender.hasPermission("rebellion.items.admin.status")) {
            return deny(sender);
        }
        Map<String, MenuSpec> menus = state.menus().active();
        send(sender, "<gold>RebellionItems status</gold>");
        send(sender, "<gray>annotation mode: <white>" + state.config().annotationMode()
                + "</white>, lore position: <white>" + state.config().lorePosition() + "</white></gray>");
        send(sender, "<gray>rarity overrides: <white>" + state.rarities().overrides().size()
                + "</white>, menus: <white>" + menus.size() + "</white>"
                + (menus.isEmpty() ? " (none registered)" : " (" + String.join(", ", menus.keySet()) + ")")
                + "</gray>");
        modules.health("items").ifPresent(h -> send(sender, "<gray>module state: <white>"
                + h.state() + "</white></gray>"));
        return true;
    }

    private boolean openMenu(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>Menus can only be opened by players.</red>");
            return true;
        }
        if (args.length != 1) {
            send(sender, "<red>Usage: /rebellionmenu <menu-id></red>");
            return true;
        }
        String id = args[0].toLowerCase(java.util.Locale.ROOT);
        MenuSpec spec = state.menus().active().get(id);
        if (spec == null) {
            send(sender, "<red>No menu '" + escape(id) + "' is registered.</red>");
            return true;
        }
        // Per-menu permission: visibility is not authorization.
        if (!player.hasPermission("rebellion.items.menu." + id)) {
            return deny(player);
        }
        MenuHost host = new MenuHost(spec, player,
                state.config().allowConsoleActions(), logger);
        host.open();
        return true;
    }

    private boolean deny(CommandSender sender) {
        send(sender, "<red>You do not have permission to do that.</red>");
        return true;
    }

    private void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }

    /**
     * Neutralizes tag injection for values echoed into MiniMessage templates.
     * Angle brackets are stripped: menu ids and issue strings lose nothing
     * meaningful, and hostile config content cannot smuggle MiniMessage tags.
     */
    private static String escape(String value) {
        return value.replace("<", "").replace(">", "");
    }


    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String[] args) {
        if (command.getName().equals("rebellionmenu")) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
                return state.menus().active().keySet().stream()
                        .filter(id -> id.startsWith(prefix))
                        .sorted()
                        .toList();
            }
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
