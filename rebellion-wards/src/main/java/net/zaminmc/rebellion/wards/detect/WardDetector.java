package net.zaminmc.rebellion.wards.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardsConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The pure ward detection state machine (ADR-0007) — no Bukkit types, no
 * wall-clock access, fully deterministic under a caller-supplied clock and
 * team resolver. Every rule the master prompt demands lives here and is
 * unit-tested here:
 *
 * <ul>
 *   <li><strong>Outsider definition:</strong> anyone who is neither the ward
 *       owner nor a cached teammate of the owner at decision time. No team
 *       module / not loaded ⇒ everyone except the owner is an outsider.</li>
 *   <li><strong>Enter/leave tracking:</strong> one alert per enter/leave
 *       cycle — moving around inside the radius is silent; leaving ends the
 *       cycle (notice only when the enter actually alerted and
 *       {@code alerts.notify-leave} is on).</li>
 *   <li><strong>Spam protection:</strong> per-(ward, player) re-alert
 *       cooldown that survives the leave; a new ward is never throttled by
 *       an unrelated pair.</li>
 *   <li><strong>Warning delay (dwell):</strong> an enter commits only after
 *       the player remained inside {@code detection.dwell-seconds}; leaving
 *       first produces nothing at all. Masking before the commit suppresses
 *       the alert (the counter-ritual window).</li>
 *   <li><strong>Masking:</strong> masked players never produce new enter
 *       alerts; already-delivered alerts are never undone.</li>
 *   <li><strong>Information only:</strong> the output type is
 *       {@link AlertDecision} — the type system makes a protection side
 *       effect unrepresentable.</li>
 * </ul>
 */
public final class WardDetector {

    private final WardsConfig config;
    private final DetectionState state;
    private final TeamAffiliationService teams;
    private final MaskingResolver masking;

    public WardDetector(WardsConfig config, DetectionState state,
            @Nullable TeamAffiliationService teams, MaskingResolver masking) {
        this.config = config;
        this.state = state;
        this.teams = teams;
        this.masking = masking;
    }

    public DetectionState state() {
        return state;
    }

    /**
     * Runs one player's detection pass over the wards whose cells cover the
     * player's position (the sweep pre-filters candidates via the spatial
     * index; cross-world wards can never appear here because the index is
     * per-world).
     *
     * @param playerId   the (online) player
     * @param world      the player's current world name
     * @param x          horizontal position
     * @param z          horizontal position
     * @param candidates wards covering the player's cell (any state)
     * @param nowMs      logical sweep time
     * @return alerts to deliver (possibly empty; never null)
     */
    public @NotNull List<AlertDecision> detect(@NotNull UUID playerId, @NotNull String world,
            double x, double z, @NotNull List<Ward> candidates, long nowMs) {
        List<AlertDecision> alerts = new ArrayList<>(2);
        for (Ward ward : candidates) {
            if (!ward.active()) {
                // Tracks were cleared service-side on toggle; skip without
                // emitting anything (an inactive ward has no opinions).
                continue;
            }
            boolean inside = ward.horizontalDistanceSquared(x, z) <= ward.radius() * ward.radius();
            TrackKey key = TrackKey.of(playerId, ward.id());
            Track track = state.track(key);

            if (inside && !track.inside()) {
                state.update(key, track.enter(nowMs, config.dwellSeconds() > 0));
                if (config.dwellSeconds() == 0) {
                    commitEnter(key, ward, playerId, x, z, nowMs, alerts);
                }
            } else if (inside && track.inside()) {
                if (track.dwellPending()
                        && nowMs - track.enteredAtMs() >= config.dwellSeconds() * 1000L) {
                    commitEnter(key, ward, playerId, x, z, nowMs, alerts);
                }
            } else if (!inside && track.inside()) {
                boolean reported = track.alerted();
                state.update(key, track.leave());
                if (reported && config.notifyLeave()) {
                    alerts.add(AlertDecision.leave(ward, playerId));
                }
            }
        }
        return alerts;
    }

    /** Enter-commit path shared by instant and dwell-delayed entries. */
    private void commitEnter(TrackKey key, Ward ward, UUID playerId, double x, double z,
            long nowMs, List<AlertDecision> alerts) {
        Track track = state.track(key);
        if (!isOutsider(ward, playerId)) {
            // Teammate/owner entering their own ward: track (so their leave
            // is silent bookkeeping), never alert.
            state.update(key, track.suppressDwell());
            return;
        }
        if (masking.masked(playerId, nowMs)) {
            // Masked before/at the commit: the sentry never saw them.
            state.update(key, track.suppressDwell());
            return;
        }
        long cooldownMs = config.realertCooldownSeconds() * 1000L;
        if (nowMs - track.lastAlertAtMs() < cooldownMs) {
            // Re-enter inside the cooldown window: stay silent, stay inside.
            state.update(key, track.suppressDwell());
            return;
        }
        state.update(key, track.commitAlert(nowMs));
        double distance = Math.sqrt(ward.horizontalDistanceSquared(x, z));
        alerts.add(AlertDecision.enter(ward, playerId, distance));
    }

    /**
     * Outsider test at decision time. Owner always passes; teammates pass
     * through the cache-only {@link TeamAffiliationService}; unknown or
     * absent-team resolution is OUTSIDER (the safe direction: wards alert
     * rather than silently trust a cold cache).
     */
    private boolean isOutsider(Ward ward, UUID player) {
        if (ward.owner().equals(player)) {
            return false;
        }
        Optional<UUID> wardTeam = ward.teamId();
        if (wardTeam.isEmpty() || teams == null) {
            return true;
        }
        return !wardTeam.equals(teams.teamOf(player));
    }
}
