package net.zaminmc.rebellion.wards.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardOutcome;
import net.zaminmc.rebellion.wards.WardResult;
import net.zaminmc.rebellion.wards.WardService;
import net.zaminmc.rebellion.wards.WardType;
import net.zaminmc.rebellion.wards.WardsConfig;
import net.zaminmc.rebellion.wards.distance.DistanceService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /ward} — player-facing ward management. Subcommands:
 * <ul>
 *   <li>{@code create <name> <radius> [decoy]} — binds a ward to the
 *       targeted anchor block of the configured material (within reach).</li>
 *   <li>{@code remove <name>} / {@code toggle <name>} — ownership or
 *       configured team rank enforced service-side.</li>
 *   <li>{@code list} — own + team wards with live status.</li>
 *   <li>{@code info <name>} — one ward's details including reported
 *       insider count.</li>
 * </ul>
 *
 * <p>Authority rules: the anchor comes from the live raytrace (never client
 * coordinates), targets resolve through owned-ward name lookup, and every
 * mutation result renders through typed outcome messages from the module's
 * messages.yml catalog (ADR-0012) — {@code ward.<action>.<outcome>} keys;
 * names and details arrive as placeholders, never as raw MiniMessage.</p>
 */
public final class WardCommand implements CommandExecutor, TabCompleter {

    private final WardService service;
    private final WardsConfig config;
    private final DistanceService distances;
    private final TeamAffiliationService teams;
    private final MessageCatalog messages;

