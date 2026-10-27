package net.zaminmc.rebellion.wards.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Movement sampler between the (very hot) move-event path and the (cadenced)
 * detection sweep, per master prompt §5.2: movement events only mark a
 * player dirty with their latest coordinates; the sweep processes dirty
 * players at {@code detection.sweep-interval-ticks}. No per-event scans, no
 * per-tick work, no per-ward work on the event path — one map put.
 *
 * <p>Thread contract: event handlers (main thread) call {@link #update} and
 * {@link #remove}; the async sweep calls {@link #drainChanged} and
 * {@link #snapshot}. All structures are concurrent; a position update that
 * races the drain is simply picked up by the next sweep — sampling, not
 * event delivery.</p>
 */
public final class MovementFeed {

    private final ConcurrentHashMap<UUID, PositionSnapshot> latest = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<UUID, Boolean> dirty = ConcurrentHashMap.newKeySet();

    /** Event path: record the latest position and mark the player dirty. O(1). */
    public void update(@NotNull UUID player, @NotNull PositionSnapshot snapshot) {
        latest.put(player, snapshot);
        dirty.add(player);
    }

    /** Quit path: drop the player's position and pending mark. */
    public void remove(@NotNull UUID player) {
        latest.remove(player);
        dirty.remove(player);
    }

    /** Sweep path: every player marked since the last drain, with their latest snapshot. */
    public @NotNull List<Map.Entry<UUID, PositionSnapshot>> drainChanged() {
        List<Map.Entry<UUID, PositionSnapshot>> changed = new ArrayList<>(dirty.size());
        for (UUID player : dirty) {
            dirty.remove(player);
            PositionSnapshot snapshot = latest.get(player);
            if (snapshot != null) {
                changed.add(Map.entry(player, snapshot));
            }
        }
        return changed;
    }

    /** Latest snapshot for players the sweep must re-check (dwell-pending) without movement. */
    public @Nullable PositionSnapshot snapshot(@NotNull UUID player) {
        return latest.get(player);
    }

    /** Diagnostics/benchmarks. */
    public int trackedPlayers() {
        return latest.size();
    }
}
