package net.zaminmc.rebellion.artifacts.purge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure selection semantics of the purge kill set (ADR-0015 §4): the ONLINE
 * target set with the holder spared per config — master prompt §5.5 "do
 * not accidentally kill the activating holder unless the configured rules
 * explicitly say so".
 */
final class PurgeSelectionTest {

    private final UUID holder = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void spareHolderDefaultExcludesOnlyTheHolder() {
        List<UUID> online = List.of(holder, a, b, c);
        List<UUID> targets = PurgeExecutor.selectTargets(holder, online, true);
        assertEquals(List.of(a, b, c), targets);
    }

    @Test
    void explicitRuleMayIncludeTheHolder() {
        List<UUID> online = List.of(holder, a, b);
        List<UUID> targets = PurgeExecutor.selectTargets(holder, online, false);
        assertEquals(List.of(holder, a, b), targets);
    }

    @Test
    void holderOfflineMeansNobodyIsSparedForBeingTheHolder() {
        // The holder logged off during the warning window: the ONLINE set
        // simply does not contain them.
        List<UUID> online = List.of(a, b);
        List<UUID> targets = PurgeExecutor.selectTargets(holder, online, true);
        assertEquals(List.of(a, b), targets);
    }

    @Test
    void emptyServerPurgesNobody() {
        assertEquals(List.of(), PurgeExecutor.selectTargets(holder, List.of(), true));
        assertEquals(List.of(), PurgeExecutor.selectTargets(holder, List.of(), false));
    }

    @Test
    void orderIsPreservedForDeterministicOutcomeCounts() {
        List<UUID> online = new ArrayList<>(List.of(c, b, a));
        List<UUID> targets = PurgeExecutor.selectTargets(holder, online, true);
        assertEquals(List.of(c, b, a), targets);
    }
}
