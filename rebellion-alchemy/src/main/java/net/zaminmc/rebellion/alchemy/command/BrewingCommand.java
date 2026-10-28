package net.zaminmc.rebellion.alchemy.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.alchemy.AlchemyConfig;
import net.zaminmc.rebellion.alchemy.AlchemyOutcome;
import net.zaminmc.rebellion.alchemy.AlchemyResult;
import net.zaminmc.rebellion.alchemy.AlchemyService;
import net.zaminmc.rebellion.alchemy.AutoRule;
import net.zaminmc.rebellion.alchemy.AutoTrigger;
import net.zaminmc.rebellion.alchemy.CostItem;
import net.zaminmc.rebellion.alchemy.UnlockDefinition;
import net.zaminmc.rebellion.alchemy.UnlockKind;
import net.zaminmc.rebellion.alchemy.controller.AlchemyPlayerCache;
import net.zaminmc.rebellion.alchemy.controller.Costs;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /brewing} — the progression surface and the purchase driver
 * (ADR-0020 §5). The purchase follows the same closed sequence as
 * {@code /ritual}'s claim protocol: verify the (efficiency-reduced) cost
 * set on the main thread through the pure {@link Costs} math →
 * {@code reserveUnlock} → {@code Inventory.removeItem} with leftover
 * detection and exact refund → {@code commitUnlock}. Every typed outcome
 * maps to a message key; every refusal leaves the inventory no worse
 * than before (the digest row is the refund evidence).
 *
 * <p>Rule management drives the ADR-0019 service directly — every gate
 * (trigger/potion whitelists, threshold bounds, cooldown floor, priority
 * range, per-player cap, ownership) is row/config-side — and refreshes
 * the join-loaded cache that the auto-consume evaluator and the drink
 * boost read.</p>
 */
