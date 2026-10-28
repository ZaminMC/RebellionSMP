package net.zaminmc.rebellion.artifacts.event;

import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactOutcome;
import net.zaminmc.rebellion.artifacts.ArtifactResult;
import net.zaminmc.rebellion.artifacts.ArtifactService;
import net.zaminmc.rebellion.artifacts.ArtifactState;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The Rebellion qualification probe (ADR-0015 §3): after any transition
 * that lands an artifact HELD, read the mirror — all three held by one
 * player means {@code triggerRebellion}. The observer is deliberately
 * dumb: no dedup, no locking, no inventory scanning, because the
 * trigger's own transaction re-verifies qualification and the singleton
 * guard CAS collapses duplicate probes into
 * {@code EVENT_ALREADY_ACTIVE} carrying the one occurrence (master
 * prompt §5.5: "trigger once" is structural, not event-layer code).
 *
 * <p>On a real trigger the catastrophe surface of this slice broadcasts:
 * a global warning to every online player and a join-time notice while
 * the occurrence stays ACTIVE (handled by the sweep + join listener in
 * the plugin wiring).</p>
 */
public final class RebellionObserver {

    private final Plugin plugin;
    private final ArtifactService service;
    private final MessageCatalog messages;

    public RebellionObserver(@NotNull Plugin plugin, @NotNull ArtifactService service,
            @NotNull MessageCatalog messages) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
    }

    /**
     * Probes whether {@code player} now controls all three artifacts and,
     * if so, fires the (idempotent) Rebellion trigger.
     */
    public void checkPossession(@NotNull Player player) {
        if (!holdsAllThree(player.getUniqueId())) {
            return;
        }
        service.triggerRebellion(player.getUniqueId(),
                        "full control of all three artifacts")
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.outcome() == ArtifactOutcome.EVENT_TRIGGERED
                            || result.outcome() == ArtifactOutcome.EVENT_ALREADY_ACTIVE) {
                        broadcastTrigger(result, player);
                    }
                }));
    }

    /** Mirror read: all three rows HELD by the same player. */
    public boolean holdsAllThree(@NotNull java.util.UUID holder) {
        int held = 0;
        for (Artifact artifact : service.all()) {
            if (artifact.state() == ArtifactState.HELD
                    && holder.equals(artifact.holder())) {
                held++;
            }
        }
        return held == net.zaminmc.rebellion.artifacts.ArtifactsPolicy.ARTIFACT_COUNT;
    }

    private void broadcastTrigger(@NotNull ArtifactResult result,
            @NotNull Player player) {
        var holder = Placeholder.unparsed("holder", player.getName());
        var event = result.event();
        var eventId = Placeholder.unparsed("event",
                event == null ? "?" : event.eventId().toString());
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(messages.msg("event.triggered", holder, eventId));
        }
        plugin.getLogger().info("Rebellion event "
                + (event == null ? "?" : event.eventId()) + " active; holder "
                + player.getName() + " (outcome " + result.outcome() + ")");
    }

    /** Join-time notice while an occurrence is ACTIVE (wired by the plugin). */
    public void notifyIfActive(@NotNull Player joining) {
        service.activeEvent().ifPresent(event ->
                joining.sendMessage(messages.msg("event.join-notice",
                        Placeholder.unparsed("holder", event.triggerHolder().toString()),
                        Placeholder.unparsed("event", event.eventId().toString()))));
    }
}
