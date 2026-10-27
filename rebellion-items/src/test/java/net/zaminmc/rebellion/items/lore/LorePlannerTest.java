package net.zaminmc.rebellion.items.lore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Marker-based lore planning: preservation, de-duplication, positions. */
final class LorePlannerTest {

    private static final String M = "\u0001RB";

    /** String extractor: elements are their own text. */
    private static final java.util.function.Function<String, String> ID = s -> s;

    @Test
    void appendsRarityLineToBottomByDefault() {
        LorePlanner planner = new LorePlanner(LorePlanner.Position.BOTTOM);
        List<String> out = planner.plan(List.of("Keep me"), ID, M + "Epic");
        assertEquals(List.of("Keep me", M + "Epic"), out);
    }

    @Test
    void topPositionInsertsBeforeExistingLore() {
        LorePlanner planner = new LorePlanner(LorePlanner.Position.TOP);
        List<String> out = planner.plan(List.of("Keep me"), ID, M + "Epic");
        assertEquals(List.of(M + "Epic", "Keep me"), out);
    }

    @Test
    void nullPositionDefaultsToBottom() {
        LorePlanner planner = new LorePlanner(null);
        List<String> out = planner.plan(List.of("a"), ID, M + "X");
        assertEquals(List.of("a", M + "X"), out);
    }

    @Test
    void reAnnotatingReplacesMarkerLineWithoutDuplication() {
        LorePlanner planner = new LorePlanner(LorePlanner.Position.BOTTOM);
        List<String> once = planner.plan(List.of("Human lore"), ID, M + "Rare");
        List<String> twice = planner.plan(once, ID, M + "Legendary");
        assertEquals(List.of("Human lore", M + "Legendary"), twice);
        assertEquals(2, twice.size());
    }

    @Test
    void humanLoreThatLooksLikeARarityLineIsPreserved() {
        LorePlanner planner = new LorePlanner(LorePlanner.Position.BOTTOM);
        // No marker: human-written, must never be silently deleted.
        List<String> out = planner.plan(List.of("Epic item forged by Zamin"), ID, M + "Epic");
        assertEquals(2, out.size());
        assertTrue(out.contains("Epic item forged by Zamin"));
        assertTrue(out.contains(M + "Epic"));
    }

    @Test
    void removalOnlyPassStripsAllMarkerLines() {
        LorePlanner planner = new LorePlanner(LorePlanner.Position.BOTTOM);
        List<String> out = planner.plan(
                List.of(M + "Old", "keep", M + "Older", "also keep"), ID, null);
        assertEquals(List.of("keep", "also keep"), out);
    }

    @Test
    void nullAndEmptyLoreHandled() {
        LorePlanner planner = new LorePlanner(LorePlanner.Position.BOTTOM);
        assertEquals(List.of(M + "Common"), planner.plan(null, ID, M + "Common"));
        assertEquals(List.of(M + "Common"), planner.plan(List.of(), ID, M + "Common"));
        assertEquals(List.of(), planner.plan(null, ID, null));
    }

    @Test
    void nullElementsAreDroppedNotCrashing() {
        LorePlanner planner = new LorePlanner(LorePlanner.Position.BOTTOM);
        List<String> out = planner.plan(Arrays.asList("a", null, "b"), ID, M + "X");
        assertEquals(List.of("a", "b", M + "X"), out);
    }

    @Test
    void markerDetection() {
        assertTrue(LorePlanner.isMarkerLine(M + "Epic"));
        assertFalse(LorePlanner.isMarkerLine("Epic"));
        assertFalse(LorePlanner.isMarkerLine(null));
        assertEquals(M, LorePlanner.marker());
    }

    @Test
    void topPositionOnEmptyLoreAppends() {
        LorePlanner planner = new LorePlanner(LorePlanner.Position.TOP);
        assertEquals(List.of(M + "X"), planner.plan(List.of(), ID, M + "X"));
    }

    @Test
    void worksForComponentLikeElementsViaExtractor() {
        // Same code path the Bukkit adapter uses for Adventure components.
        record Elem(String text) { }
        java.util.function.Function<Elem, String> textOf = Elem::text;
        LorePlanner planner = new LorePlanner(LorePlanner.Position.BOTTOM);
        List<Elem> out = planner.plan(
                List.of(new Elem(M + "Old"), new Elem("keep")),
                textOf, new Elem(M + "New"));
        assertEquals(2, out.size());
        assertEquals("keep", out.get(0).text());
        assertEquals(M + "New", out.get(1).text());
    }
}
