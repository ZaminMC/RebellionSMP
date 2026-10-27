package net.zaminmc.rebellion.wards.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link MovementFeed} — the O(1) event-path sampler. Marks are idempotent,
 * drains take exactly the marked players with their LATEST snapshot, and
 * quit removes everything.
 */
class MovementFeedTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void updateMarksDirtyAndDrainClears() {
        MovementFeed feed = new MovementFeed();
        feed.update(A, PositionSnapshot.of("world", 1, 64, 1));
        feed.update(B, PositionSnapshot.of("world", 2, 64, 2));

        List<Map.Entry<UUID, PositionSnapshot>> changed = feed.drainChanged();
        assertEquals(2, changed.size());
        assertTrue(feed.drainChanged().isEmpty(), "drain clears the dirty set");
    }

    @Test
    void repeatedMarksYieldLatestSnapshotOnly() {
        MovementFeed feed = new MovementFeed();
        feed.update(A, PositionSnapshot.of("world", 10, 64, 10));
        feed.update(A, PositionSnapshot.of("world", 20, 64, 20));
        feed.update(A, PositionSnapshot.of("world", 30, 64, 30));

        List<Map.Entry<UUID, PositionSnapshot>> changed = feed.drainChanged();
        assertEquals(1, changed.size());
        PositionSnapshot latest = changed.get(0).getValue();
        assertEquals(30.0, latest.x());
        assertEquals(30.0, latest.z());
    }

    @Test
    void snapshotReadsLatestWithoutMarking() {
        MovementFeed feed = new MovementFeed();
        feed.update(A, PositionSnapshot.of("world", 5, 64, 5));
        List<Map.Entry<UUID, PositionSnapshot>> first = feed.drainChanged();
        assertEquals(1, first.size());
        // Position survives the drain; reading it does not re-mark.
        assertEquals(PositionSnapshot.of("world", 5, 64, 5), feed.snapshot(A));
        assertTrue(feed.drainChanged().isEmpty(), "snapshot must not re-mark");
    }

    @Test
    void quitRemovesPositionAndPendingMark() {
        MovementFeed feed = new MovementFeed();
        feed.update(A, PositionSnapshot.of("world", 5, 64, 5));
        feed.remove(A);
        assertNull(feed.snapshot(A));
        assertTrue(feed.drainChanged().isEmpty());
        assertEquals(0, feed.trackedPlayers());
    }
}