    public WardCommand(WardService service, WardsConfig config, DistanceService distances,
            TeamAffiliationService teams, MessageCatalog messages) {
        this.service = service;
        this.config = config;
        this.distances = distances;
        this.teams = teams;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.msg("player-only"));
            return true;
        }
        if (!player.hasPermission("rebellion.wards.use")) {
            player.sendMessage(messages.msg("common.no-permission"));
            return true;
        }
        if (args.length == 0) {
            return usage(player);
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(player, args);
            case "remove" -> byName(player, args, 1, "ward.remove.usage",
                    ward -> done(player, "remove",
                            service.remove(player.getUniqueId(), ward.id()),
                            "ward.remove.ok", wardResolvers(ward)));
            case "toggle" -> byName(player, args, 1, "ward.toggle.usage",
                    // OK has two texts; the ward's pre-mutation state decides which.
                    ward -> done(player, "toggle",
                            service.toggle(player.getUniqueId(), ward.id()),
                            ward.active()
                                    ? "ward.toggle.deactivated"
                                    : "ward.toggle.reactivated",
                            wardResolvers(ward)));
            case "info" -> byName(player, args, 1, "info.usage",
                    ward -> info(player, ward));
            case "list" -> list(player);
            default -> {
                return usage(player);
            }
        }
        return true;
    }

    private TagResolver[] wardResolvers(Ward ward) {
        return new TagResolver[]{
                Placeholder.unparsed("ward", ward.name()),
                Placeholder.unparsed("detail",
                        ward.name() == null ? "" : ward.name())};
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    private void create(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(messages.msg(config.decoysEnabled()
                    ? "ward.create.usage-decoy" : "ward.create.usage"));
            return;
        }
        String name = args[1];
        double radius;
        try {
            radius = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(messages.msg("ward.create.radius-not-a-number",
                    Placeholder.unparsed("min", String.valueOf(config.minRadius())),
                    Placeholder.unparsed("max", String.valueOf(config.maxRadius()))));
            return;
        }
        WardType type = WardType.WARD;
        if (args.length >= 4 && "decoy".equalsIgnoreCase(args[3])) {
            type = WardType.DECOY;
        }
        Block target = player.getTargetBlockExact(config.createReachBlocks());
        Material anchorMaterial = Material.matchMaterial(config.anchorMaterial());
        if (target == null || target.getType() != anchorMaterial) {
            player.sendMessage(messages.msg("ward.create.no-anchor",
                    Placeholder.unparsed("material", config.anchorMaterial()),
                    Placeholder.unparsed("reach", String.valueOf(config.createReachBlocks()))));
            return;
        }
        Optional<UUID> teamId = teams == null ? Optional.empty() : teams.teamOf(player.getUniqueId());
        CompletableFuture<WardResult> future = service.create(player.getUniqueId(), teamId,
                name, target.getWorld().getName(), target.getX(), target.getY(), target.getZ(),
                radius, type);
        done(player, "create", future, "ward.create.ok");
    }

    // ------------------------------------------------------------------
    // list / info
    // ------------------------------------------------------------------

    private void list(Player player) {
        List<Ward> wards = service.ownedBy(player.getUniqueId());
        if (wards.isEmpty()) {
            player.sendMessage(messages.msg("list.empty",
                    Placeholder.unparsed("material", config.anchorMaterial())));
            return;
        }
        player.sendMessage(messages.raw("list.header",
                Placeholder.unparsed("count", String.valueOf(wards.size()))));
        for (Ward ward : wards) {
            player.sendMessage(renderWardLine(ward, player));
        }
    }

    private void info(Player player, Ward ward) {
        player.sendMessage(messages.raw("info.header",
                Placeholder.unparsed("ward", ward.name())));
        player.sendMessage(messages.raw("info.type-line",
                Placeholder.component("type", messages.raw(
                        ward.type() == WardType.DECOY ? "info.type-decoy" : "info.type-ward")),
                Placeholder.component("status", messages.raw(
                        ward.active() ? "state.active" : "state.inactive"))));
        player.sendMessage(messages.raw("info.geometry",
                Placeholder.unparsed("radius", String.valueOf(ward.radius())),
                Placeholder.unparsed("world", ward.world()),
                Placeholder.unparsed("x", String.valueOf((int) Math.floor(ward.x()))),
                Placeholder.unparsed("y", String.valueOf((int) Math.floor(ward.y()))),
                Placeholder.unparsed("z", String.valueOf((int) Math.floor(ward.z())))));
        player.sendMessage(messages.raw("info.insiders",
                Placeholder.unparsed("count", String.valueOf(service.reportedInsiders(ward.id())))));
        player.sendMessage(messages.raw("info.no-protection"));
    }

    private Component renderWardLine(Ward ward, Player player) {
        double distance = DistanceService.horizontalDistance(ward.world(), ward.x(), ward.z(),
                player.getWorld().getName(), player.getLocation().getX(),
                player.getLocation().getZ())
                .orElse(-1.0);
        Component where = distance < 0
                ? messages.raw("list.different-world")
                : Component.text(distances.format(distance, ward.world()) + " away");
        return messages.raw("list.line",
                Placeholder.unparsed("ward", ward.name()),
                Placeholder.component("state", messages.raw(
                        ward.active() ? "state.active" : "state.inactive")),
                Placeholder.component("decoy", ward.type() == WardType.DECOY
                        ? messages.raw("state.decoy-tag") : Component.empty()),
                Placeholder.unparsed("radius", String.valueOf(ward.radius())),
                Placeholder.component("where", where));
    }

    // ------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------

    private void byName(Player player, String[] args, int nameIndex, String usageKey,
            Consumer<Ward> action) {
        if (args.length <= nameIndex) {
            player.sendMessage(messages.msg(usageKey));
            return;
        }
        Optional<Ward> ward = service.byName(player.getUniqueId(), args[nameIndex]);
        if (ward.isEmpty()) {
            player.sendMessage(messages.msg("no-such-ward",
                    Placeholder.unparsed("name", args[nameIndex])));
            return;
        }
        action.accept(ward.get());
    }

    /**
     * Renders one typed result: {@code okKey} for the OK outcome (toggle has
     * two), {@code ward.<action>.<outcome>} for everything else. Detail and
     * ward name arrive as placeholders, never as raw MiniMessage.
     */
    private void done(Player player, String action, CompletableFuture<WardResult> future,
            String okKey, TagResolver... extraResolvers) {
        future.whenComplete((result, err) -> {
            if (err != null) {
                player.sendMessage(messages.msg("common.unavailable"));
                return;
            }
            String key = result.outcome() == WardOutcome.OK
                    ? okKey : outcomeKey(action, result.outcome());
            String detail = result.detail() != null ? result.detail()
                    : result.ward() != null ? result.ward().name() : "";
            player.sendMessage(messages.msg(key, joined(extraResolvers,
                    Placeholder.unparsed("detail", detail))));
        });
    }

    private static TagResolver[] joined(TagResolver[] extra, TagResolver detail) {
        TagResolver[] all = new TagResolver[extra.length + 1];
        System.arraycopy(extra, 0, all, 0, extra.length);
        all[extra.length] = detail;
        return all;
    }

    /**
     * Outcome → catalog key: {@code ward.<action>.<outcome>} with the enum
     * name lowercased and underscores as dashes — deterministic (ADR-0012).
     * Infrastructure failure (DB_ERROR) fails closed with the shared
     * unavailable message.
     */
    public static String outcomeKey(String action, WardOutcome outcome) {
        return switch (outcome) {
            case DB_ERROR -> "common.unavailable";
            default -> "ward." + action + "."
                    + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-');
        };
    }

    private boolean usage(Player player) {
        player.sendMessage(messages.msg("usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("create", "remove", "toggle", "list", "info")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            if (sender instanceof Player player) {
                service.ownedBy(player.getUniqueId()).forEach(ward ->
                        completions.add(ward.name()));
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("create")
                && config.decoysEnabled()) {
            completions.add("decoy");
        }
        return completions;
    }
}
