package net.zaminmc.rebellion.items.command;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
import net.zaminmc.rebellion.api.module.ModuleState;
import net.zaminmc.rebellion.items.audit.AuditEntry;
import net.zaminmc.rebellion.items.audit.InventoryAudit;
import net.zaminmc.rebellion.items.menu.MenuCommandBridge;
import net.zaminmc.rebellion.items.menu.MenuHost;
import net.zaminmc.rebellion.items.ItemsModuleState;
import net.zaminmc.rebellion.items.menu.MenuSpec;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
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
    private static final List<String> SUBS =
            List.of("inspect", "annotate", "give", "audit", "reload", "status");

    private final ItemsModuleState state;
    private final ModuleRegistry modules;
    private final MenuCommandBridge menuCommands;
    private final Logger logger;

    public ItemsCommand(ItemsModuleState state, ModuleRegistry modules,
            MenuCommandBridge menuCommands, Logger logger) {
        this.state = state;
        this.modules = modules;
        this.menuCommands = menuCommands;
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
            case "give" -> give(sender, args);
            case "audit" -> audit(sender, args);
            case "reload" -> reload(sender);
            case "status" -> status(sender);
            default -> usage(sender);
        };
    }

    private boolean usage(CommandSender sender) {
        send(sender, "<red>Usage: /rebellionitems <inspect|annotate|give|audit|reload|status></red>");
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

    /**
     * Restricted mint: creates one stack of a DEFINED custom item only.
     * There is no arbitrary-combination mint anywhere in this plugin family.
     */
    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rebellion.items.admin.give")) {
            return deny(sender);
        }
        if (args.length < 2) {
            send(sender, "<red>Usage: /rebellionitems give <item-id> [player]</red>");
            return true;
        }
        String id = args[1].toLowerCase(java.util.Locale.ROOT);
        var spec = state.customItems().get(id);
        if (spec == null) {
            send(sender, "<red>No custom item '" + escape(id) + "' is defined in items.yml.</red>");
            return true;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                send(sender, "<red>Player '" + escape(args[2]) + "' is not online.</red>");
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            send(sender, "<red>Console must specify a player: /rebellionitems give <id> <player></red>");
            return true;
        }

        var factory = new net.zaminmc.rebellion.items.lore.CustomItemFactory(
                spec, state.rarities(), state.annotator(), logger);
        var stack = factory.create();
        if (stack == null) {
            send(sender, "<red>Refused: the item's base material is unavailable on this server version.</red>");
            return true;
        }
        target.getInventory().addItem(stack);
        send(sender, "<green>Gave one '" + escape(spec.displayName()) + "' (id: " + escape(id)
                + ", rarity: " + spec.rarityKey() + (spec.tracksInstances() ? ", unique instance stamped"
                : "") + ") to " + escape(target.getName()) + ".</green>");
        logger.info(() -> "Admin " + sender.getName() + " gave defined custom item '" + id
                + "' to " + target.getName());
        return true;
    }

    /**
     * Live admin audit view over one online player's containers. Every
     * RebellionSMP-tagged stack is listed with its identity; untagged stacks
     * are only counted. Read-only: audit never mutates the inventory.
     */
    private boolean audit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rebellion.items.admin.audit")) {
            return deny(sender);
        }
        if (args.length < 2) {
            send(sender, "<red>Usage: /rebellionitems audit <player></red>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "<red>Player '" + escape(args[1]) + "' is not online "
                    + "(audit is a live view of loaded inventories).</red>");
            return true;
        }

        List<AuditEntry> entries = new java.util.ArrayList<>(68);
        collect(entries, "armor", target.getInventory().getArmorContents());
        collect(entries, "offhand", target.getInventory().getExtraContents());
        collect(entries, "inventory", target.getInventory().getStorageContents());
        collect(entries, "enderchest", target.getEnderChest().getContents());

        var report = InventoryAudit.of(entries);
        send(sender, "<gold>RebellionSMP audit — " + escape(target.getName()) + "</gold>");
        send(sender, "<gray>scanned " + report.scannedStacks() + " stacks, tagged "
                + report.taggedStacks() + ", unique instances " + report.uniqueStacks()
                + "</gray>");
        for (InventoryAudit.TaggedLine line : report.tagged()) {
            send(sender, "<gray>" + line.container() + "[" + line.slot() + "]</gray> <white>"
                    + escape(line.material()) + " x" + line.amount() + "</white> <gray>rarity "
                    + escape(line.rarityKey())
                    + (line.itemId() == null ? "" : ", item " + escape(line.itemId()))
                    + (line.instanceId() == null ? "" : ", instance " + escape(line.instanceId()))
                    + (line.talismanTierKey() == null ? "" : ", tier " + escape(line.talismanTierKey()))
                    + "</gray>");
        }
        if (!report.byRarity().isEmpty()) {
            send(sender, "<gray>per rarity: " + report.byRarity().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("") + "</gray>");
        }
        if (report.tagged().isEmpty()) {
            send(sender, "<gray>No RebellionSMP-tagged stacks found.</gray>");
        }
        return true;
    }

    /** Appends one container's stacks (skipping null/air) with 0-based slots. */
    private void collect(List<AuditEntry> entries, String container, ItemStack[] contents) {
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            entries.add(new AuditEntry(container, slot, stack.getType().name(),
                    stack.getAmount(), state.annotator().identity(stack).orElse(null)));
        }
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("rebellion.items.admin.reload")) {
            return deny(sender);
        }
        var result = state.reloadAll();
        if (result.isEmpty()) {
            // Dynamic per-menu commands track the freshly activated menus.
            menuCommands.sync(Bukkit.getServer().getCommandMap(), state.menus().active());
            send(sender, "<green>Reloaded configuration, rarities, messages and menus.</green>");
            send(sender, "<gray>Menu commands active: "
                    + (menuCommands.registeredLabels().isEmpty() ? "none"
                            : "/" + String.join(", /", menuCommands.registeredLabels()))
                    + "</gray>");
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
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase(java.util.Locale.ROOT);
            return state.customItems().keySet().stream()
                    .filter(id -> id.startsWith(prefix))
                    .sorted()
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("audit")) {
            String prefix = args[1].toLowerCase(java.util.Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .limit(20)
                    .toList();
        }
        return List.of();
    }
}
