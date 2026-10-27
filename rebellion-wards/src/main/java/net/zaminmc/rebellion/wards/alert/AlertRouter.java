package net.zaminmc.rebellion.wards.alert;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * without a server.</p>
 */
public final class AlertRouter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String ENTER_TEMPLATE =
            "<gray>[Ward]</gray> <yellow>{player}</yellow> is approaching "
            + "<aqua>{ward}</aqua>{decoy} from <green>{distance}</green> away.";
    private static final String LEAVE_TEMPLATE =
            "<gray>[Ward]</gray> <yellow>{player}</yellow> left the radius of "
            + "<aqua>{ward}</aqua>{decoy}.";

    private final Plugin plugin;
    private final WardsConfig config;
    private final DistanceService distances;
    @Nullable
    private final TeamAffiliationService teams;

    public AlertRouter(Plugin plugin, WardsConfig config, DistanceService distances,
            @Nullable TeamAffiliationService teams) {
        this.plugin = plugin;
        this.config = config;
        this.distances = distances;
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
            Component message = MINI.deserialize(
                    renderText(alert, distances, names));
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

    /** Fills the alert template; the name resolver is delivery-time and
     *  server-owned (null renders as "someone" for offline intruders). */
    static String renderText(@NotNull AlertDecision alert, @NotNull DistanceService distances,
            @NotNull Function<UUID, String> nameResolver) {
        String template = alert.kind() == AlertDecision.Kind.ENTER
                ? ENTER_TEMPLATE : LEAVE_TEMPLATE;
        String distance = alert.kind() == AlertDecision.Kind.ENTER
                ? distances.format(Math.max(0.0, alert.distanceBlocks()), alert.ward().world())
                : "";
        String rawName = nameResolver.apply(alert.playerId());
        return template
                .replace("{player}", escape(rawName == null ? "someone" : rawName))
                .replace("{ward}", escape(alert.ward().name()))
                .replace("{decoy}", alert.decoy() ? " <gray>(decoy)</gray>" : "")
                .replace("{distance}", distance);
    }

    /** Neutralizes MiniMessage tag injection (names are pre-validated; belt and braces). */
    private static String escape(String value) {
        return value == null ? "" : value.replace("<", "").replace(">", "");
    }
}
