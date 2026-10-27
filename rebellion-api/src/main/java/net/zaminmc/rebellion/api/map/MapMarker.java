package net.zaminmc.rebellion.api.map;

import org.jetbrains.annotations.NotNull;

/**
 * One published web-map marker (ADR-0007). Value object only — positioning,
 * icons and rendering belong to the future web-map adapter (Phase 10), not
 * to this contract.
 *
 * <p>Privacy contract (master prompt §14): markers carry positions, so a
 * provider must only publish a category after its own admin gate
 * <em>explicitly</em> enables that exact category. {@link MapMarkerProvider}
 * implementations are the first enforcement point; the adapter re-checks
 * before anything reaches a public map.</p>
 *
 * @param category stable category key, e.g. {@code wards}
 * @param world    world name (never null)
 * @param x        world x coordinate
 * @param y        world y coordinate
 * @param z        world z coordinate
 * @param label    already-sanitized display label (no MiniMessage tags)
 * @param iconHint adapter-defined icon hint, e.g. {@code ward}; adapters may
 *                 ignore it
 */
public record MapMarker(
        @NotNull String category,
        @NotNull String world,
        double x,
        double y,
        double z,
        @NotNull String label,
        @NotNull String iconHint) {

    public MapMarker {
        if (category.isBlank() || world.isBlank() || label.isBlank()) {
            throw new IllegalArgumentException("category, world and label must be non-blank");
        }
    }
}
