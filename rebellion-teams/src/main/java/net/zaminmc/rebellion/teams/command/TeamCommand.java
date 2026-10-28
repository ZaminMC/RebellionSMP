package net.zaminmc.rebellion.teams.command;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
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
 *
 * <p>All player feedback renders through the module's messages.yml catalog
 * (ADR-0012). Outcome messages live under {@code team.<action>.<outcome>};
 * names and details are inserted as MiniMessage placeholders, so they can
 * never inject tags.</p>
 */
public final class TeamCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("create", "invite", "accept", "decline",
            "uninvite", "kick", "leave", "rename", "disband", "promote", "demote", "transfer",
            "friendlyfire", "ally", "enemy", "neutral", "info", "members", "invites", "history");

    private final TeamService service;
    private final MessageCatalog messages;
    private final Logger logger;

    public TeamCommand(TeamService service, MessageCatalog messages, Logger logger) {
        this.service = service;
        this.messages = messages;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.msg("player-only"));
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
                done(player, "create", service.create(player.getUniqueId(), name));
            }
            case "invite" -> {
                Player target = onlineTarget(player, args, 1, "team.invite.usage");
                if (target != null) {
                    done(player, "invite", service.invite(player.getUniqueId(), target.getUniqueId()),
                            Placeholder.unparsed("player", target.getName()));
                }
            }
            case "accept" -> done(player, "accept",
                    service.accept(player.getUniqueId(), nameOrNull(args, 1)));
            case "decline" -> done(player, "accept",
                    service.decline(player.getUniqueId(), nameOrNull(args, 1)));
            case "uninvite" -> {
                Player target = onlineTarget(player, args, 1, "team.uninvite.usage");
                if (target != null) {
                    done(player, "uninvite",
                            service.uninvite(player.getUniqueId(), target.getUniqueId()),
                            Placeholder.unparsed("player", target.getName()));
                }
            }
            case "kick" -> {
                Player target = onlineTarget(player, args, 1, "team.kick.usage");
                if (target != null) {
                    done(player, "kick", service.kick(player.getUniqueId(), target.getUniqueId()),
                            Placeholder.unparsed("player", target.getName()));
                }
            }
            case "leave" -> done(player, "leave", service.leave(player.getUniqueId()));
            case "disband" -> done(player, "leave", service.disband(player.getUniqueId()));
            case "rename" -> {
                if (args.length < 2) return usage(sender);
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                done(player, "rename", service.rename(player.getUniqueId(), name));
            }
            case "promote" -> {
                Player target = onlineTarget(player, args, 1, "team.rank.promote-usage");
                if (target != null) {
                    done(player, "rank", service.promote(player.getUniqueId(), target.getUniqueId()),
                            Placeholder.unparsed("player", target.getName()));
                }
            }
            case "demote" -> {
                Player target = onlineTarget(player, args, 1, "team.rank.demote-usage");
                if (target != null) {
                    done(player, "rank", service.demote(player.getUniqueId(), target.getUniqueId()),
                            Placeholder.unparsed("player", target.getName()));
                }
            }
            case "transfer" -> {
                Player target = onlineTarget(player, args, 1, "team.rank.transfer-usage");
                if (target != null) {
                    done(player, "rank", service.transfer(player.getUniqueId(), target.getUniqueId()),
                            Placeholder.unparsed("player", target.getName()));
                }
            }
            case "friendlyfire" -> {
                if (args.length < 2 || !(args[1].equalsIgnoreCase("on")
                        || args[1].equalsIgnoreCase("off"))) {
                    player.sendMessage(messages.msg("team.friendlyfire.usage"));
                    return true;
                }
                done(player, "friendlyfire", service.setFriendlyFire(player.getUniqueId(),
                        args[1].equalsIgnoreCase("on")));
            }
            case "ally", "enemy", "neutral" -> {
                String relation = args[0].toLowerCase(Locale.ROOT);
                if (args.length < 2) {
                    player.sendMessage(messages.msg("team.relation.usage",
                            Placeholder.unparsed("relation", relation)));
                    return true;
                }
                done(player, "relation",
                        service.setRelation(player.getUniqueId(), args[1], relation),
                        Placeholder.unparsed("relation", relation));
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
                        player.sendMessage(messages.msg("info.unavailable"));
                        return;
                    }
                    if (view.isEmpty()) {
                        player.sendMessage(messages.msg(teamNameOrNull == null
                                ? "not-in-team" : "no-team-named"));
                        return;
                    }
                    renderTeam(player, view.get());
                });
    }

    private void renderTeam(Player player, TeamService.TeamView view) {
        player.sendMessage(messages.raw("info.header",
                Placeholder.unparsed("name", view.team().displayName())));
        player.sendMessage(messages.raw("info.summary",
                Placeholder.unparsed("count", String.valueOf(view.members().size())),
                Placeholder.component("fire", messages.raw(
                        view.team().friendlyFire() ? "info.fire-on" : "info.fire-off")),
                Placeholder.unparsed("created", new java.util.Date(view.team().createdAtUtc()).toString())));
        for (var member : view.members()) {
            String name = nameOf(member.player());
            String rankKey = switch (member.rankKey()) {
                case TeamService.RANK_LEADER -> "info.rank-leader";
                case TeamService.RANK_OFFICER -> "info.rank-officer";
                default -> null;
            };
            player.sendMessage(messages.raw("info.member-line",
                    Placeholder.unparsed("name", name),
                    Placeholder.component("rank", rankKey == null
                            ? net.kyori.adventure.text.Component.empty()
                            : messages.raw(rankKey))));
        }
        if (!view.relations().isEmpty()) {
            StringBuilder relations = new StringBuilder();
            for (var relation : view.relations()) {
                relations.append(relation.relation()).append(" ");
            }
            player.sendMessage(messages.raw("info.relations-line",
                    Placeholder.unparsed("relations", relations.toString().trim())));
        }
    }

    private void invites(Player player) {
        service.invitesOf(player.getUniqueId()).whenComplete((invites, err) -> {
            if (err != null) {
                player.sendMessage(messages.msg("invites.unavailable"));
                return;
            }
            if (invites.isEmpty()) {
                player.sendMessage(messages.msg("invites.none"));
                return;
            }
            player.sendMessage(messages.raw("invites.header"));
            for (var invite : invites) {
                long minutes = Math.max(0, (invite.expiresAtUtc()
                        - System.currentTimeMillis()) / 60_000L);
                player.sendMessage(messages.raw("invites.line",
                        Placeholder.unparsed("team", invite.teamName()),
                        Placeholder.unparsed("minutes", String.valueOf(minutes))));
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
                player.sendMessage(messages.msg("history.unavailable"));
                return;
            }
            if (rows.isEmpty()) {
                player.sendMessage(messages.msg("history.none"));
                return;
            }
            player.sendMessage(messages.raw("history.header"));
            for (var row : rows) {
                player.sendMessage(messages.raw("history.line",
                        Placeholder.unparsed("date", new java.util.Date(row.recordedAtUtc()).toString()),
                        Placeholder.unparsed("action", row.action()),
                        Placeholder.unparsed("actor", nameOf(row.actor())),
                        Placeholder.unparsed("detail", row.detail() == null ? "" : row.detail())));
            }
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Player onlineTarget(Player player, String[] args, int index, String usageKey) {
        if (args.length <= index) {
            player.sendMessage(messages.msg(usageKey));
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[index]);
        if (target == null) {
            player.sendMessage(messages.msg("target-not-online",
                    Placeholder.unparsed("player", args[index])));
        }
        return target;
    }

    private static String nameOrNull(String[] args, int index) {
        return args.length > index ? args[index] : null;
    }

    private void done(Player player, String action, CompletableFuture<TeamResult> future,
            TagResolver... extraResolvers) {
        future.whenComplete((result, err) -> {
            if (err != null) {
                player.sendMessage(messages.msg("common.unavailable"));
                logger.warning(() -> player.getName() + " team action failed: " + err.getMessage());
                return;
            }
            player.sendMessage(messages.msg(outcomeKey(action, result.outcome()),
                    joined(extraResolvers, Placeholder.unparsed("detail",
                            result.detail() == null ? "" : result.detail()))));
        });
    }

    private static TagResolver[] joined(TagResolver[] extra, TagResolver detail) {
        TagResolver[] all = new TagResolver[extra.length + 1];
        System.arraycopy(extra, 0, all, 0, extra.length);
        all[extra.length] = detail;
        return all;
    }

    /**
     * Outcome → catalog key: {@code team.<action>.<outcome>} with the enum
     * name lowercased and underscores as dashes — deterministic, so an admin
     * who sees a message can find its key and vice versa. Only texts that are
     * identical across all actions share a top-level key; infrastructure
     * outcomes (DB_FAILURE, OVERLOADED) fail closed with the shared
     * unavailable message.
     */
    static String outcomeKey(String action, TeamOutcome outcome) {
        return switch (outcome) {
            case NOT_IN_TEAM -> "not-in-team";
            case NAME_INVALID -> "name-invalid";
            case NAME_TAKEN -> "name-taken";
            case DB_FAILURE, OVERLOADED -> "common.unavailable";
            default -> "team." + action + "."
                    + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-');
        };
    }

    private static String nameOf(UUID player) {
        var offline = Bukkit.getOfflinePlayer(player);
        String name = offline == null ? null : offline.getName();
        return name == null ? player.toString().substring(0, 8) : name;
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(messages.msg("usage"));
        return true;
    }

    private boolean deny(CommandSender sender) {
        sender.sendMessage(messages.msg("common.no-permission"));
        return true;
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