public final class BrewingCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("status", "unlocks", "unlock", "rules");
    private static final List<String> RULE_SUBS = List.of("add", "toggle", "remove");

    private final Plugin plugin;
    private final AlchemyService service;
    private final AlchemyConfig config;
    private final AlchemyPlayerCache cache;
    private final MessageCatalog messages;

    public BrewingCommand(@NotNull Plugin plugin, @NotNull AlchemyService service,
            @NotNull AlchemyConfig config, @NotNull AlchemyPlayerCache cache,
            @NotNull MessageCatalog messages) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.cache = cache;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.msg("player-only"));
            return true;
        }
        if (!player.hasPermission("rebellion.alchemy.use")) {
            player.sendMessage(messages.msg("common.no-permission"));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(messages.msg("usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(player);
            case "unlocks" -> unlocks(player);
            case "unlock" -> unlock(player, args);
            case "rules" -> rules(player, args);
            default -> player.sendMessage(messages.msg("usage"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // status
    // ------------------------------------------------------------------

    private void status(Player player) {
        UUID self = player.getUniqueId();
        service.progress(self).thenAccept(progress ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (progress.maxedOut()) {
                        player.sendMessage(messages.msg("brewing.status.maxed",
                                Placeholder.unparsed("level",
                                        String.valueOf(progress.level()))));
                    } else {
                        player.sendMessage(messages.msg("brewing.status.line",
                                Placeholder.unparsed("level",
                                        String.valueOf(progress.level())),
                                Placeholder.unparsed("xp",
                                        String.valueOf(progress.xpIntoLevel())),
                                Placeholder.unparsed("next",
                                        String.valueOf(progress.nextLevelCost()))));
                    }
                    Set<String> owned = cache.unlocksNow(self);
                    if (owned != null) {
                        player.sendMessage(messages.msg("brewing.status.unlocks",
                                Placeholder.unparsed("count",
                                        String.valueOf(owned.size()))));
                    }
                }));
        service.rules(self).thenAccept(rules ->
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(messages.msg("brewing.status.rules",
                                Placeholder.unparsed("count",
                                        String.valueOf(rules.size()))))));
    }

    // ------------------------------------------------------------------
    // unlocks
    // ------------------------------------------------------------------

    private void unlocks(Player player) {
        Set<String> owned = cache.unlocksNow(player.getUniqueId());
        if (owned == null) {
            cache.load(player.getUniqueId());
            owned = cache.unlocksNow(player.getUniqueId());
        }
        Set<String> ownedKeys = owned == null ? Set.of() : owned;
        double discount = discountOf(player);
        player.sendMessage(messages.msg("brewing.unlocks.header"));
        for (UnlockDefinition definition : config.unlocks().values()) {
            List<CostItem> price = Costs.reduced(definition.cost(), discount);
            String state = !definition.enabled() ? "disabled"
                    : ownedKeys.contains(definition.key()) ? "owned" : "available";
            player.sendMessage(messages.msg("brewing.unlocks.line",
                    Placeholder.unparsed("key", definition.key()),
                    Placeholder.unparsed("kind", definition.kind().name()
                            .toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("level",
                            String.valueOf(definition.requiredLevel())),
                    Placeholder.component("state",
                            messages.raw("brewing.unlocks.state." + state)),
                    Placeholder.unparsed("cost", Costs.describe(price))));
        }
    }

    // ------------------------------------------------------------------
    // unlock <key> — the purchase driver
    // ------------------------------------------------------------------

    private void unlock(Player player, String[] args) {
        String key = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;
        if (key == null || key.isBlank()) {
            player.sendMessage(messages.msg("brewing.unlock.usage"));
            return;
        }
        UnlockDefinition definition = config.unlocks().get(key);
        if (definition == null) {
            player.sendMessage(messages.msg("brewing.unlock.unknown",
                    Placeholder.unparsed("key", key)));
            return;
        }
        if (!definition.enabled()) {
            player.sendMessage(messages.msg("brewing.unlock.disabled",
                    Placeholder.unparsed("key", key)));
            return;
        }
        List<CostItem> price = Costs.reduced(definition.cost(),
                discountOf(player));
        ItemStack[] contents = player.getInventory().getStorageContents();
        if (!Costs.hasAll(contents, price)) {
            List<String> missing = Costs.missing(contents, price);
            player.sendMessage(messages.msg("brewing.unlock.missing",
                    Placeholder.unparsed("missing", String.join(", ", missing))));
            return;
        }
        String digest = "cost:" + Costs.describe(price);
        service.reserveUnlock(player.getUniqueId(), key, digest)
                .thenAccept(reserved -> Bukkit.getScheduler().runTask(plugin, () ->
                        drivePurchase(player, definition, price, reserved)));
    }

    private void drivePurchase(Player player, UnlockDefinition definition,
            List<CostItem> price,
            AlchemyResult<net.zaminmc.rebellion.alchemy.AlchemyClaim> reserved) {
        if (reserved.outcome() != AlchemyOutcome.RESERVED
                || reserved.payload() == null) {
            renderPurchaseRefusal(player, reserved);
            return;
        }
        var claim = reserved.payload();
        // 26.3 API: removeItem returns slot -> leftover stack.
        HashMap<Integer, ItemStack> leftover =
                player.getInventory().removeItem(removalStacks(price));
        if (!leftover.isEmpty()) {
            // The impossible-in-practice main-thread race did happen:
            // release the claim and return what the removal did take.
            refund(player, price, leftover);
            service.releaseReservation(claim.claimId(), player.getUniqueId(),
                    "inventory changed mid-purchase");
            player.sendMessage(messages.msg("brewing.unlock.removed-mid-purchase"));
            return;
        }
        service.commitUnlock(claim.claimId(), player.getUniqueId())
                .thenAccept(committed -> Bukkit.getScheduler().runTask(plugin, () -> {
                    cache.refreshUnlocks(player.getUniqueId());
                    switch (committed.outcome()) {
                        case UNLOCK_GRANTED -> player.sendMessage(
                                messages.msg(committed.detail() != null
                                        && committed.detail().startsWith("already owned")
                                        ? "brewing.unlock.already-owned"
                                        : "brewing.unlock.ok",
                                        Placeholder.unparsed("key", definition.key())));
                        case COMMIT_NOOP -> player.sendMessage(
                                messages.msg("brewing.unlock.repeated"));
                        default -> renderPurchaseRefusal(player, committed);
                    }
                }));
    }

    private void renderPurchaseRefusal(Player player, AlchemyResult<?> result) {
        switch (result.outcome()) {
            case UNLOCK_UNKNOWN -> player.sendMessage(
                    messages.msg("brewing.unlock.unknown",
                            Placeholder.unparsed("key", result.detail() == null ? "?"
                                    : result.detail())));
            case UNLOCK_DISABLED -> player.sendMessage(
                    messages.msg("brewing.unlock.disabled",
                            Placeholder.unparsed("key", "?")));
            case LEVEL_TOO_LOW -> player.sendMessage(
                    messages.msg("brewing.unlock.level-too-low",
                            Placeholder.unparsed("detail", String.valueOf(
                                    result.detail()))));
            case PREREQUISITE_MISSING -> player.sendMessage(
                    messages.msg("brewing.unlock.prerequisite",
                            Placeholder.unparsed("detail",
                                    String.valueOf(result.detail()))));
            case ALREADY_OWNED -> player.sendMessage(
                    messages.msg("brewing.unlock.owned"));
            case RESERVATION_HELD -> player.sendMessage(
                    messages.msg("brewing.unlock.reservation-held"));
            default -> player.sendMessage(messages.msg("common.unavailable"));
        }
    }

    /** @return the holder's summed efficiency cost-reduction fraction. */
    private double discountOf(Player player) {
        Set<String> owned = cache.unlocksNow(player.getUniqueId());
        if (owned == null) {
            return 0.0;
        }
        double reduction = 0.0;
        for (String key : owned) {
            UnlockDefinition definition = config.unlocks().get(key);
            if (definition != null && definition.kind() == UnlockKind.EFFICIENCY) {
                reduction += definition.costReduction();
            }
        }
        return reduction;
    }

    private ItemStack[] removalStacks(List<CostItem> price) {
        ItemStack[] stacks = new ItemStack[price.size()];
        for (int i = 0; i < price.size(); i++) {
            stacks[i] = new ItemStack(price.get(i).material(),
                    price.get(i).amount());
        }
        return stacks;
    }

    /** Returns what the removal actually took (requested minus leftover). */
    private void refund(Player player, List<CostItem> price,
            Map<Integer, ItemStack> leftover) {
        for (CostItem item : price) {
            int left = 0;
            for (ItemStack stack : leftover.values()) {
                if (stack.getType() == item.material()) {
                    left += stack.getAmount();
                }
            }
            int removed = item.amount() - left;
            if (removed > 0) {
                HashMap<Integer, ItemStack> back = player.getInventory().addItem(
                        new ItemStack(item.material(), removed));
                // Full inventory: the remains drop at the feet (a purchase
                // never eats items through a refund path).
                back.values().forEach(stack -> player.getWorld()
                        .dropItemNaturally(player.getLocation(), stack));
            }
        }
    }

    // ------------------------------------------------------------------
    // rules — list / add / toggle / remove
    // ------------------------------------------------------------------

    private void rules(Player player, String[] args) {
        if (args.length == 1) {
            listRules(player);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> addRule(player, args);
            case "toggle" -> toggleRule(player, args, false);
            case "remove" -> toggleRule(player, args, true);
            default -> player.sendMessage(messages.msg("brewing.rules.usage"));
        }
    }

    private void listRules(Player player) {
        service.rules(player.getUniqueId()).thenAccept(rules ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(messages.msg("brewing.rules.header"));
                    if (rules.isEmpty()) {
                        player.sendMessage(messages.msg("brewing.rules.none"));
                        return;
                    }
                    for (AutoRule rule : rules) {
                        player.sendMessage(messages.msg("brewing.rules.line",
                                Placeholder.unparsed("id", shortId(rule)),
                                Placeholder.unparsed("trigger", rule.trigger().name()
                                        .toLowerCase(Locale.ROOT).replace('_', '-')),
                                Placeholder.unparsed("potion", rule.potionType()
                                        .toLowerCase(Locale.ROOT)),
                                Placeholder.unparsed("threshold", rule.threshold()
                                        == null ? "-" : String.valueOf(rule.threshold())),
                                Placeholder.unparsed("cooldown",
                                        String.valueOf(rule.cooldownSeconds())),
                                Placeholder.unparsed("priority",
                                        String.valueOf(rule.priority())),
                                Placeholder.unparsed("storage", rule.storage().name()
                                        .toLowerCase(Locale.ROOT)),
                                Placeholder.component("state", messages.raw(
                                        rule.enabled() ? "brewing.rules.state.active"
                                                : "brewing.rules.state.paused"))));
                    }
                }));
    }

    private void addRule(Player player, String[] args) {
        // rules add <trigger> <potion> [threshold] <cooldown> <priority> <storage>
        if (args.length < 4) {
            player.sendMessage(messages.msg("brewing.rules.add.usage"));
            return;
        }
        AutoTrigger trigger = AutoTrigger.parse(args[2]);
        if (trigger == null) {
            player.sendMessage(messages.msg("brewing.rules.add.bad-trigger"));
            return;
        }
        PotionType potion = parsePotion(args[3]);
        if (potion == null) {
            player.sendMessage(messages.msg("brewing.rules.add.bad-potion",
                    Placeholder.unparsed("potion", args[3])));
            return;
        }
        Integer threshold = null;
        int index = 4;
        if (trigger.usesThreshold()) {
            if (args.length < index + 4) {
                player.sendMessage(messages.msg("brewing.rules.add.usage"));
                return;
            }
            threshold = parseInt(args[index++]);
            if (threshold == null) {
                player.sendMessage(messages.msg("brewing.rules.add.bad-threshold"));
                return;
            }
        }
        if (args.length != index + 3) {
            player.sendMessage(messages.msg("brewing.rules.add.usage"));
            return;
        }
        Integer cooldown = parseInt(args[index++]);
        Integer priority = parseInt(args[index++]);
        String storageRaw = args[index++];
        var storage = net.zaminmc.rebellion.alchemy.StorageScope.parse(storageRaw);
        if (cooldown == null) {
            player.sendMessage(messages.msg("brewing.rules.add.usage"));
            return;
        }
        if (priority == null) {
            player.sendMessage(messages.msg("brewing.rules.add.usage"));
            return;
        }
        if (storage == null) {
            player.sendMessage(messages.msg("brewing.rules.add.bad-storage",
                    Placeholder.unparsed("storage", storageRaw)));
            return;
        }
        UUID ruleId = UUID.randomUUID();
        service.defineAutoRule(ruleId, player.getUniqueId(), trigger, potion,
                        threshold, cooldown, priority, storage, "command")
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.outcome() == AlchemyOutcome.RULE_DEFINED) {
                        cache.refreshRules(player.getUniqueId());
                    }
                    switch (result.outcome()) {
                        case RULE_DEFINED -> player.sendMessage(
                                messages.msg("brewing.rules.add.ok",
                                        Placeholder.unparsed("id",
                                                shortId(result.payload()))));
                        case TRIGGER_DISABLED -> player.sendMessage(
                                messages.msg("brewing.rules.add.refused.trigger"));
                        case POTION_NOT_ALLOWED -> player.sendMessage(
                                messages.msg("brewing.rules.add.refused.potion"));
                        case THRESHOLD_OUT_OF_BOUNDS -> player.sendMessage(
                                messages.msg("brewing.rules.add.refused.threshold",
                                        Placeholder.unparsed("min", String.valueOf(
                                                config.autoPolicy().healthMin())),
                                        Placeholder.unparsed("max", String.valueOf(
                                                config.autoPolicy().healthMax()))));
                        case COOLDOWN_BELOW_FLOOR -> player.sendMessage(
                                messages.msg("brewing.rules.add.refused.cooldown",
                                        Placeholder.unparsed("min", String.valueOf(
                                                config.autoPolicy()
                                                        .minCooldownSeconds()))));
                        case PRIORITY_OUT_OF_RANGE -> player.sendMessage(
                                messages.msg("brewing.rules.add.refused.priority",
                                        Placeholder.unparsed("min", String.valueOf(
                                                config.autoPolicy().minPriority())),
                                        Placeholder.unparsed("max", String.valueOf(
                                                config.autoPolicy().maxPriority()))));
                        case RULE_CAP_REACHED -> player.sendMessage(
                                messages.msg("brewing.rules.add.refused.cap"));
                        default -> player.sendMessage(
                                messages.msg("common.unavailable"));
                    }
                }));
    }

    private void toggleRule(Player player, String[] args, boolean remove) {
        if (args.length < 3) {
            player.sendMessage(messages.msg("brewing.rules.usage"));
            return;
        }
        String prefix = args[2];
        service.rules(player.getUniqueId()).thenAccept(rules ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    List<AutoRule> matches = new ArrayList<>();
                    for (AutoRule rule : rules) {
                        if (rule.ruleId().toString().startsWith(prefix)) {
                            matches.add(rule);
                        }
                    }
                    if (matches.size() != 1) {
                        player.sendMessage(messages.msg("brewing.rules.unknown-id"));
                        return;
                    }
                    AutoRule target = matches.get(0);
                    if (remove) {
                        service.removeAutoRule(target.ruleId(), player.getUniqueId(),
                                        "command")
                                .thenAccept(result -> Bukkit.getScheduler().runTask(
                                        plugin, () -> {
                                            if (result.outcome()
                                                    == AlchemyOutcome.RULE_REMOVED) {
                                                cache.refreshRules(
                                                        player.getUniqueId());
                                            }
                                            player.sendMessage(
                                                    result.outcome()
                                                            == AlchemyOutcome.RULE_REMOVED
                                                            ? messages.msg(
                                                                    "brewing.rules.remove.ok")
                                                            : messages.msg(
                                                                    "brewing.rules.remove.noop"));
                                        }));
                    } else {
                        boolean next = !target.enabled();
                        service.toggleAutoRule(target.ruleId(), player.getUniqueId(),
                                        next, "command")
                                .thenAccept(result -> Bukkit.getScheduler().runTask(
                                        plugin, () -> {
                                            if (result.outcome()
                                                    == AlchemyOutcome.RULE_ENABLED
                                                    || result.outcome()
                                                            == AlchemyOutcome.RULE_DISABLED) {
                                                cache.refreshRules(
                                                        player.getUniqueId());
                                            }
                                            player.sendMessage(messages.msg(next
                                                    ? "brewing.rules.toggle.resumed"
                                                    : "brewing.rules.toggle.paused",
                                                    Placeholder.unparsed("id",
                                                            shortId(target))));
                                        }));
                    }
                }));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static String shortId(AutoRule rule) {
        return rule.ruleId().toString().substring(0, 8);
    }

    private static @Nullable PotionType parsePotion(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return PotionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
            @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("unlock") && args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return config.unlocks().keySet().stream()
                    .filter(k -> k.startsWith(prefix)).sorted().toList();
        }
        if (args[0].equalsIgnoreCase("rules")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return RULE_SUBS.stream().filter(s -> s.startsWith(prefix)).toList();
            }
            if (args[1].equalsIgnoreCase("add")) {
                if (args.length == 3) {
                    String prefix = args[2].toLowerCase(Locale.ROOT);
                    return java.util.Arrays.stream(AutoTrigger.values())
                            .map(t -> t.name().toLowerCase(Locale.ROOT))
                            .filter(t -> t.startsWith(prefix)).toList();
                }
                if (args.length == 4) {
                    String prefix = args[3].toUpperCase(Locale.ROOT);
                    return config.autoPolicy().allowedPotionTypes().stream()
                            .map(Enum::name).filter(p -> p.startsWith(prefix))
                            .toList();
                }
                if (args.length == 7
                        && AutoTrigger.parse(args[2]) != null
                        && AutoTrigger.parse(args[2]).usesThreshold()) {
                    String prefix = args[6].toUpperCase(Locale.ROOT);
                    return java.util.Arrays.stream(
                                    net.zaminmc.rebellion.alchemy.StorageScope.values())
                            .map(Enum::name).filter(s -> s.startsWith(prefix))
                            .toList();
                }
                if (args.length == 6
                        && (AutoTrigger.parse(args[2]) == null
                            || !AutoTrigger.parse(args[2]).usesThreshold())) {
                    String prefix = args[5].toUpperCase(Locale.ROOT);
                    return java.util.Arrays.stream(
                                    net.zaminmc.rebellion.alchemy.StorageScope.values())
                            .map(Enum::name).filter(s -> s.startsWith(prefix))
                            .toList();
                }
            }
        }
        return List.of();
    }
}
