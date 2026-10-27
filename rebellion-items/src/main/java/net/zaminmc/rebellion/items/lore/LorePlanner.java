package net.zaminmc.rebellion.items.lore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Computes the lore transformation for rarity annotation — pure domain logic,
 * no Bukkit types, fully unit-testable.
 *
 * <p>Generic over the lore element type so both tests (plain strings) and the
 * Bukkit adapter (Adventure {@code Component}) share one implementation; the
 * caller supplies a {@code textOf} extractor used only for marker matching.</p>
 *
 * <p>The planner guarantees the product requirements of master prompt §5.4:</p>
 * <ul>
 *   <li>existing lore elements are preserved verbatim in their original order;</li>
 *   <li>re-annotating a stack never duplicates the rarity line (marker-based
 *       de-duplication);</li>
 *   <li>the rarity line is inserted at a configurable position (bottom by
 *       default, top optional);</li>
 *   <li>a null rarity line performs a removal-only pass.</li>
 * </ul>
 *
 * <p>De-duplication matches lines produced by this service via the invisible
 * marker prefix. A manually typed look-alike without the marker is preserved —
 * never silently deleting human-written lore is a stronger guarantee than
 * aggressive cleanup.</p>
 */
public final class LorePlanner {

    /** Sentinel embedded in our rarity lines; ASCII SOH (invisible in chat). */
    static final String MARKER = "\u0001RB";

    /** Where the rarity line is placed within the lore. */
    public enum Position { TOP, BOTTOM }

    private final Position position;

    public LorePlanner(Position position) {
        this.position = position == null ? Position.BOTTOM : position;
    }

    /**
     * @param existing   current lore elements (may be null)
     * @param textOf    plain-text extractor used for marker detection
     * @param rarityLine the new rarity element <em>with</em> marker embedded
     *                   (see {@link #isMarkerLine(String)}), or null for a
     *                   removal-only pass
     * @return the transformed lore; a new list even when nothing changes
     */
    public <T> List<T> plan(List<T> existing, Function<T, String> textOf, T rarityLine) {
        List<T> cleaned = new ArrayList<>();
        if (existing != null) {
            for (T element : existing) {
                if (element == null) {
                    continue;
                }
                String text = textOf.apply(element);
                if (!isMarkerLine(text)) {
                    cleaned.add(element);
                }
            }
        }
        if (rarityLine == null) {
            return cleaned;
        }
        List<T> out = new ArrayList<>(cleaned);
        if (position == Position.TOP && !out.isEmpty()) {
            out.add(0, rarityLine);
        } else {
            out.add(rarityLine);
        }
        return out;
    }

    /** True when the line was produced by this service. */
    public static boolean isMarkerLine(String line) {
        return line != null && line.contains(MARKER);
    }

    /** The raw marker, for callers that need to embed it themselves. */
    public static String marker() {
        return MARKER;
    }
}
