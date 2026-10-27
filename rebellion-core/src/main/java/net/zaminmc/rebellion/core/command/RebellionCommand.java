package net.zaminmc.rebellion.core.command;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.core.CoreState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /rebellion <status|modules|diagnose|reload>} — admin surface.
 *
 * <p>Permission checks run per subcommand (rebellion.admin.*) before any state
 * is touched. Reload is config+messages only; database changes are validated
 * but require a restart, and the command says so.</p>
 */
public final class RebellionCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("status", "modules", "diagnose", "reload");

    private final CoreState state;

    public RebellionCommand(CoreState state) {
        this.state = state;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(state.messages().msg("common.unknown-subcommand"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                if (!sender.hasPermission("rebellion.admin.status")) {
                    sender.sendMessage(state.messages().msg("common.no-permission"));
                    return true;
                }
                sendStatus(sender);
            }
            case "modules" -> {
                if (!sender.hasPermission("rebellion.admin.modules")) {
                    sender.sendMessage(state.messages().msg("common.no-permission"));
                    return true;
                }
                sendModules(sender);
            }
            case "diagnose" -> {
                if (!sender.hasPermission("rebellion.admin.diagnose")) {
                    sender.sendMessage(state.messages().msg("common.no-permission"));
                    return true;
                }
                sendDiagnose(sender);
            }
            case "reload" -> {
                if (!sender.hasPermission("rebellion.admin.reload")) {
                    sender.sendMessage(state.messages().msg("common.no-permission"));
                    return true;
                }
                doReload(sender);
            }
            default -> sender.sendMessage(state.messages().msg("common.unknown-subcommand"));
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        var db = state.database();
        var m = state.messages();
        sender.sendMessage(m.raw("status.header"));
        sender.sendMessage(m.raw("status.version",
                Placeholder.unparsed("version", state.coreVersion()),
                Placeholder.unparsed("api", state.apiVersion())));
        boolean healthy = db != null && db.healthy();
        sender.sendMessage(m.raw("status.backend",
                Placeholder.unparsed("backend", db == null ? "offline" : db.backend().name()),
                Placeholder.component("state", m.raw(healthy ? "status.backend-ok" : "status.backend-degraded"))));
        if (db != null) {
            sender.sendMessage(m.raw("status.pool",
                    Placeholder.unparsed("queued", String.valueOf(db.queuedCount())),
                    Placeholder.unparsed("active", String.valueOf(db.activeCount())),
                    Placeholder.unparsed("completed", String.valueOf(db.completedCount()))));
            Map<String, Integer> versions = db.appliedMigrationVersions();
            sender.sendMessage(m.raw("status.migration",
                    Placeholder.unparsed("applied",
                            String.valueOf(versions.values().stream().mapToInt(Integer::intValue).sum())),
                    Placeholder.unparsed("modules", String.valueOf(versions.size()))));
        }
        Duration uptime = Duration.between(state.startedAt(), java.time.Instant.now());
        sender.sendMessage(m.raw("status.uptime",
                Placeholder.unparsed("uptime", formatDuration(uptime))));
    }

    private void sendModules(CommandSender sender) {
        var m = state.messages();
        var snapshot = state.modules().snapshot();
        sender.sendMessage(m.raw("modules.header"));
        if (snapshot.isEmpty()) {
            sender.sendMessage(m.raw("modules.empty"));
            return;
        }
        List<net.zaminmc.rebellion.api.module.ModuleHealth> sorted = new ArrayList<>(snapshot);
        sorted.sort(java.util.Comparator.comparing(h -> h.descriptor().moduleKey()));
        for (var health : sorted) {
            String stateKey = switch (health.state()) {
                case OPERATIONAL -> "modules.state-operational";
                case DEGRADED -> "modules.state-degraded";
                case FAILED -> "modules.state-failed";
                case PENDING -> "modules.state-pending";
                case DISABLED -> "modules.state-disabled";
            };
            Component stateComponent = m.raw(stateKey);
            String detail = health.detail().isEmpty() ? "" : "— " + health.detail();
            sender.sendMessage(m.raw("modules.line",
                    Placeholder.unparsed("name", health.descriptor().displayName()),
                    Placeholder.unparsed("key", health.descriptor().moduleKey()),
                    Placeholder.component("state", stateComponent),
                    Placeholder.unparsed("detail", detail)));
        }
    }

    private void sendDiagnose(CommandSender sender) {
        var m = state.messages();
        sender.sendMessage(m.msg("diagnose.started"));
        // Report writing is small, local file IO; acceptable on the main thread,
        // and ordering guarantees the reply arrives after the write.
        java.nio.file.Path path = state.diagnostics().writeReport(
                "admin request (" + sender.getName() + ")",
                state.modules().snapshot().stream()
                        .map(h -> h.descriptor().moduleKey() + "=" + h.state()
                                + (h.detail().isEmpty() ? "" : " (" + h.detail() + ")"))
                        .toList(),
                state.configSummary(),
                null);
        if (path == null) {
            String incident = state.diagnostics().incident();
            sender.sendMessage(m.msg("common.internal-error",
                    Placeholder.unparsed("incident", incident)));
            return;
        }
        sender.sendMessage(m.msg("diagnose.written",
                Placeholder.unparsed("path", path.toString()),
                Placeholder.unparsed("incident", path.getFileName().toString())));
    }

    private void doReload(CommandSender sender) {
        var m = state.messages();
        sender.sendMessage(m.msg("reload.started"));
        var result = state.reloadConfigAndMessages();
        if (result.valid()) {
            sender.sendMessage(m.msg("reload.success"));
        } else {
            sender.sendMessage(m.msg("reload.failed"));
            for (var issue : result.issues()) {
                sender.sendMessage(m.raw("reload.issue",
                        Placeholder.unparsed("path", issue.path()),
                        Placeholder.unparsed("message", issue.message())));
            }
        }
        sender.sendMessage(m.msg("reload.database-note"));
    }

    private static String formatDuration(Duration d) {
        long h = d.toHours();
        long min = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) {
            return h + "h " + min + "m";
        }
        if (min > 0) {
            return min + "m " + s + "s";
        }
        return s + "s";
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : SUBS) {
                // Only offer subcommands the sender may execute.
                if (s.startsWith(prefix) && sender.hasPermission("rebellion.admin." + s)) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }
}
