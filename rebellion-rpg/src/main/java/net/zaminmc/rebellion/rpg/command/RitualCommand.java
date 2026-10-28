package net.zaminmc.rebellion.rpg.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.rpg.ClaimState;
import net.zaminmc.rebellion.rpg.Discovery;
import net.zaminmc.rebellion.rpg.Ingredient;
import net.zaminmc.rebellion.rpg.PlayerEffect;
import net.zaminmc.rebellion.rpg.RitualClaim;
import net.zaminmc.rebellion.rpg.RitualDefinition;
import net.zaminmc.rebellion.rpg.RitualKind;
import net.zaminmc.rebellion.rpg.RpgConfig;
import net.zaminmc.rebellion.rpg.RpgOutcome;
import net.zaminmc.rebellion.rpg.RpgResult;
import net.zaminmc.rebellion.rpg.RpgService;
import net.zaminmc.rebellion.rpg.TrackingSession;
import net.zaminmc.rebellion.rpg.controller.Ingredients;
import net.zaminmc.rebellion.rpg.controller.SoulController;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /ritual} — the casting surface and the claim protocol's driver
 * (master prompt §5.3 / ADR-0016 §2). Every mutation follows the same
 * closed sequence, which is what makes ingredient consumption
 * race-proof:
 *
 * <ol>
 *   <li>verify the reagent set on the main thread (pure
 *       {@link Ingredients} math over storage contents);</li>
 *   <li>reserve the claim (service CAS: rate limit, cooldown, duplicate
 *       reservation) with the definition digest as refund evidence;</li>
 *   <li>remove the reagents through {@code Inventory.removeItem} and
 *       check the leftover map — the mid-cast race detector;</li>
 *   <li>commit (tracking session / mask / knowledge). A refused commit
 *       releases the claim; a mid-cast inventory change releases the
 *       claim and returns what was removed.</li>
 * </ol>
 *
 * <p>Targets resolve to known UUIDs only (online exact match, then the
 * non-blocking cached lookup) — tracking never guesses a name. All
 * entity/position rendering happens on the main thread via the plugin
 * scheduler; database futures never touch Bukkit state directly.</p>
 */
