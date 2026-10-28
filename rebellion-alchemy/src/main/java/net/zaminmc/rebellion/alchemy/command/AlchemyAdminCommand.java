package net.zaminmc.rebellion.alchemy.command;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.alchemy.AlchemyConfig;
import net.zaminmc.rebellion.alchemy.AlchemyOutcome;
import net.zaminmc.rebellion.alchemy.BagDefinition;
import net.zaminmc.rebellion.alchemy.controller.AlchemyPlayerCache;
import net.zaminmc.rebellion.alchemy.controller.Bags;
import net.zaminmc.rebellion.alchemy.db.AlchemyStore.EventRow;
import net.zaminmc.rebellion.alchemy.AlchemyService;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /alchemyadmin} — audit, the ADR-0019 admin-grant path and the
 * restricted bag mint (ADR-0020 §5). Console-legal; player targets resolve
 * to known UUIDs only (online exact, then the non-blocking cached lookup —
 * never a web call, never a guess). Nothing here mints currency or clears
 * debts; grants are audited rows and the bag mint is a stamped, unique-id
 * item — the items-module restricted-mint discipline.
 */
public final class AlchemyAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("events", "grant", "bag");

    private final org.bukkit.plugin.Plugin plugin;
    private final AlchemyService service;
    private final AlchemyConfig config;
    private final AlchemyPlayerCache cache;
    private final Bags bags;
    private final MessageCatalog messages;

    public AlchemyAdminCommand(@NotNull org.bukkit.plugin.Plugin plugin,
            @NotNull AlchemyService service,
            @NotNull AlchemyConfig config, @NotNull AlchemyPlayerCache cache,
            @NotNull Bags bags, @NotNull MessageCatalog messages) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.cache = cache;
        this.bags = bags;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(messages.msg("admin.usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "events" -> events(sender, args);
            case "grant" -> grant(sender, args);
            case "bag" -> mint(sender, args);
            default -> sender.sendMessage(messages.msg("admin.usage"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // events <player> — the audit read
    // ------------------------------------------------------------------

    private void events(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(messages.msg("admin.events-usage"));
            return;
        }
        UUID target = resolveKnown(args[1]);
        if (target == null) {
            sender.sendMessage(messages.msg("admin.player-unknown",
                    Placeholder.unparsed("name", args[1])));
            return;
        }
        service.eventsFor(target).thenAccept(rows -> Bukkit.getScheduler()
                .runTask(plugin, () -> {
                    sender.sendMessage(messages.msg("admin.events-header",
                            Placeholder.unparsed("name", args[1])));
                    if (rows.isEmpty()) {
                        sender.sendMessage(messages.msg("admin.events-none"));
                        return;
                    }
                    int from = Math.max(0, rows.size() - 10);
                    for (EventRow row : rows.subList(from, rows.size())) {
                        sender.sendMessage(messages.msg("admin.events-line",
                                Placeholder.unparsed("type", row.type()),
                                Placeholder.unparsed("outcome",
                                        row.outcome() == null ? "-" : row.outcome()),
                                Placeholder.unparsed("detail", row.detail() == null
                                        ? "-" : row.detail())));
                    }
                }));
    }

    // ------------------------------------------------------------------
    // grant <player> <key> — the ADR-0019 admin grant
    // ------------------------------------------------------------------

    private void grant(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(messages.msg("admin.grant-usage"));
            return;
        }
        UUID target = resolveKnown(args[1]);
        if (target == null) {
            sender.sendMessage(messages.msg("admin.player-unknown",
                    Placeholder.unparsed("name", args[1])));
            return;
        }
        String key = args[2].toLowerCase(Locale.ROOT);
        service.grantUnlock(target, key, "admin:" + sender.getName())
                .thenAccept(result -> render(sender, switch (result.outcome()) {
                    case UNLOCK_GRANTED -> "admin.grant.ok";
                    case UNLOCK_GRANT_NOOP -> "admin.grant.noop";
                    case UNLOCK_UNKNOWN -> "admin.grant.unknown";
                    default -> "common.unavailable";
                }, Placeholder.unparsed("name", String.valueOf(args[1])),
                        Placeholder.unparsed("key", key)));
        cache.refreshUnlocks(target); // no-op for offline/uncached viewers
    }

    // ------------------------------------------------------------------
    // bag <player> <key> [amount] — the restricted mint
    // ------------------------------------------------------------------

    private void mint(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(messages.msg("admin.bag-usage"));
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        if (online == null) {
            sender.sendMessage(messages.msg("admin.bag-online-only"));
            return;
        }
        String key = args[2].toLowerCase(Locale.ROOT);
        BagDefinition definition = config.bags().get(key);
        if (definition == null || !definition.enabled()) {
            sender.sendMessage(messages.msg("admin.bag-unknown",
                    Placeholder.unparsed("key", key)));
            return;
        }
        int amount = 1;
        if (args.length > 3) {
            try {
                amount = Integer.parseInt(args[3].trim());
            } catch (NumberFormatException e) {
                amount = 0;
            }
        }
        if (amount < 1 || amount > 16) {
            sender.sendMessage(messages.msg("admin.bag-usage"));
            return;
        }
        for (int i = 0; i < amount; i++) {
            ItemStack bagItem = new ItemStack(definition.material());
            bags.stamp(bagItem, definition.key(), java.util.UUID.randomUUID());
            HashMap<Integer, ItemStack> leftover =
                    online.getInventory().addItem(bagItem);
            // Full inventory: the bag drops at the player's feet (the mint
            // never destroys what it made).
            leftover.values().forEach(stack -> online.getWorld()
                    .dropItemNaturally(online.getLocation(), stack));
        }
        sender.sendMessage(messages.msg("admin.bag.minted",
                Placeholder.unparsed("name", online.getName()),
                Placeholder.unparsed("key", definition.key()),
                Placeholder.unparsed("count", String.valueOf(amount))));
        online.sendMessage(messages.msg("admin.bag.received",
                Placeholder.unparsed("key", definition.key()),
                Placeholder.unparsed("count", String.valueOf(amount))));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void render(CommandSender sender, String key,
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        Bukkit.getScheduler().runTask(plugin,
                () -> sender.sendMessage(messages.msg(key, resolvers)));
    }

    /** Online exact match, then the cached offline record; null = unknown. */
    private static UUID resolveKnown(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        var cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
            @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("grant")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return config.unlocks().keySet().stream()
                    .filter(k -> k.startsWith(prefix)).sorted().toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bag")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return config.bags().keySet().stream()
                    .filter(k -> k.startsWith(prefix)).sorted().toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted().toList();
        }
        return List.of();
    }
}
