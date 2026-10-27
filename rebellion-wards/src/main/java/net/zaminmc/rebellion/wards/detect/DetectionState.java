package net.zaminmc.rebellion.wards.detect;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Concurrent enter/leave + cooldown state for all (player, ward) pairs.
 *
 * <p>State here is deliberately <em>ephemeral</em>: it is detection working
 * memory, not persisted authority. A restart empties it, which is correct —
 * outsiders standing inside a ward after a restart are re-detected as new
 * entries on their first movement.</p>
 *
 * <h2>Bounded memory</h2>
 * <ul>
 *   <li>Pairs are created only on ENTER transitions, never per movement
 *       event.</li>
 *   <li>{@link #insidePlayers(long)} prunes idle pairs (outside + older than
 *       the prune horizon) during its pass, so an idle pair costs memory at
 *       most 4 cooldowns + the sweep cadence.</li>
 *   <li>{@link #clearPlayer} (quit) and {@link #clearWard} (remove/toggle)
 *       drop every pair of that side immediately.</li>
 * </ul>
 */
public final class DetectionState {

    private final ConcurrentHashMap<TrackKey, Track> pairs = new ConcurrentHashMap<>();
    private final long pruneAfterMs;

    /**
     * @param pruneAfterMs how long an outside pair survives before pruning;
     *                     callers pass a horizon comfortably above the
     *                     re-alert cooldown
     */
    public DetectionState(long pruneAfterMs) {
        this.pruneAfterMs = pruneAfterMs;
    }

    /** Current track for a pair; {@link Track#OUTSIDE} semantics when absent. */
    Track track(TrackKey key) {
        return pairs.getOrDefault(key, Track.OUTSIDE);
    }

    /** Atomic read-modify-write for one pair (single-threaded sweep makes this a formality). */
    void update(TrackKey key, Track updated) {
        pairs.put(key, updated);
    }

    /**
     * Players with at least one inside track (pending dwell commits must be
     * re-checked even without movement) plus a lazy prune of idle pairs.
     */
    public @NotNull Set<UUID> insidePlayers(long nowMs) {
        Set<UUID> result = new HashSet<>();
        for (var entry : pairs.entrySet()) {
            if (entry.getValue().pruneable(nowMs, pruneAfterMs)) {
                pairs.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (entry.getValue().inside()) {
                result.add(entry.getKey().player());
            }
        }
        return result;
    }

    /** Number of outsiders currently inside one ward (live intel for /ward info). */
    public int insidersOf(@NotNull UUID wardId) {
        int count = 0;
        for (var entry : pairs.entrySet()) {
            if (entry.getKey().ward().equals(wardId)) {
                Track track = entry.getValue();
                // Count only reported insiders: unreported (suppressed) ones
                // are intentionally invisible to the owner.
                if (track.inside() && track.alerted()) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Drops every pair of a player (quit). */
    public void clearPlayer(@NotNull UUID playerId) {
        pairs.keySet().removeIf(key -> key.player().equals(playerId));
    }

    /** Drops every pair of a ward (removal or active toggle; re-activation re-detects). */
    public void clearWard(@NotNull UUID wardId) {
        pairs.keySet().removeIf(key -> key.ward().equals(wardId));
    }

    /** Live pair count (diagnostics/benchmarks). */
    public int size() {
        return pairs.size();
    }
}
