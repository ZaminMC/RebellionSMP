package net.zaminmc.rebellion.teams.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.zaminmc.rebellion.teams.TeamOutcome;
import net.zaminmc.rebellion.teams.TeamResult;
import net.zaminmc.rebellion.teams.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /team} — the full team management surface. Every action is
 * permission-gated ({@code rebellion.teams.use}; history additionally
 * {@code rebellion.teams.history}) and rank-gated inside the service's
 * transaction. Targets are resolved from <em>online</em> players only — a
 * client-provided name is never authority; the live Player object's UUID is.
 * Completion happens on persistence threads; Paper's Audience#sendMessage is
 * thread-safe, so responses stream as futures complete.
 */
public final class TeamCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final List<String> SUBS = List.of("create", "invite", "accept", "decline",
            "uninvite", "kick", "leave", "rename", "disband", "promote", "demote", "transfer",
            "friendlyfire", "ally", "enemy", "neutral", "info", "members", "invites", "history");

    private final TeamService service;
    private final Logger logger;

    public TeamCommand(TeamService service, Logger logger) {
        this.service = service;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>Team commands are player-only (use /rebellion modules for admin views).</red>");
            return true;
        }
        if (!player.hasPermission("rebellion.teams.use")) {
            return deny(sender);
        }
        if (args.length == 0) {
            return usage(sender);
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 2) return usage(sender);
                // Rejoin with remaining args so multi-word names reach the service intact.
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                done(player, service.create(player.getUniqueId(), name), CREATE_MESSAGES);
            }
            case "invite" -> {
                Player target = onlineTarget(player, args, 1, "<red>Usage: /team invite <player></red>");
                if (target != null) {
                    done(player, service.invite(player.getUniqueId(), target.getUniqueId()),
                            inviteMessages(target.getName()));
                }
            }
            case "accept" -> done(player,
                    service.accept(player.getUniqueId(), nameOrNull(args, 1)), ACCEPT_MESSAGES);
            case "decline" -> done(player,
                    service.decline(player.getUniqueId(), nameOrNull(args, 1)), ACCEPT_MESSAGES);
            case "uninvite" -> {
                Player target = onlineTarget(player, args, 1, "<red>Usage: /team uninvite <player></red>");
                if (target != null) {
                    done(player, service.uninvite(player.getUniqueId(), target.getUniqueId()),
                            inviteMessages(target.getName()));
                }
            }
            case "kick" -> {
                Player target = onlineTarget(player, args, 1, "<red>Usage: /team kick <player></red>");
                if (target != null) {
                    done(player, service.kick(player.getUniqueId(), target.getUniqueId()),
                            kickMessages(target.getName()));
                }
            }
            case "leave" -> done(player, service.leave(player.getUniqueId()), LEAVE_MESSAGES);
            case "disband" -> done(player, service.disband(player.getUniqueId()), LEAVE_MESSAGES);
            case "rename" -> {
                if (args.length < 2) return usage(sender);
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                done(player, service.rename(player.getUniqueId(), name), RENAME_MESSAGES);
            }
            case "promote" -> {
                Player target = onlineTarget(player, args, 1, "<red>Usage: /team promote <player></red>");
                if (target != null) {
                    done(player, service.promote(player.getUniqueId(), target.getUniqueId()),
                            promoteMessages(target.getName()));
                }
            }
            case "demote" -> {
                Player target = onlineTarget(player, args, 1, "<red>Usage: /team demote <player></red>");
                if (target != null) {
                    done(player, service.demote(player.getUniqueId(), target.getUniqueId()),
                            promoteMessages(target.getName()));
                }
            }
            case "transfer" -> {
                Player target = onlineTarget(player, args, 1, "<red>Usage: /team transfer <player></red>");
                if (target != null) {
                    done(player, service.transfer(player.getUniqueId(), target.getUniqueId()),
                            promoteMessages(target.getName()));
                }
            }
            case "friendlyfire" -> {
                if (args.length < 2 || !(args[1].equalsIgnoreCase("on")
                        || args[1].equalsIgnoreCase("off"))) {
                    send(player, "<red>Usage: /team friendlyfire <on|off></red>");
                    return true;
                }
                done(player, service.setFriendlyFire(player.getUniqueId(),
                        args[1].equalsIgnoreCase("on")), FRIENDLY_FIRE_MESSAGES);
            }
            case "ally", "enemy", "neutral" -> {
                if (args.length < 2) {
                    send(player, "<red>Usage: /team " + args[0].toLowerCase(Locale.ROOT)
                            + " <team-name></red>");
                    return true;
                }
                String relation = args[0].toLowerCase(Locale.ROOT);
                done(player, service.setRelation(player.getUniqueId(), args[1], relation),
                        relationMessages(relation));
            }
            case "info" -> info(player, nameOrNull(args, 1));
            case "members" -> info(player, null);
            case "invites" -> invites(player);
            case "history" -> history(player);
            default -> usage(sender);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Read commands
    // ------------------------------------------------------------------

    private void info(Player player, String teamNameOrNull) {
        CompletableFuture<?> future = teamNameOrNull == null
                ? service.teamInfo(player.getUniqueId())
                : service.teamInfoByName(teamNameOrNull);
        ((CompletableFuture<java.util.Optional<TeamService.TeamView>>) future)
                .whenComplete((view, err) -> {
                    if (err != null) {
                        send(player, "<red>Team data is unavailable right now. Nothing was changed.</red>");
                        return;
                    }
                    if (view.isEmpty()) {
                        send(player, teamNameOrNull == null
                                ? "<gray>You are not in a team.</gray>"
                                : "<red>No team with that name.</red>");
                        return;
                    }
                    renderTeam(player, view.get());
                });
    }

    private void renderTeam(Player player, TeamService.TeamView view) {
        send(player, "<gold>Team " + escape(view.team().displayName()) + "</gold>");
        send(player, "<gray>members " + view.members().size() + ", friendly fire "
                + (view.team().friendlyFire() ? "<red>on</red>" : "<green>off</green>")
                + ", created " + new java.util.Date(view.team().createdAtUtc()) + "</gray>");
        for (var member : view.members()) {
            String name = nameOf(member.player());
            String tag = switch (member.rankKey()) {
                case TeamService.RANK_LEADER -> " <gold>[leader]</gold>";
                case TeamService.RANK_OFFICER -> " <yellow>[officer]</yellow>";
                default -> "";
            };
            send(player, "<gray>-</gray> <white>" + escape(name) + "</white>" + tag);
        }
        if (!view.relations().isEmpty()) {
            StringBuilder relations = new StringBuilder();
            for (var relation : view.relations()) {
                relations.append(relation.relation()).append(" ");
            }
            send(player, "<gray>relations: " + escape(relations.toString().trim()) + "</gray>");
        }
    }

    private void invites(Player player) {
        service.invitesOf(player.getUniqueId()).whenComplete((invites, err) -> {
            if (err != null) {
                send(player, "<red>Invite data is unavailable right now.</red>");
                return;
            }
            if (invites.isEmpty()) {
                send(player, "<gray>You have no pending invites.</gray>");
                return;
            }
            send(player, "<gold>Pending invites</gold>");
            for (var invite : invites) {
                long minutes = Math.max(0, (invite.expiresAtUtc()
                        - System.currentTimeMillis()) / 60_000L);
                send(player, "<gray>-</gray> <white>" + escape(invite.teamName())
                        + "</white> <gray>(expires in " + minutes + "m)</gray>");
            }
        });
    }

    private void history(Player player) {
        if (!player.hasPermission("rebellion.teams.history")) {
            deny(player);
            return;
        }
        service.historyOf(player.getUniqueId(), 30).whenComplete((rows, err) -> {
            if (err != null) {
                send(player, "<red>History is unavailable right now.</red>");
                return;
            }
            if (rows.isEmpty()) {
                send(player, "<gray>No team history to show (you are teamless).</gray>");
                return;
            }
            send(player, "<gold>Team management history (latest 30)</gold>");
            for (var row : rows) {
                send(player, "<gray>" + new java.util.Date(row.recordedAtUtc()) + "</gray> "
                        + "<white>" + escape(row.action()) + "</white> <gray>by "
                        + escape(nameOf(row.actor())) + " " + escape(row.detail()) + "</gray>");
            }
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Player onlineTarget(Player player, String[] args, int index, String usage) {
        if (args.length <= index) {
            send(player, usage);
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[index]);
        if (target == null) {
            send(player, "<red>Player '" + escape(args[index]) + "' is not online "
                    + "(membership actions resolve online players only).</red>");
        }
        return target;
    }

    private static String nameOrNull(String[] args, int index) {
        return args.length > index ? args[index] : null;
    }

    private void done(Player player, CompletableFuture<TeamResult> future,
            Map<TeamOutcome, String> messages) {
        future.whenComplete((result, err) -> {
            if (err != null) {
                send(player, "<red>That action is unavailable right now; nothing was changed. "
                        + "Please try again in a moment.</red>");
                logger.warning(() -> player.getName() + " team action failed: " + err.getMessage());
                return;
            }
            send(player, render(result, messages));
        });
    }

    private static String render(TeamResult result, Map<TeamOutcome, String> messages) {
        String message = messages.getOrDefault(result.outcome(),
                "<red>The action failed with an unexpected result.</red>");
        return message.replace("{detail}", result.detail() == null ? "" : result.detail());
    }

    private static String nameOf(UUID player) {
        var offline = Bukkit.getOfflinePlayer(player);
        String name = offline == null ? null : offline.getName();
        return name == null ? player.toString().substring(0, 8) : name;
    }

    private static boolean usage(CommandSender sender) {
        send(sender, "<red>Usage: /team <"
                + String.join("|", SUBS) + "></red>");
        return true;
    }

    private static boolean deny(CommandSender sender) {
        send(sender, "<red>You do not have permission to do that.</red>");
        return true;
    }

    private static void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }

    /** Neutralizes MiniMessage tag injection from names and config text. */
    private static String escape(String value) {
        return value == null ? "" : value.replace("<", "").replace(">", "");
    }

    // ------------------------------------------------------------------
    // Outcome messages (outcome → MiniMessage; {detail} replaced per result)
    // ------------------------------------------------------------------

    private static final Map<TeamOutcome, String> CREATE_MESSAGES = Map.ofEntries(
            Map.entry(TeamOutcome.OK, "<green>Team created. You are its leader.</green>"),
            Map.entry(TeamOutcome.NAME_INVALID, "<red>Team names are 3-24 chars of letters, digits or underscore, and cannot be a reserved word.</red>"),
            Map.entry(TeamOutcome.NAME_TAKEN, "<red>That team name is already taken.</red>"),
            Map.entry(TeamOutcome.ALREADY_IN_TEAM, "<red>You are already in a team. Leave it first.</red>"),
            Map.entry(TeamOutcome.CREATE_COOLDOWN, "<gray>You can create a new team in {detail}s.</gray>"));

    private static final Map<TeamOutcome, String> ACCEPT_MESSAGES = Map.ofEntries(
            Map.entry(TeamOutcome.OK, "<green>Done.</green>"),
            Map.entry(TeamOutcome.NO_INVITE, "<gray>No pending invite matches{detail}.</gray>".replace("{detail}", " (accept <team-name> when several are pending)")),
            Map.entry(TeamOutcome.INVITE_NOT_FOUND, "<red>That team has no open invite for you.</red>"),
            Map.entry(TeamOutcome.TEAM_FULL, "<red>The team {detail} is full.</red>"),
            Map.entry(TeamOutcome.ALREADY_IN_TEAM, "<red>You are already in a team.</red>"));

    private static final Map<TeamOutcome, String> LEAVE_MESSAGES = Map.ofEntries(
            Map.entry(TeamOutcome.OK, "<green>Done.</green>"),
            Map.entry(TeamOutcome.NOT_IN_TEAM, "<gray>You are not in a team.</gray>"),
            Map.entry(TeamOutcome.NO_LEADER_RIGHTS, "<red>Only the team leader may do that.</red>"));

    private static final Map<TeamOutcome, String> RENAME_MESSAGES = Map.ofEntries(
            Map.entry(TeamOutcome.OK, "<green>Team renamed.</green>"),
            Map.entry(TeamOutcome.NOT_IN_TEAM, "<gray>You are not in a team.</gray>"),
            Map.entry(TeamOutcome.NO_LEADER_RIGHTS, "<red>Only the team leader may rename the team.</red>"),
            Map.entry(TeamOutcome.NAME_INVALID, "<red>Team names are 3-24 chars of letters, digits or underscore, and cannot be a reserved word.</red>"),
            Map.entry(TeamOutcome.NAME_TAKEN, "<red>That team name is already taken.</red>"),
            Map.entry(TeamOutcome.RENAME_COOLDOWN, "<gray>The team can be renamed again in {detail}s.</gray>"));

    private static final Map<TeamOutcome, String> FRIENDLY_FIRE_MESSAGES = Map.ofEntries(
            Map.entry(TeamOutcome.OK, "<green>Friendly fire setting applied.</green>"),
            Map.entry(TeamOutcome.NOT_IN_TEAM, "<gray>You are not in a team.</gray>"),
            Map.entry(TeamOutcome.NO_LEADER_RIGHTS, "<red>Only the team leader may change friendly fire.</red>"));

    private static java.util.function.Function<String, Map<TeamOutcome, String>> INVITE_BASE =
            targetName -> Map.ofEntries(
                    Map.entry(TeamOutcome.OK, "<green>Invite handled for " + targetName + ".</green>"),
                    Map.entry(TeamOutcome.NOT_IN_TEAM, "<gray>You are not in a team.</gray>"),
                    Map.entry(TeamOutcome.NO_OFFICER_RIGHTS, "<red>Only officers and the leader may manage invites.</red>"),
                    Map.entry(TeamOutcome.INVITE_QUEUE_FULL, "<red>" + targetName + " already has the maximum number of pending invites.</red>"),
                    Map.entry(TeamOutcome.INVITE_RATE_LIMITED, "<gray>You can send another invite in {detail}s.</gray>"));

    private static Map<TeamOutcome, String> inviteMessages(String targetName) {
        var base = new java.util.HashMap<TeamOutcome, String>(INVITE_BASE.apply(targetName));
        base.put(TeamOutcome.INVITE_SELF, "<red>You cannot invite yourself.</red>");
        base.put(TeamOutcome.INVITE_TARGET_IN_TEAM, "<red>" + targetName + " is already in a team.</red>");
        base.put(TeamOutcome.INVITE_NOT_FOUND, "<red>No pending invite for " + targetName + " to cancel.</red>");
        return base;
    }

    private static Map<TeamOutcome, String> kickMessages(String targetName) {
        return Map.ofEntries(
                Map.entry(TeamOutcome.OK, "<green>" + targetName + " was removed from the team.</green>"),
                Map.entry(TeamOutcome.NOT_IN_TEAM, "<gray>You are not in a team.</gray>"),
                Map.entry(TeamOutcome.CANT_TARGET_SELF, "<red>Use /team leave instead.</red>"),
                Map.entry(TeamOutcome.TARGET_NOT_MEMBER, "<red>" + targetName + " is not in your team.</red>"),
                Map.entry(TeamOutcome.CANT_TARGET_LEADER, "<red>The team leader cannot be kicked.</red>"),
                Map.entry(TeamOutcome.NO_OFFICER_RIGHTS, "<red>Only officers and the leader may kick members.</red>"),
                Map.entry(TeamOutcome.NO_LEADER_RIGHTS, "<red>Only the leader may kick officers.</red>"));
    }

    private static Map<TeamOutcome, String> promoteMessages(String targetName) {
        return Map.ofEntries(
                Map.entry(TeamOutcome.OK, "<green>Rank updated for " + targetName + ".</green>"),
                Map.entry(TeamOutcome.NOT_IN_TEAM, "<gray>You are not in a team.</gray>"),
                Map.entry(TeamOutcome.NO_LEADER_RIGHTS, "<red>Only the team leader may change ranks.</red>"),
                Map.entry(TeamOutcome.CANT_TARGET_SELF, "<red>You cannot target yourself here.</red>"),
                Map.entry(TeamOutcome.TARGET_NOT_MEMBER, "<red>" + targetName + " is not in your team.</red>"),
                Map.entry(TeamOutcome.CANT_TARGET_LEADER, "<red>The leader's rank changes only via /team transfer.</red>"),
                Map.entry(TeamOutcome.ALREADY_OFFICER, "<gray>" + targetName + " is already an officer.</gray>"),
                Map.entry(TeamOutcome.TARGET_NOT_OFFICER, "<gray>" + targetName + " is not an officer.</gray>"));
    }

    private static Map<TeamOutcome, String> relationMessages(String relation) {
        return Map.ofEntries(
                Map.entry(TeamOutcome.OK, "<green>Diplomacy updated: {detail} is now " + relation + ".</green>"),
                Map.entry(TeamOutcome.NOT_IN_TEAM, "<gray>You are not in a team.</gray>"),
                Map.entry(TeamOutcome.NO_LEADER_RIGHTS, "<red>Only the team leader may set diplomacy.</red>"),
                Map.entry(TeamOutcome.TEAM_NOT_FOUND, "<red>No team with that name.</red>"),
                Map.entry(TeamOutcome.RELATION_OWN_TEAM, "<red>Your team cannot set a relation with itself.</red>"));
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean playerArg = switch (sub) {
            case "invite", "uninvite", "kick", "promote", "demote", "transfer" -> true;
            default -> false;
        };
        if (args.length == 2 && playerArg) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .limit(20)
                    .toList();
        }
        if (args.length == 2 && sub.equals("friendlyfire")) {
            return List.of("on", "off");
        }
        return List.of();
    }
}
