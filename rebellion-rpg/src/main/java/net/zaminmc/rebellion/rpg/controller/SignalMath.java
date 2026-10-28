package net.zaminmc.rebellion.rpg.controller;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Pure geometry for REGION-tier tracking signals (ADR-0016 §3 /
 * ADR-0017). The controller may reveal at most the session's effective
 * precision tier; this class computes exactly what a REGION signal is
 * allowed to carry — an eight-way compass bearing and a distance bucket —
 * never an exact vector. Block units only: the wards calibrated-scale
 * precedent forbids labelling uncalibrated blocks as real-world
 * kilometers.
 *
 * <p>Minecraft axes: +X = east, +Z = south (a position delta of
 * {@code (dx=100, dz=0)} is due east).</p>
 */
public final class SignalMath {

    private SignalMath() {
    }

    /**
     * Eight-way compass bearing for a horizontal delta.
     *
     * @return one of N, NE, E, SE, S, SW, W, NW (upper-case compass codes)
     */
    public static @NotNull String compass8(int dx, int dz) {
        // atan2(dz, dx): 0 = +X (east), PI/2 = +Z (south) — screen/world
        // convention where angles grow clockwise from east.
        double angle = Math.atan2(dz, dx);
        // Math.round yields an integral long here (atan2 spans [-PI, PI], so
        // octant spans [-4, 4]); floorMod wraps the negative north-half
        // octants into [0, 8) — floorMod(double, int) does not exist.
        long octant = Math.round(angle / (Math.PI / 4));
        return switch ((int) Math.floorMod(octant, 8)) {
            case 0 -> "E";
            case 1 -> "SE";
            case 2 -> "S";
            case 3 -> "SW";
            case 4 -> "W";
            case 5 -> "NW";
            case 6 -> "N";
            default -> "NE";
        };
    }

    /**
     * Horizontal distance between two block positions (y ignored — the
     * signal is a ground reading, deliberately coarser than a 3D vector).
     */
    public static double horizontalDistance(int x1, int z1, int x2, int z2) {
        long dx = (long) x2 - x1;
        long dz = (long) z2 - z1;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Distance bucket index for a horizontal distance against the
     * configured ascending edges.
     *
     * @return 0 for "within edges[0]", 1 for "within edges[1]", ...,
     *         {@code edges.size()} for "beyond the last edge" — the
     *         renderer maps the index to a message key, never a number.
     */
    public static int bucketIndex(double horizontalDistance,
            @NotNull List<Integer> edges) {
        for (int i = 0; i < edges.size(); i++) {
            if (horizontalDistance < edges.get(i)) {
                return i;
            }
        }
        return edges.size();
    }
}
