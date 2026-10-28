package net.zaminmc.rebellion.items.command;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
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
 * {@code rebellion.items.admin} guard in plugin.yml is defense in depth.
 * All player feedback renders through the module's messages.yml catalog
 * (ADR-0012); dynamic values are inserted as MiniMessage placeholders, so
 * config or player text can never inject tags — the resolver inserts them
 * as literal text by construction.</p>
 */
public final class ItemsCommand implements CommandExecutor, TabCompleter {

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

    /** Live catalog: reads through the state so a reload takes effect immediately. */
    private MessageCatalog m() {
        return state.messages();
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
        sender.sendMessage(m().msg("usage"));
        return true;
    }

    private boolean inspect(CommandSender sender) {
        if (!sender.hasPermission("rebellion.items.admin.inspect")) {
            return deny(sender);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(m().msg("inspect.player-only"));
            return true;
        }
        var held = player.getInventory().getItemInMainHand();
        var identity = state.annotator().identity(held);
        if (identity.isEmpty()) {
            sender.sendMessage(m().msg("inspect.none"));
            return true;
        }
        var id = identity.get();
        sender.sendMessage(m().raw("inspect.header"));
        sender.sendMessage(m().raw("inspect.line",
                Placeholder.unparsed("schema", String.valueOf(id.schema())),
                Placeholder.unparsed("rarity", id.rarityKey()),
                Placeholder.unparsed("itemid", id.itemId() == null ? "-" : id.itemId()),
                Placeholder.unparsed("instance", id.instanceId() == null ? "-" : String.valueOf(id.instanceId())),
                Placeholder.unparsed("tier", id.talismanTierKey() == null ? "-" : id.talismanTierKey())));
        return true;
    }

