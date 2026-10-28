package net.zaminmc.rebellion.artifacts.command;

import java.util.List;
import java.util.Locale;
import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactKey;
import net.zaminmc.rebellion.artifacts.ArtifactNames;
import net.zaminmc.rebellion.artifacts.ArtifactOutcome;
import net.zaminmc.rebellion.artifacts.ArtifactService;
import net.zaminmc.rebellion.artifacts.db.ArtifactStore.AuditRow;
import net.zaminmc.rebellion.artifacts.RebellionEvent;
import net.zaminmc.rebellion.artifacts.item.ArtifactItems;
import net.zaminmc.rebellion.artifacts.origin.OriginPresentation;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /artifactadmin} — audited administration (the DragonAdminCommand
 * discipline). Subcommands: {@code list}, {@code info <artifact>},
 * {@code route <artifact> <player>} (stuck LOST/RETURNING rows only, fresh
 * instance, target must be online to receive the item),
 * {@code audit <artifact>}, {@code complete confirm} (force-complete the
 * ACTIVE occurrence), {@code events} (occurrence history).
 *
 * <p>An ORIGIN artifact is never routable — the protected realm must be
 * conquered through its encounter, never bypassed by an admin grant
 * (ADR-0013 §3). Every mutation goes through the audited service CAS.</p>
 */
