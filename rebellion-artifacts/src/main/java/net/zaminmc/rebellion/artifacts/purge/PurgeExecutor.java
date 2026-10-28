package net.zaminmc.rebellion.artifacts.purge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.zaminmc.rebellion.artifacts.ArtifactOutcome;
import net.zaminmc.rebellion.artifacts.ArtifactResult;
import net.zaminmc.rebellion.artifacts.ArtifactService;
import net.zaminmc.rebellion.artifacts.ArtifactsConfig;
import net.zaminmc.rebellion.artifacts.RebellionEvent;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The gated world-wide purge (ADR-0015 §4, master prompt §5.5). Quadruple
 * runtime gate on top of the default-off config switch: an ACTIVE
 * occurrence must exist, the actor must be its trigger holder, the
 * telegraph (min-delay since trigger) must have elapsed, and the literal
 * {@code confirm} keyword is required. Structural idempotency: the kill
 * loop runs only after the once-per-occurrence
 * {@code purge_executed_utc} CAS commits, so retried commands, restarts
 * mid-warning and racing admins can never double-kill.
 *
 * <p>Target set: ONLINE only (every currently online player). The
 * activating holder is spared unless the configured rules explicitly say
 * otherwise. The kill loop is a plain {@code setHealth(0)} per target —
 * no attribute stacking, no untracked modifiers. Outcome counts are
 * broadcast and the audit row commits with the stamp.</p>
 */
public final class PurgeExecutor {

    private final Plugin plugin;
    private final ArtifactService service;
    private final ArtifactsConfig config;
    private final MessageCatalog messages;

    public PurgeExecutor(@NotNull Plugin plugin, @NotNull ArtifactService service,
            @NotNull ArtifactsConfig config, @NotNull MessageCatalog messages) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.messages = messages;
    }

    /**
     * Pre-flight: why may this actor not purge right now? Null = allowed.
     * Every check is a plain readable refusal — no state is touched.
     */
    public @Nullable String refusalFor(@NotNull Player actor, boolean confirmed) {
        if (!config.purge().enabled()) {
            return "purge.disabled";
        }
        RebellionEvent event = service.activeEvent().orElse(null);
        if (event == null) {
            return "purge.no-event";
        }
        if (!event.triggerHolder().equals(actor.getUniqueId())) {
            return "purge.not-holder";
        }
        long sinceTrigger = System.currentTimeMillis() - event.initiatedAtUtc();
        if (sinceTrigger < config.purge().minDelayMinutes() * 60_000L) {
            return "purge.too-early";
        }
        if (event.purgeExecutedAtUtc() != null) {
            return "purge.already-executed";
        }
        if (!confirmed) {
            return "purge.confirm";
        }
        return null;
    }

    /**
     * Executes the gated purge: broadcast the warning, wait the configured
     * lead, claim the once-per-occurrence stamp, then kill the selected
     * online target set (holder spared per config). The stamp commits
     * BEFORE any player is touched, so a crash mid-kill can never re-run
     * the set.
     */
    public void execute(@NotNull Player actor) {
        RebellionEvent event = service.activeEvent().orElse(null);
        if (event == null) {
            actor.sendMessage(messages.msg("purge.no-event"));
            return;
        }
        UUID eventId = event.eventId();
        int lead = config.purge().warningSeconds();
        broadcast("purge.warning",
                Placeholder.unparsed("holder", actor.getName()),
                Placeholder.unparsed("seconds", String.valueOf(lead)));
        plugin.getLogger().warning("purge armed by " + actor.getName()
                + " for occurrence " + eventId + " (kill loop in " + lead + "s)");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Re-verify the stamp is still unclaimed (an admin/race may have
            // won it during the warning window) — then claim it.
            service.executePurge(eventId, actor.getUniqueId())
                    .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (result.outcome() == ArtifactOutcome.PURGE_ALREADY_EXECUTED) {
                            actor.sendMessage(messages.msg("purge.already-executed"));
                            return;
                        }
                        if (result.outcome() != ArtifactOutcome.PURGE_EXECUTED) {
                            actor.sendMessage(messages.msg("common.unavailable"));
                            return;
                        }
                        int killed = killTargets(actor.getUniqueId());
                        broadcast("purge.executed",
                                Placeholder.unparsed("holder", actor.getName()),
                                Placeholder.unparsed("count", String.valueOf(killed)),
                                Placeholder.unparsed("event", eventId.toString()));
                        plugin.getLogger().warning("purge executed for occurrence "
                                + eventId + ": " + killed + " online players killed");
                    }));
        }, lead * 20L);
    }

    /**
     * The kill loop against the online target set: select ids, then kill
     * the players behind them. The holder is spared unless configured
     * otherwise (master prompt §5.5: never accidentally kill the
     * activating holder).
     */
    private int killTargets(@NotNull UUID triggerHolder) {
        List<UUID> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        int killed = 0;
        for (UUID targetId : selectTargets(triggerHolder, online,
                config.purge().spareHolder())) {
            Player target = Bukkit.getPlayer(targetId);
            if (target == null) {
                continue; // logged off during the warning window: spared by time
            }
            try {
                target.setHealth(0.0);
                killed++;
            } catch (Exception e) {
                plugin.getLogger().warning("purge could not kill " + target.getName()
                        + ": " + e.getMessage());
            }
        }
        return killed;
    }

    /**
     * Target-set selection, pure over UUIDs (serverless-testable): the
     * ONLINE set, holder spared per config. Order is preserved so the
     * outcome counts are deterministic in tests.
     */
    public static @NotNull List<UUID> selectTargets(@NotNull UUID triggerHolder,
            @NotNull List<UUID> online, boolean spareHolder) {
        List<UUID> targets = new ArrayList<>(online.size());
        for (UUID candidate : online) {
            if (spareHolder && candidate.equals(triggerHolder)) {
                continue;
            }
            targets.add(candidate);
        }
        return targets;
    }

    private void broadcast(@NotNull String key,
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(messages.msg(key, resolvers));
        }
    }

    /** Result classification for command-layer messages. */
    public static boolean isExecuted(@NotNull ArtifactResult result) {
        return result.outcome() == ArtifactOutcome.PURGE_EXECUTED;
    }
}
