package net.zaminmc.rebellion.artifacts.sweep;

import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactOutcome;
import net.zaminmc.rebellion.artifacts.ArtifactService;
import net.zaminmc.rebellion.artifacts.ArtifactState;
import net.zaminmc.rebellion.artifacts.ArtifactsConfig;
import net.zaminmc.rebellion.artifacts.event.RebellionObserver;
import net.zaminmc.rebellion.artifacts.origin.OriginPresentation;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The maintenance sweep (ADR-0015): one periodic task driving every
 * time-based artifact behavior — each step individually idempotent, so a
 * sweep that dies at any cadence cannot double-apply anything.
 *
 * <ol>
 *   <li><strong>Return pipeline</strong> — LOST/SPENT rows whose return
 *       delay has elapsed enter RETURNING ({@code beginReturn}, refused
 *       while an event is active); RETURNING rows arrive home
 *       ({@code completeReturn}) with a fresh discoverable instance.</li>
 *   <li><strong>Origin reconcile</strong> — exactly one presentation pair
 *       per ORIGIN row (none otherwise), loaded chunks only.</li>
 *   <li><strong>Event duration</strong> — an ACTIVE occurrence older than
 *       the configured duration completes ({@code completeRebellion});
 *       the guard clears and the world can reconquer.</li>
 *   <li><strong>Reminder</strong> — while an occurrence is ACTIVE, every
 *       configured interval a reminder goes to online players.</li>
 * </ol>
 */
public final class ArtifactsSweep {

    private final Plugin plugin;
    private final ArtifactService service;
    private final ArtifactsConfig config;
    private final OriginPresentation presentation;
    private final RebellionObserver observer;
    private final MessageCatalog messages;
    private long lastReminderAtUtc = 0L;

    public ArtifactsSweep(@NotNull Plugin plugin, @NotNull ArtifactService service,
            @NotNull ArtifactsConfig config, @NotNull OriginPresentation presentation,
            @NotNull RebellionObserver observer, @NotNull MessageCatalog messages) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.presentation = presentation;
        this.observer = observer;
        this.messages = messages;
    }

    /** One sweep pass (main thread; DB work hops through the service). */
    public void run() {
        try {
            sweepReturns();
            presentation.reconcile();
            sweepEvent();
        } catch (Exception e) {
            plugin.getLogger().warning("artifacts sweep failed: " + e.getMessage());
        }
    }

    private void sweepReturns() {
        long now = System.currentTimeMillis();
        for (Artifact artifact : service.all()) {
            switch (artifact.state()) {
                case LOST, SPENT -> {
                    if (now - artifact.stateSinceUtc() >= config.returnDelayMs()) {
                        service.beginReturn(artifact.artifactKey())
                                .thenAccept(result -> {
                                    if (result.outcome()
                                            == ArtifactOutcome.RETURN_QUEUED) {
                                        plugin.getLogger().info("artifact "
                                                + artifact.artifactKey()
                                                + " is returning home");
                                    }
                                });
                    }
                }
                case RETURNING -> service.completeReturn(artifact.artifactKey())
                        .thenAccept(result -> {
                            if (result.outcome() == ArtifactOutcome.RETURNED) {
                                plugin.getLogger().info("artifact "
                                        + artifact.artifactKey()
                                        + " arrived at its protected origin");
                            }
                        });
                default -> {
                }
            }
        }
    }

    private void sweepEvent() {
        long now = System.currentTimeMillis();
        service.activeEvent().ifPresent(event -> {
            long elapsed = now - event.initiatedAtUtc();
            if (elapsed >= config.eventDurationMinutes() * 60_000L) {
                service.completeRebellion(event.eventId(), null)
                        .thenAccept(result -> {
                            if (result.outcome()
                                    == ArtifactOutcome.EVENT_COMPLETED) {
                                broadcast("event.completed",
                                        Placeholder.unparsed("event",
                                                event.eventId().toString()));
                                plugin.getLogger().info("rebellion occurrence "
                                        + event.eventId() + " completed (duration)");
                            }
                        });
                return;
            }
            long interval = config.eventReminderMinutes() * 60_000L;
            if (interval > 0 && now - lastReminderAtUtc >= interval) {
                lastReminderAtUtc = now;
                broadcast("event.reminder",
                        Placeholder.unparsed("event", event.eventId().toString()),
                        Placeholder.parsed("remaining", remaining(elapsed)));
            }
        });
    }

    private @NotNull String remaining(long elapsed) {
        long totalMs = config.eventDurationMinutes() * 60_000L;
        long minutes = Math.max(0, (totalMs - elapsed) / 60_000L);
        return minutes + "m";
    }

    private void broadcast(@NotNull String key,
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(messages.msg(key, resolvers));
        }
    }
}