public final class ArtifactAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("list", "info", "route", "audit", "complete", "events");

    private final org.bukkit.plugin.Plugin plugin;
    private final ArtifactService service;
    private final ArtifactItems items;
    private final OriginPresentation presentation;
    private final MessageCatalog messages;
    private final net.zaminmc.rebellion.artifacts.ArtifactsConfig config;

    public ArtifactAdminCommand(@NotNull org.bukkit.plugin.Plugin plugin,
            @NotNull ArtifactService service, @NotNull ArtifactItems items,
            @NotNull OriginPresentation presentation,
            @NotNull net.zaminmc.rebellion.artifacts.ArtifactsConfig config,
            @NotNull MessageCatalog messages) {
        this.plugin = plugin;
        this.service = service;
        this.items = items;
        this.presentation = presentation;
        this.config = config;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!sender.hasPermission("rebellion.artifacts.admin")) {
            sender.sendMessage(messages.msg("common.no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(messages.msg("admin.usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "route" -> route(sender, args);
            case "audit" -> audit(sender, args);
            case "complete" -> complete(sender, args);
            case "events" -> events(sender);
            default -> sender.sendMessage(messages.msg("admin.usage"));
        }
        return true;
    }

    private void list(@NotNull CommandSender sender) {
        sender.sendMessage(messages.msg("admin.list-header"));
        for (Artifact artifact : service.all()) {
            sender.sendMessage(messages.msg("status.line",
                    Placeholder.component("artifact", ArtifactNames.shortName(
                            messages, artifact.artifactKey())),
                    Placeholder.component("state", messages.raw("state."
                            + artifact.state().name().toLowerCase(Locale.ROOT))),
                    Placeholder.unparsed("holder", artifact.holder() == null ? "-"
                            : artifact.holder().toString()),
                    Placeholder.unparsed("since", String.valueOf(
                            artifact.stateSinceUtc()))));
        }
    }

    private void info(@NotNull CommandSender sender, @NotNull String[] args) {
        ArtifactKey key = ArtifactCommand.parseKey(args.length > 1 ? args[1] : null);
        if (key == null) {
            sender.sendMessage(messages.msg("admin.artifact-required"));
            return;
        }
        Artifact artifact = service.byKey(key).orElse(null);
        if (artifact == null) {
            sender.sendMessage(messages.msg("admin.artifact-required"));
            return;
        }
        sender.sendMessage(messages.msg("admin.info",
                Placeholder.component("artifact", ArtifactNames.shortName(messages, key)),
                Placeholder.component("state", messages.raw("state."
                        + artifact.state().name().toLowerCase(Locale.ROOT))),
                Placeholder.unparsed("holder", artifact.holder() == null ? "-"
                        : artifact.holder().toString()),
                Placeholder.unparsed("id", artifact.artifactId().toString()),
                Placeholder.unparsed("instance", artifact.instance().toString()),
                Placeholder.unparsed("origin", artifact.originWorld() + " "
                        + artifact.originX() + "," + artifact.originY() + ","
                        + artifact.originZ())));
    }

    private void route(@NotNull CommandSender sender, @NotNull String[] args) {
        ArtifactKey key = ArtifactCommand.parseKey(args.length > 1 ? args[1] : null);
        if (key == null || args.length < 3) {
            sender.sendMessage(messages.msg("admin.route-usage"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(messages.msg("admin.target-offline",
                    Placeholder.unparsed("player", args[2])));
            return;
        }
        service.adminRoute(key, target.getUniqueId(),
                        sender instanceof Player admin ? admin.getUniqueId() : null)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.outcome() == ArtifactOutcome.ROUTED) {
                        presentation.removePresentation(key);
                        target.getInventory().addItem(items.build(
                                result.artifact(), config, messages));
                        sender.sendMessage(messages.msg("admin.route-ok",
                                Placeholder.component("artifact", ArtifactNames.shortName(
                                        messages, key)),
                                Placeholder.unparsed("player", target.getName())));
                        target.sendMessage(messages.msg("flow.gained",
                                Placeholder.component("artifact", ArtifactNames.shortName(
                                        messages, key))));
                    } else {
                        sender.sendMessage(messages.msg("admin.route-refused",
                                Placeholder.component("artifact", ArtifactNames.shortName(
                                        messages, key)),
                                Placeholder.unparsed("reason",
                                        String.valueOf(result.outcome()))));
                    }
                }));
    }

    private void audit(@NotNull CommandSender sender, @NotNull String[] args) {
        ArtifactKey key = ArtifactCommand.parseKey(args.length > 1 ? args[1] : null);
        if (key == null) {
            sender.sendMessage(messages.msg("admin.artifact-required"));
            return;
        }
        service.auditTrail(key).thenAccept(rows ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(messages.msg("admin.audit-header",
                            Placeholder.component("artifact", ArtifactNames.shortName(
                                    messages, key))));
                    if (rows.isEmpty()) {
                        sender.sendMessage(messages.msg("admin.audit-empty"));
                        return;
                    }
                    for (AuditRow row : rows) {
                        sender.sendMessage(messages.msg("admin.audit-line",
                                Placeholder.unparsed("action", row.action()),
                                Placeholder.unparsed("actor", row.actor() == null ? "-"
                                        : row.actor().toString()),
                                Placeholder.unparsed("detail", row.detail() == null ? "-"
                                        : row.detail()),
                                Placeholder.unparsed("at", java.time.Instant
                                        .ofEpochMilli(row.atUtc()).toString())));
                    }
                }));
    }

    private void complete(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2 || !ArtifactCommand.CONFIRM.equals(
                args[1].toLowerCase(Locale.ROOT))) {
            sender.sendMessage(messages.msg("admin.complete-confirm"));
            return;
        }
        RebellionEvent event = service.activeEvent().orElse(null);
        if (event == null) {
            sender.sendMessage(messages.msg("spend.no-event"));
            return;
        }
        service.completeRebellion(event.eventId(),
                        sender instanceof Player admin ? admin.getUniqueId() : null)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(messages.msg("admin.complete-ok",
                                Placeholder.unparsed("event", event.eventId().toString()),
                                Placeholder.unparsed("outcome",
                                        String.valueOf(result.outcome()))))));
    }

    private void events(@NotNull CommandSender sender) {
        service.eventHistory().thenAccept(history ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(messages.msg("admin.events-header"));
                    if (history.isEmpty()) {
                        sender.sendMessage(messages.msg("admin.audit-empty"));
                        return;
                    }
                    for (RebellionEvent event : history) {
                        sender.sendMessage(messages.msg("admin.events-line",
                                Placeholder.unparsed("event", event.eventId().toString()),
                                Placeholder.parsed("state", event.state().name()),
                                Placeholder.unparsed("holder",
                                        event.triggerHolder().toString()),
                                Placeholder.unparsed("purged",
                                        event.purgeExecutedAtUtc() == null ? "no"
                                                : java.time.Instant.ofEpochMilli(
                                                        event.purgeExecutedAtUtc())
                                                        .toString())));
                    }
                }));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
            @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 1) {
            return SUBS.stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2) {
            String head = args[0].toLowerCase(Locale.ROOT);
            if (head.equals("info") || head.equals("route") || head.equals("audit")) {
                return java.util.Arrays.stream(ArtifactKey.values())
                        .map(k -> k.name().toLowerCase(Locale.ROOT))
                        .filter(n -> n.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (head.equals("complete")) {
                return List.of(ArtifactCommand.CONFIRM);
            }
        }
        return List.of();
    }
}
