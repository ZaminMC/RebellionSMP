package net.zaminmc.rebellion.wards.alert;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardsConfig;
import net.zaminmc.rebellion.wards.detect.AlertDecision;
import net.zaminmc.rebellion.wards.distance.DistanceService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Renders alert decisions and routes them to the configured channel
 * ({@code alerts.channel}: team / owner / both).
 *
 * <p>Delivery happens on the main thread (a single scheduled hop per sweep
 * with alerts — alert volume is tiny and this keeps player lookups and
 * messaging on the thread Bukkit owns). Recipients are resolved from UUIDs
 * through the live server and the cache-only
 * {@link TeamAffiliationService}; names are never authority and the
 * intruder's display name is resolved at delivery time.</p>
 *
 * <p>The decision logic — {@link #recipients} and {@link #renderText} — is
 * static and pure so the test suite covers channel routing and rendering
 * without a server. Templates come from the module's messages.yml catalog
 * (ADR-0012): {@code alert.enter} / {@code alert.leave} with
 * {@code <player> <ward> <decoy> <distance>} placeholders. Names and
 * distances are inserted as unparsed placeholders, so ward names or player
 * names can never inject MiniMessage tags or click events.</p>
 */
public final class AlertRouter {

    private final Plugin plugin;
    private final WardsConfig config;
    private final DistanceService distances;
    private final MessageCatalog messages;
    @Nullable
    private final TeamAffiliationService teams;

    public AlertRouter(Plugin plugin, WardsConfig config, DistanceService distances,
            MessageCatalog messages, @Nullable TeamAffiliationService teams) {
        this.plugin = plugin;
        this.config = config;
        this.distances = distances;
        this.messages = messages;
        this.teams = teams;
    }

    /** Entry point from the async sweep: hop once to the main thread. */
    public void deliver(@NotNull java.util.List<AlertDecision> alerts) {
        if (alerts.isEmpty()) {
            return;
        }
        java.util.List<AlertDecision> snapshot = java.util.List.copyOf(alerts);
        Server server = plugin.getServer();
        if (server.isPrimaryThread()) {
            renderAndSend(snapshot);
        } else {
            server.getScheduler().runTask(plugin, () -> renderAndSend(snapshot));
        }
    }

    private void renderAndSend(java.util.List<AlertDecision> alerts) {
        Function<UUID, String> names = id -> {
            Player player = plugin.getServer().getPlayer(id);
            return player == null ? null : player.getName();
        };
        for (AlertDecision alert : alerts) {
            Component message = renderText(alert, distances, names, messages);
            for (UUID recipient : recipients(alert.ward(), config, teams)) {
                Player player = plugin.getServer().getPlayer(recipient);
                if (player != null) {
                    player.sendMessage(message);
                }
            }
        }
    }

    /**
     * Recipient set for one ward alert per the configured channel. Team
     * channel falls back to the owner when the ward is teamless, the teams
     * module is absent, or the team has no online members (cold cache is
     * never trusted to drop an alert).
     */
    static Set<UUID> recipients(@NotNull Ward ward, @NotNull WardsConfig config,
            @Nullable TeamAffiliationService teams) {
        Set<UUID> result = new LinkedHashSet<>();
        switch (config.alertChannel()) {
            case TEAM -> {
                if (ward.teamId().isPresent() && teams != null) {
                    result.addAll(teams.onlineMembersOf(ward.teamId().get()));
                }
                if (result.isEmpty()) {
                    result.add(ward.owner());
                }
            }
            case OWNER -> result.add(ward.owner());
            case BOTH -> {
                result.add(ward.owner());
                if (ward.teamId().isPresent() && teams != null) {
                    result.addAll(teams.onlineMembersOf(ward.teamId().get()));
                }
            }
        }
        return result;
    }

    /**
     * Renders one alert through the catalog's {@code alert.enter} /
     * {@code alert.leave} templates. The name resolver is delivery-time and
     * server-owned (null renders as the configured {@code alert.unknown-player}
     * text for offline intruders).
     */
    static Component renderText(@NotNull AlertDecision alert, @NotNull DistanceService distances,
            @NotNull Function<UUID, String> nameResolver, @NotNull MessageCatalog catalog) {
        String templateKey = alert.kind() == AlertDecision.Kind.ENTER
                ? "alert.enter" : "alert.leave";
        String rawName = nameResolver.apply(alert.playerId());
        String unknown = catalog.template("alert.unknown-player");
        String distance = alert.kind() == AlertDecision.Kind.ENTER
                ? distances.format(Math.max(0.0, alert.distanceBlocks()), alert.ward().world())
                : "";
        return catalog.raw(templateKey,
                Placeholder.unparsed("player",
                        rawName == null ? (unknown == null ? "someone" : unknown) : rawName),
                Placeholder.unparsed("ward", alert.ward().name()),
                Placeholder.component("decoy", alert.decoy()
                        ? catalog.raw("alert.decoy-tag") : Component.empty()),
                Placeholder.unparsed("distance", distance));
    }
}
