package net.zaminmc.rebellion.wards.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * mutation result renders through typed outcome messages — the same
 * contracts the teams command layer established.</p>
 */
public final class WardCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final WardService service;
    private final WardsConfig config;
    private final DistanceService distances;
    private final TeamAffiliationService teams;

    public WardCommand(WardService service, WardsConfig config, DistanceService distances,
            TeamAffiliationService teams) {
        this.service = service;
        this.config = config;
        this.distances = distances;
        this.teams = teams;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MINI.deserialize("<red>Ward commands are player-only.</red>"));
            return true;
        }
        if (!player.hasPermission("rebellion.wards.use")) {
            send(player, "<red>You do not have permission to do that.</red>");
            return true;
        }
        if (args.length == 0) {
            return usage(player);
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(player, args);
            case "remove" -> byName(player, args, 1, "Usage: /ward remove <name>",
                    ward -> done(player, service.remove(player.getUniqueId(), ward.id()),
                            REMOVE_MESSAGES));
            case "toggle" -> byName(player, args, 1, "Usage: /ward toggle <name>",
                    ward -> done(player, service.toggle(player.getUniqueId(), ward.id()),
                            toggleMessages(ward)));
            case "info" -> byName(player, args, 1, "Usage: /ward info <name>",
                    ward -> info(player, ward));
            case "list" -> list(player);
            default -> {
                return usage(player);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    private void create(Player player, String[] args) {
        if (args.length < 3) {
            send(player, "<red>Usage: /ward create <name> <radius>"
                    + (config.decoysEnabled() ? " [decoy]" : "") + "</red>");
            return;
        }
        String name = args[1];
        double radius;
        try {
            radius = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            send(player, "<red>Radius must be a number between " + config.minRadius()
                    + " and " + config.maxRadius() + ".</red>");
            return;
        }
        WardType type = WardType.WARD;
        if (args.length >= 4 && "decoy".equalsIgnoreCase(args[3])) {
            type = WardType.DECOY;
        }
        Block target = player.getTargetBlockExact(config.createReachBlocks());
        Material anchorMaterial = Material.matchMaterial(config.anchorMaterial());
        if (target == null || target.getType() != anchorMaterial) {
            send(player, "<red>Target a placed " + config.anchorMaterial()
                    + " block (within " + config.createReachBlocks()
                    + " blocks) to bind the ward.</red>");
            return;
        }
        Optional<UUID> teamId = teams == null ? Optional.empty() : teams.teamOf(player.getUniqueId());
        CompletableFuture<WardResult> future = service.create(player.getUniqueId(), teamId,
                name, target.getWorld().getName(), target.getX(), target.getY(), target.getZ(),
                radius, type);
        done(player, future, CREATE_MESSAGES);
    }

    // ------------------------------------------------------------------
    // list / info
    // ------------------------------------------------------------------

    private void list(Player player) {
        List<Ward> wards = service.ownedBy(player.getUniqueId());
        if (wards.isEmpty()) {
            send(player, "<gray>You have no watch wards. Create one with "
                    + "/ward create &lt;name&gt; &lt;radius&gt; at a placed "
                    + config.anchorMaterial() + ".</gray>");
            return;
        }
        send(player, "<gray>Your watch wards (" + wards.size() + "):</gray>");
        for (Ward ward : wards) {
            send(player, renderWardLine(ward, player));
        }
    }

    private void info(Player player, Ward ward) {
        send(player, "<gray>Watch ward '</gray><aqua>" + escape(ward.name()) + "<gray>'</gray>");
        send(player, "<gray>Type:</gray> " + (ward.type() == WardType.DECOY
                ? "<yellow>decoy</yellow>" : "<white>ward</white>")
                + " <gray>| Status:</gray> " + (ward.active()
                ? "<green>active</green>" : "<red>inactive</red>"));
        send(player, "<gray>Radius:</gray> <white>" + ward.radius() + " blocks</white>"
                + " <gray>| World:</gray> <white>" + escape(ward.world()) + "</white>"
                + " <gray>| Anchor:</gray> <white>" + (int) Math.floor(ward.x()) + ", "
                + (int) Math.floor(ward.y()) + ", " + (int) Math.floor(ward.z()) + "</white>");
        send(player, "<gray>Reported outsiders inside:</gray> <white>"
                + service.reportedInsiders(ward.id()) + "</white>");
        send(player, "<gray>Wards report approaching outsiders only - they do not "
                + "protect this area.</gray>");
    }

    private String renderWardLine(Ward ward, Player player) {
        double distance = net.zaminmc.rebellion.wards.distance.DistanceService
                .horizontalDistance(ward.world(), ward.x(), ward.z(),
                        player.getWorld().getName(), player.getLocation().getX(),
                        player.getLocation().getZ())
                .orElse(-1.0);
        String where = distance < 0
                ? "different world"
                : distances.format(distance, ward.world()) + " away";
        return "<gray>• '</gray><aqua>" + escape(ward.name()) + "<gray>'</gray> "
                + (ward.active() ? "<green>active</green>" : "<red>inactive</red>")
                + (ward.type() == WardType.DECOY ? " <yellow>(decoy)</yellow>" : "")
                + " <gray>- r" + ward.radius() + ", " + where + "</gray>";
    }

    // ------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------

    private void byName(Player player, String[] args, int nameIndex, String usage,
            Consumer<Ward> action) {
        if (args.length <= nameIndex) {
            send(player, "<red>" + usage + "</red>");
            return;
        }
        Optional<Ward> ward = service.byName(player.getUniqueId(), args[nameIndex]);
        if (ward.isEmpty()) {
            send(player, "<gray>No ward of yours or your team is named '"
                    + escape(args[nameIndex]) + "'.</gray>");
            return;
        }
        action.accept(ward.get());
    }

    private void done(Player player, CompletableFuture<WardResult> future,
            Map<WardOutcome, String> messages) {
        future.whenComplete((result, err) -> {
            if (err != null) {
                send(player, "<red>That action is unavailable right now; nothing was changed. "
                        + "Please try again in a moment.</red>");
                return;
            }
            send(player, render(result, messages));
        });
    }

    private static String render(WardResult result, Map<WardOutcome, String> messages) {
        String message = messages.getOrDefault(result.outcome(),
                "<red>The action failed with an unexpected result.</red>");
        String detail = result.detail() != null ? result.detail()
                : result.ward() != null ? result.ward().name() : "";
        return message.replace("{detail}", detail);
    }

    private boolean usage(Player player) {
        send(player, "<gray>Usage: /ward <create|remove|toggle|list|info> — watch wards "
                + "report approaching outsiders; they never protect land.</gray>");
        return true;
    }

    private static void send(Player player, String miniMessage) {
        player.sendMessage(MINI.deserialize(miniMessage));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("<", "").replace(">", "");
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

    // ------------------------------------------------------------------
    // Outcome messages
    // ------------------------------------------------------------------

    private static final Map<WardOutcome, String> CREATE_MESSAGES = Map.ofEntries(
            Map.entry(WardOutcome.OK, "<green>Watch ward '{detail}' created and active.</green>"),
            Map.entry(WardOutcome.NAME_INVALID, "<red>Ward names are 3-24 chars of letters, digits, underscore or hyphen.</red>"),
            Map.entry(WardOutcome.NAME_TAKEN, "<red>You already have a ward with that name.</red>"),
            Map.entry(WardOutcome.RADIUS_INVALID, "<red>Radius must be between {detail} blocks.</red>"),
            Map.entry(WardOutcome.PLAYER_CAP, "<red>You reached your ward cap of {detail}. Remove one first.</red>"),
            Map.entry(WardOutcome.TEAM_CAP, "<red>Your team reached its ward cap of {detail}.</red>"),
            Map.entry(WardOutcome.GLOBAL_CAP, "<red>The server reached its global ward cap of {detail}.</red>"),
            Map.entry(WardOutcome.ANCHOR_TAKEN, "<red>That anchor block already carries a ward.</red>"),
            Map.entry(WardOutcome.DECOYS_DISABLED, "<red>Decoy wards are disabled on this server.</red>"),
            Map.entry(WardOutcome.DB_ERROR, "<red>The action is unavailable right now; nothing was changed.</red>"));

    private static final Map<WardOutcome, String> REMOVE_MESSAGES = Map.ofEntries(
            Map.entry(WardOutcome.OK, "<green>Watch ward '{detail}' removed.</green>"),
            Map.entry(WardOutcome.NOT_FOUND, "<gray>That ward no longer exists.</gray>"),
            Map.entry(WardOutcome.NO_RIGHTS, "<red>Only the ward owner or a team manager can do that.</red>"),
            Map.entry(WardOutcome.DB_ERROR, "<red>The action is unavailable right now; nothing was changed.</red>"));

    private static Map<WardOutcome, String> toggleMessages(Ward ward) {
        // Built before the mutation: the ward's current active flag tells us
        // what the new state will be, so OK can name it.
        String ok = ward.active()
                ? "<green>Watch ward '" + escapeStatic(ward.name()) + "' deactivated.</green>"
                : "<green>Watch ward '" + escapeStatic(ward.name()) + "' reactivated.</green>";
        return Map.ofEntries(
                Map.entry(WardOutcome.OK, ok),
                Map.entry(WardOutcome.NOT_FOUND, "<gray>That ward no longer exists.</gray>"),
                Map.entry(WardOutcome.NO_RIGHTS,
                        "<red>Only the ward owner or a team manager can do that.</red>"),
                Map.entry(WardOutcome.DB_ERROR,
                        "<red>The action is unavailable right now; nothing was changed.</red>"));
    }

    private static String escapeStatic(String value) {
        return value == null ? "" : value.replace("<", "").replace(">", "");
    }
}
