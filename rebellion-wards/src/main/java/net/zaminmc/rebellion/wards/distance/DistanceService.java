package net.zaminmc.rebellion.wards.distance;

import java.util.Locale;
import java.util.Optional;
import net.zaminmc.rebellion.wards.WardsConfig;
import org.jetbrains.annotations.NotNull;

/**
 * Calibrated distance reporting (master prompt §5.2, ADR-0007).
 *
 * <h2>What this service guarantees</h2>
 * <ul>
 *   <li><strong>Horizontal world-coordinate distance only.</strong> Y is
 *       ignored — a player directly above or below the anchor is "at" it
 *       horizontally. This keeps detection and display consistent.</li>
 *   <li><strong>Kilometers are never guessed.</strong> A world only reports
 *       {@code km} when its {@code world-units-per-kilometer} was explicitly
 *       configured (validated finite, positive, plausible at load). Without
 *       calibration the formatter emits blocks (or meters, per config) and
 *       the string "km" cannot appear — the exact defect class the gate
 *       exists to prevent.</li>
 *   <li><strong>Cross-world has no distance.</strong> Two positions in
 *       different worlds have no meaningful metric; callers get
 *       {@link Optional#empty()} and must not fall back to a number. The
 *       detector never pairs cross-world (the spatial index is per-world);
 *       dimension transitions are documented as leave events.</li>
 *   <li><strong>No wraparound.</strong> Vanilla worlds do not wrap at the
 *       border; distance is plain Euclidean on coordinates. Border effects
 *       on gameplay are the operator's to consider (documented in
 *       docs/CONFIGURATION.md).</li>
 * </ul>
 *
 * <p>Calibration procedure (docs/CONFIGURATION.md "Ward distance
 * calibration"): pick two landmarks a known real-world distance apart on the
 * imported map, take their world coordinates, divide coordinate distance by
 * real kilometers, put the result in {@code distance.world-units-per-kilometer.<world>}.</p>
 */
public final class DistanceService {

    private final WardsConfig config;

    public DistanceService(WardsConfig config) {
        this.config = config;
    }

    /**
     * Horizontal distance between two same-world positions, or empty when
     * the worlds differ (never a number for cross-world).
     */
    public static Optional<Double> horizontalDistance(@NotNull String worldA, double ax, double az,
            @NotNull String worldB, double bx, double bz) {
        if (!worldA.equals(worldB)) {
            return Optional.empty();
        }
        double dx = ax - bx;
        double dz = az - bz;
        return Optional.of(Math.sqrt(dx * dx + dz * dz));
    }

    /** Squared comparison form for radius checks (no sqrt on hot paths). */
    public static double horizontalDistanceSquared(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    /**
     * Formats a same-world block distance per the world's calibration:
     * {@code "2.4 km"} when calibrated (one decimal below 10 km, integer
     * above), otherwise {@code "2400 blocks"} / {@code "2400 meters"} per
     * {@code distance.uncalibrated-unit}. Integer-block display truncates
     * sub-block slivers.
     *
     * @param blocks horizontal distance in world units (never negative)
     * @param world  the world the distance was measured in
     */
    public @NotNull String format(double blocks, @NotNull String world) {
        Double scale = config.worldUnitsPerKilometer().get(world);
        if (scale == null) {
            long whole = Math.round(Math.max(0.0, blocks));
            return whole + " " + config.uncalibratedUnit();
        }
        double km = Math.max(0.0, blocks) / scale;
        if (km >= 100.0) {
            return String.format(Locale.ROOT, "%.0f km", km);
        }
        return String.format(Locale.ROOT, "%.1f km", km);
    }

    /**
     * Convenience: distance between a position and a ward anchor, then
     * format. Empty world pairing surfaces as an IllegalArgumentException —
     * callers on the hot path pre-filter by world.
     */
    public @NotNull String formatDistance(double px, double pz, @NotNull String wardWorld,
            double wx, double wz) {
        return format(horizontalDistance(wardWorld, wx, wz, wardWorld, px, pz)
                .orElseThrow(() -> new IllegalArgumentException(
                        "cross-world distance has no format")), wardWorld);
    }
}