public final class RitualCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("list", "cast", "track",
            "mask", "status", "signal", "release");
    /** Extracts the leading seconds value from typed refusal details. */
    private static final Pattern SECONDS = Pattern.compile("(\\d+)s?");

    private final Plugin plugin;
    private final RpgService service;
    private final RpgConfig config;
    private final MessageCatalog messages;
    private final SoulController souls;

    public RitualCommand(@NotNull Plugin plugin, @NotNull RpgService service,
            @NotNull RpgConfig config, @NotNull MessageCatalog messages,
            @NotNull SoulController souls) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.messages = messages;
        this.souls = souls;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.msg("player-only"));
            return true;
        }
        if (!player.hasPermission("rebellion.rpg.use")) {
            player.sendMessage(messages.msg("common.no-permission"));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(messages.msg("usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(player);
            case "cast" -> cast(player, args);
            case "track" -> track(player, args);
            case "mask" -> mask(player, args);
            case "status" -> status(player);
            case "signal" -> souls.renderSignals(player);
            case "release" -> release(player);
            default -> player.sendMessage(messages.msg("usage"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    private void list(Player player) {
        service.discoveries(player.getUniqueId()).thenAccept(discoveries ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(messages.msg("ritual.list.header"));
                    List<String> known = discoveries.stream()
                            .map(Discovery::key).toList();
                    for (RitualDefinition definition : config.rituals().values()) {
                        String state = !definition.enabled() ? "disabled"
                                : known.contains(knowledgeOf(definition))
                                        ? "known" : "unknown";
                        TagResolver[] resolvers = new TagResolver[]{
                                Placeholder.unparsed("key", definition.key()),
                                Placeholder.unparsed("kind", definition.kind().name()),
                                Placeholder.component("state",
                                        messages.raw("ritual.list.state." + state)),
                                Placeholder.unparsed("ingredients",
                                        describeIngredients(definition.ingredients()))};
                        player.sendMessage(messages.msg("ritual.list.line", resolvers));
                    }
                }));
    }

    /** The discovery key a KNOWLEDGE ritual unlocks ("" for others). */
    private String knowledgeOf(RitualDefinition definition) {
        return definition.knowledgeKey().orElse("");
    }

    private String describeIngredients(List<Ingredient> required) {
        StringBuilder sb = new StringBuilder();
        for (Ingredient ingredient : required) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(ingredient.material().name().toLowerCase(Locale.ROOT))
                    .append(" x").append(ingredient.amount());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // cast / track / mask (the claim protocol driver)
    // ------------------------------------------------------------------

    private void cast(Player player, String[] args) {
        RitualDefinition definition = definition(player, args.length > 1 ? args[1] : null,
                "ritual.cast.unknown", "ritual.cast.usage");
        if (definition == null) {
            return;
        }
        if (definition.kind() != RitualKind.KNOWLEDGE) {
            player.sendMessage(messages.msg("ritual.cast.wrong-kind",
                    Placeholder.unparsed("kind", definition.kind().name())));
            return;
        }
        drive(player, definition, claimId ->
                service.commitKnowledge(claimId, player.getUniqueId())
                        .thenAccept(result -> renderCommit(player, definition, result,
                                null)));
    }

    private void track(Player player, String[] args) {
        RitualDefinition definition = definition(player, args.length > 2 ? args[1] : null,
                "ritual.cast.unknown", "ritual.track.usage");
        if (definition == null) {
            return;
        }
        if (definition.kind() != RitualKind.TRACKING) {
            player.sendMessage(messages.msg("ritual.track.wrong-kind"));
            return;
        }
        // Known UUIDs only: online exact match, then the cached offline
        // lookup (never a blocking web call, never a guess).
        String name = args.length > 2 ? args[2] : "";
        UUID target = resolveKnown(name);
        if (target == null) {
            player.sendMessage(messages.msg("ritual.track.unknown-target"));
            return;
        }
        String targetName = nameOf(target);
        drive(player, definition, claimId ->
                service.commitTracking(claimId, player.getUniqueId(), target)
                        .thenAccept(result -> renderCommit(player, definition, result,
                                targetName)));
    }

    private void mask(Player player, String[] args) {
        RitualDefinition definition = definition(player, args.length > 1 ? args[1] : null,
                "ritual.cast.unknown", "ritual.mask.usage");
        if (definition == null) {
            return;
        }
        if (definition.kind() != RitualKind.COUNTER_MASK) {
            player.sendMessage(messages.msg("ritual.mask.wrong-kind"));
            return;
        }
        drive(player, definition, claimId ->
                service.commitCounterMask(claimId, player.getUniqueId(),
                                player.getUniqueId())
                        .thenAccept(result -> renderCommit(player, definition, result,
                                player.getName())));
    }

    /**
     * The shared reserve -> remove -> commit sequence. The commit step
     * runs through the given driver on the main thread once reagents
     * are gone; every refusal path leaves the claim released and the
     * inventory no worse than before (the digest row is the refund
     * evidence).
     */
    private void drive(Player player, RitualDefinition definition,
            java.util.function.Consumer<UUID> commitStep) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        if (!Ingredients.hasAll(contents, definition.ingredients())) {
            player.sendMessage(messages.msg("ritual.cast.missing",
                    Placeholder.unparsed("missing", missingList(player, definition))));
            return;
        }
        service.reserveRitual(player.getUniqueId(), definition.key(), definition.digest())
                .thenAccept(reserved -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (reserved.outcome() != RpgOutcome.RESERVED
                            || reserved.payload() == null) {
                        renderRefusal(player, definition, reserved);
                        return;
                    }
                    RitualClaim claim = reserved.payload();
                    // 26.3 API: removeItem returns slot -> leftover stack.
                    HashMap<Integer, ItemStack> leftover =
                            player.getInventory().removeItem(
                                    removalStacks(definition.ingredients()));
                    if (!leftover.isEmpty()) {
                        // The impossible-in-practice main-thread race did
                        // happen: release the claim and return what the
                        // removal did take.
                        refund(player, definition, leftover);
                        service.releaseReservation(claim.claimId(), player.getUniqueId(),
                                "inventory changed mid-cast");
                        player.sendMessage(messages.msg("ritual.cast.removed-mid-cast"));
                        return;
                    }
                    commitStep.accept(claim.claimId());
                }));
    }

    private void renderCommit(Player player, RitualDefinition definition,
            RpgResult<?> result, String targetName) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (result.outcome()) {
                case KNOWLEDGE_COMMITTED -> player.sendMessage(
                        messages.msg(result.detail() != null
                                        && result.detail().startsWith("already known")
                                ? "ritual.cast.already-known" : "ritual.cast.ok",
                        Placeholder.unparsed("key", knowledgeOf(definition))));
                case TRACKING_COMMITTED -> player.sendMessage(
                        messages.msg("ritual.track.ok",
                                Placeholder.unparsed("precision",
                                        definition.precision().name()),
                                Placeholder.unparsed("target", targetName == null
                                        ? definition.key() : targetName),
                                Placeholder.unparsed("minutes",
                                        minutes(definition.durationSeconds()))));
                case MASK_GRANTED -> player.sendMessage(
                        messages.msg("ritual.mask.ok",
                                Placeholder.unparsed("minutes",
                                        minutes(definition.durationSeconds()))));
                case RITUAL_FAILED -> player.sendMessage(messages.msg("ritual.track.failed"));
                default -> renderRefusal(player, definition, result);
            }
        });
    }

    /** Typed refusals every commit path shares. */
    private void renderRefusal(Player player, RitualDefinition definition,
            RpgResult<?> result) {
        switch (result.outcome()) {
            case RATE_LIMITED -> player.sendMessage(messages.msg("ritual.refused.rate-limited",
                    Placeholder.unparsed("attempts",
                            String.valueOf(definition.rateLimitAttempts())),
                    Placeholder.unparsed("window",
                            String.valueOf(definition.rateLimitSeconds()))));
            case COOLDOWN_ACTIVE -> player.sendMessage(messages.msg("ritual.refused.cooldown",
                    Placeholder.unparsed("seconds", secondsIn(result.detail()))));
            case RESERVATION_HELD -> player.sendMessage(
                    messages.msg("ritual.refused.reservation-held"));
            case SESSION_CAP_REACHED -> player.sendMessage(
                    messages.msg("ritual.refused.session-cap"));
            default -> player.sendMessage(messages.msg("common.unavailable"));
        }
    }

    // ------------------------------------------------------------------
    // status / release
    // ------------------------------------------------------------------

    private void status(Player player) {
        UUID self = player.getUniqueId();
        player.sendMessage(messages.msg("ritual.status.header"));
        service.activeSessions(self).thenAccept(sessions ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (sessions.isEmpty()) {
                        player.sendMessage(messages.msg("ritual.status.sessions-none"));
                    }
                    for (TrackingSession session : sessions) {
                        player.sendMessage(messages.msg("ritual.status.sessions-line",
                                Placeholder.unparsed("target",
                                        nameOf(session.targetUuid())),
                                Placeholder.unparsed("precision",
                                        session.precision().name()),
                                Placeholder.unparsed("minutes",
                                        minutes((session.expiresUtc()
                                                - System.currentTimeMillis()) / 1000L))));
                    }
                }));
        service.effects(self).thenAccept(effects ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (effects.isEmpty()) {
                        player.sendMessage(messages.msg("ritual.status.effects-none"));
                    }
                    for (PlayerEffect effect : effects) {
                        if (effect.expiresUtc() <= System.currentTimeMillis()) {
                            continue; // forensics rows, not active effects
                        }
                        player.sendMessage(messages.msg("ritual.status.effects-line",
                                Placeholder.unparsed("minutes",
                                        minutes((effect.expiresUtc()
                                                - System.currentTimeMillis()) / 1000L)),
                                Placeholder.unparsed("caster",
                                        String.valueOf(effect.grantedBy()))));
                    }
                }));
        service.claims(self).thenAccept(claims ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean any = false;
                    for (RitualClaim claim : claims) {
                        if (claim.state() == ClaimState.RESERVED) {
                            any = true;
                            player.sendMessage(messages.msg("ritual.status.claims-line",
                                    Placeholder.unparsed("key", claim.ritualKey())));
                        }
                    }
                    if (!any) {
                        player.sendMessage(messages.msg("ritual.status.claims-none"));
                    }
                }));
        service.discoveries(self).thenAccept(discoveries ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (discoveries.isEmpty()) {
                        player.sendMessage(messages.msg("ritual.status.discoveries-none"));
                        return;
                    }
                    player.sendMessage(messages.msg("ritual.status.discoveries-line",
                            Placeholder.unparsed("count",
                                    String.valueOf(discoveries.size())),
                            Placeholder.unparsed("latest", discoveries.get(
                                    discoveries.size() - 1).key())));
                }));
    }

    private void release(Player player) {
        service.claims(player.getUniqueId()).thenAccept(claims ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Optional<RitualClaim> open = claims.stream()
                            .filter(c -> c.state() == ClaimState.RESERVED)
                            .findFirst();
                    if (open.isEmpty()) {
                        player.sendMessage(messages.msg("ritual.release.noop"));
                        return;
                    }
                    service.releaseReservation(open.get().claimId(),
                                    player.getUniqueId(), "manual release")
                            .thenAccept(released -> Bukkit.getScheduler().runTask(
                                    plugin, () -> player.sendMessage(
                                            released.outcome() == RpgOutcome.RELEASED
                                                    ? messages.msg("ritual.release.ok")
                                                    : messages.msg("ritual.release.noop"))));
                }));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Definition lookup with the shared unknown/usage rendering. */
    private RitualDefinition definition(Player player, String key, String unknownKey,
            String usageKey) {
        if (key == null || key.isBlank()) {
            player.sendMessage(messages.msg(usageKey));
            return null;
        }
        RitualDefinition definition = config.rituals().get(key.toLowerCase(Locale.ROOT));
        if (definition == null) {
            player.sendMessage(messages.msg(unknownKey,
                    Placeholder.unparsed("key", key)));
            return null;
        }
        if (!definition.enabled()) {
            player.sendMessage(messages.msg("ritual.cast.disabled",
                    Placeholder.unparsed("key", definition.key())));
            return null;
        }
        return definition;
    }

    /** Online exact match, then the cached offline record; null = unknown. */
    private UUID resolveKnown(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        var cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    /** Best-effort display name for a known UUID (never a web lookup). */
    private String nameOf(UUID uuid) {
        var offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name == null ? uuid.toString() : name;
    }

    private String missingList(Player player, RitualDefinition definition) {
        List<String> missing = new ArrayList<>();
        for (Ingredient ingredient : definition.ingredients()) {
            int held = Ingredients.count(player.getInventory().getStorageContents(),
                    ingredient.material());
            if (held < ingredient.amount()) {
                missing.add(ingredient.material().name().toLowerCase(Locale.ROOT)
                        + " x" + (ingredient.amount() - held));
            }
        }
        return String.join(", ", missing);
    }

    /** One removal stack per reagent, in definition order. */
    private ItemStack[] removalStacks(List<Ingredient> required) {
        ItemStack[] stacks = new ItemStack[required.size()];
        for (int i = 0; i < required.size(); i++) {
            stacks[i] = new ItemStack(required.get(i).material(),
                    required.get(i).amount());
        }
        return stacks;
    }

    /** Returns what the removal actually took (requested minus leftover). */
    private void refund(Player player, RitualDefinition definition,
            Map<Integer, ItemStack> leftover) {
        for (Ingredient ingredient : definition.ingredients()) {
            int left = 0;
            for (ItemStack stack : leftover.values()) {
                if (stack.getType() == ingredient.material()) {
                    left += stack.getAmount();
                }
            }
            int removed = ingredient.amount() - left;
            if (removed > 0) {
                HashMap<Integer, ItemStack> back = player.getInventory().addItem(
                        new ItemStack(ingredient.material(), removed));
                // Full inventory: the remains drop at the feet (a soul
                // ritual never eats reagents through a refund path).
                back.values().forEach(stack -> player.getWorld()
                        .dropItemNaturally(player.getLocation(), stack));
            }
        }
    }

    private String minutes(long seconds) {
        return String.valueOf(Math.max(0, seconds / 60L));
    }

    /** First integer in a service detail string ("cooldown: 3600s remain"). */
    private String secondsIn(String detail) {
        if (detail != null) {
            Matcher matcher = SECONDS.matcher(detail);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "?";
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
        if (args.length == 2 && (args[0].equalsIgnoreCase("cast")
                || args[0].equalsIgnoreCase("track")
                || args[0].equalsIgnoreCase("mask"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return config.rituals().keySet().stream()
                    .filter(k -> k.startsWith(prefix)).sorted().toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("track")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted().toList();
        }
        return List.of();
    }

    /** Message catalog accessor for the admin command's shared rendering. */
    public @NotNull MessageCatalog messages() {
        return messages;
    }

    /** Plugin accessor (admin command schedules render hops). */
    public @NotNull Plugin plugin() {
        return plugin;
    }
}
