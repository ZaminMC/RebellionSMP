package net.zaminmc.rebellion.wards.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardsConfig;
import net.zaminmc.rebellion.wards.alert.AlertRouter;
import net.zaminmc.rebellion.wards.detect.AlertDecision;
import net.zaminmc.rebellion.wards.detect.DetectionState;
import net.zaminmc.rebellion.wards.detect.WardDetector;
import net.zaminmc.rebellion.wards.feed.MovementFeed;
import net.zaminmc.rebellion.wards.feed.PositionSnapshot;
import net.zaminmc.rebellion.wards.index.WardSpatialIndex;
import org.jetbrains.annotations.NotNull;

/**
 * The cadenced detection sweep (master prompt §5.2). Runs async at
 * {@code detection.sweep-interval-ticks} and processes exactly:
 *
 * <ol>
 *   <li>players whose position changed since the last sweep (the movement
 *       feed's dirty set — movement events did O(1) marking, nothing else),
 *       plus</li>
 *   <li>players currently inside a ward awaiting a dwell-delayed alert (they
 *       may be standing perfectly still, and the delay must still commit).</li>
 * </ol>
 *
 * <p>Per player: one index cell query (bounded candidates), one detector
 * pass (pure state machine), zero database reads, zero world scans. Alerts
 * batch into one main-thread hop via {@link AlertRouter#deliver}.</p>
 *
 * <p>Threading: the runnable runs on an async scheduler thread; the feed,
 * index, detector state and team cache are all concurrent structures. The
 * logical clock is the sweep's own {@code System#currentTimeMillis} read —
 * tests exercise the detector with fixed clocks instead of this class,
 * which stays a thin orchestrator.</p>
 */
public final class WardSweepTask implements Runnable {

    private final WardsConfig config;
    private final WardSpatialIndex index;
    private final MovementFeed feed;
    private final WardDetector detector;
    private final DetectionState state;
    private final AlertRouter router;

    public WardSweepTask(WardsConfig config, WardSpatialIndex index, MovementFeed feed,
            WardDetector detector, DetectionState state, AlertRouter router) {
        this.config = config;
        this.index = index;
        this.feed = feed;
        this.detector = detector;
        this.state = state;
        this.router = router;
    }

    @Override
    public void run() {
        long nowMs = System.currentTimeMillis();
        List<AlertDecision> alerts = new ArrayList<>();

        // 1. Changed players (movement since the last sweep).
        List<Map.Entry<UUID, PositionSnapshot>> changed = feed.drainChanged();
        Set<UUID> toProcess = new HashSet<>();
        for (Map.Entry<UUID, PositionSnapshot> entry : changed) {
            toProcess.add(entry.getKey());
        }

        // 2. Players inside a ward (dwell-pending commits need time, not motion).
        //    This pass also prunes idle (player, ward) pairs — bounded memory.
        toProcess.addAll(state.insidePlayers(nowMs));

        for (UUID player : toProcess) {
            PositionSnapshot snapshot = feed.snapshot(player);
            if (snapshot == null) {
                continue; // quit raced the sweep; quit handling cleared state
            }
            List<Ward> candidates = index.query(snapshot.world(), snapshot.x(), snapshot.z());
            if (candidates.isEmpty()) {
                continue;
            }
            alerts.addAll(detector.detect(player, snapshot.world(), snapshot.x(),
                    snapshot.z(), candidates, nowMs));
        }
        router.deliver(alerts);
    }

    /** Config accessor for the plugin (interval lives with the task). */
    public @NotNull WardsConfig config() {
        return config;
    }
}