    private boolean annotate(CommandSender sender) {
        if (!sender.hasPermission("rebellion.items.admin.annotate")) {
            return deny(sender);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(m().msg("annotate.player-only"));
            return true;
        }
        var held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage(m().msg("annotate.hold-stack"));
            return true;
        }
        boolean applied = state.annotator().annotate(held);
        sender.sendMessage(m().msg(applied ? "annotate.success" : "annotate.rejected"));
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
            sender.sendMessage(m().msg("give.usage"));
            return true;
        }
        String id = args[1].toLowerCase(java.util.Locale.ROOT);
        var spec = state.customItems().get(id);
        if (spec == null) {
            sender.sendMessage(m().msg("give.unknown-item",
                    Placeholder.unparsed("item", id)));
            return true;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(m().msg("give.player-offline",
                        Placeholder.unparsed("player", args[2])));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(m().msg("give.console-needs-player"));
            return true;
        }

        var factory = new net.zaminmc.rebellion.items.lore.CustomItemFactory(
                spec, state.rarities(), state.annotator(), logger);
        var stack = factory.create();
        if (stack == null) {
            sender.sendMessage(m().msg("give.material-missing"));
            return true;
        }
        target.getInventory().addItem(stack);
        sender.sendMessage(m().msg("give.success",
                Placeholder.unparsed("item", spec.displayName()),
                Placeholder.unparsed("id", id),
                Placeholder.unparsed("rarity", spec.rarityKey()),
                Placeholder.unparsed("extra", spec.tracksInstances()
                        ? ", unique instance stamped" : ""),
                Placeholder.unparsed("player", target.getName())));
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
            sender.sendMessage(m().msg("audit.usage"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(m().msg("audit.player-offline",
                    Placeholder.unparsed("player", args[1])));
            return true;
        }

        List<AuditEntry> entries = new java.util.ArrayList<>(68);
        collect(entries, "armor", target.getInventory().getArmorContents());
        collect(entries, "offhand", target.getInventory().getExtraContents());
        collect(entries, "inventory", target.getInventory().getStorageContents());
        collect(entries, "enderchest", target.getEnderChest().getContents());

        var report = InventoryAudit.of(entries);
        sender.sendMessage(m().raw("audit.header",
                Placeholder.unparsed("player", target.getName())));
        sender.sendMessage(m().raw("audit.summary",
                Placeholder.unparsed("scanned", String.valueOf(report.scannedStacks())),
                Placeholder.unparsed("tagged", String.valueOf(report.taggedStacks())),
                Placeholder.unparsed("unique", String.valueOf(report.uniqueStacks()))));
        for (InventoryAudit.TaggedLine line : report.tagged()) {
            StringBuilder extra = new StringBuilder();
            if (line.itemId() != null) {
                extra.append(", item ").append(line.itemId());
            }
            if (line.instanceId() != null) {
                extra.append(", instance ").append(line.instanceId());
            }
            if (line.talismanTierKey() != null) {
                extra.append(", tier ").append(line.talismanTierKey());
            }
            sender.sendMessage(m().raw("audit.line",
                    Placeholder.unparsed("container", line.container()),
                    Placeholder.unparsed("slot", String.valueOf(line.slot())),
                    Placeholder.unparsed("material", line.material()),
                    Placeholder.unparsed("amount", String.valueOf(line.amount())),
                    Placeholder.unparsed("rarity", line.rarityKey()),
                    Placeholder.unparsed("extra", extra.toString())));
        }
        if (!report.byRarity().isEmpty()) {
            sender.sendMessage(m().raw("audit.per-rarity",
                    Placeholder.unparsed("counts", report.byRarity().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .reduce((a, b) -> a + ", " + b).orElse(""))));
        }
        if (report.tagged().isEmpty()) {
            sender.sendMessage(m().raw("audit.none"));
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
            sender.sendMessage(m().msg("reload.success"));
            sender.sendMessage(m().raw("reload.menu-commands",
                    Placeholder.unparsed("commands",
                            menuCommands.registeredLabels().isEmpty()
                                    ? "none" : "/" + String.join(", /", menuCommands.registeredLabels()))));
        } else {
            sender.sendMessage(m().msg("reload.failed"));
            result.forEach(issue -> sender.sendMessage(m().raw("reload.issue",
                    Placeholder.unparsed("issue", issue))));
        }
        return true;
    }

    private boolean status(CommandSender sender) {
        if (!sender.hasPermission("rebellion.items.admin.status")) {
            return deny(sender);
        }
        Map<String, MenuSpec> menus = state.menus().active();
        sender.sendMessage(m().raw("status.header"));
        sender.sendMessage(m().raw("status.config",
                Placeholder.unparsed("mode", String.valueOf(state.config().annotationMode())),
                Placeholder.unparsed("position", String.valueOf(state.config().lorePosition()))));
        sender.sendMessage(m().raw("status.counts",
                Placeholder.unparsed("overrides", String.valueOf(state.rarities().overrides().size())),
                Placeholder.unparsed("menus", String.valueOf(menus.size())),
                Placeholder.unparsed("menulist", menus.isEmpty()
                        ? " (none registered)" : " (" + String.join(", ", menus.keySet()) + ")")));
        modules.health("items").ifPresent(h -> sender.sendMessage(m().raw("status.module",
                Placeholder.unparsed("state", String.valueOf(h.state())))));
        return true;
    }

    private boolean openMenu(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(m().msg("menu.player-only"));
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(m().msg("menu.usage"));
            return true;
        }
        String id = args[0].toLowerCase(java.util.Locale.ROOT);
        MenuSpec spec = state.menus().active().get(id);
        if (spec == null) {
            sender.sendMessage(m().msg("menu.unknown",
                    Placeholder.unparsed("menu", id)));
            return true;
        }
        // Per-menu permission: visibility is not authorization.
        if (!player.hasPermission("rebellion.items.menu." + id)) {
            return deny(player);
        }
        MenuHost host = new MenuHost(spec, player, state.config().allowConsoleActions(),
                state.messages(), logger);
        host.open();
        return true;
    }

    private boolean deny(CommandSender sender) {
        sender.sendMessage(m().msg("common.no-permission"));
        return true;
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
