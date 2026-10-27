package net.zaminmc.rebellion.wards.distance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import net.zaminmc.rebellion.wards.WardsConfig;
import org.junit.jupiter.api.Test;

/**
 * Calibration contract of {@link DistanceService} (master prompt §5.2):
 * horizontal-only measurement, cross-world has no distance, and the
 * kilometer gate — nothing is ever labeled "km" for an uncalibrated world.
 */
class DistanceServiceTest {

    private static final String WORLD = "world";
    private static final String NETHER = "world_nether";

    private WardsConfig config(Map<String, Double> scales, String unit) {
        return new WardsConfig("LODESTONE", 6, 16.0, 256.0, 3, 12, 2000,
                java.util.Set.of("leader", "officer"), true, 20, 0, true,
                "AMETHYST_SHARD", WardsConfig.AlertChannel.TEAM, 300, true,
                scales, unit, false);
    }

    // ------------------------------------------------------------------
    // Measurement
    // ------------------------------------------------------------------

    @Test
    void horizontalDistanceIgnoresYAndIsExact() {
        // (0,?,0) to (3,?,4) = 5 exactly, regardless of Y difference.
        double d = DistanceService.horizontalDistance(WORLD, 0, 0, WORLD, 3, 4).orElseThrow();
        assertEquals(5.0, d, 1e-9);
        double d2 = DistanceService.horizontalDistance(WORLD, 0, 0, WORLD, 3, 4).orElseThrow();
        assertEquals(5.0, d2, 1e-9);
    }

    @Test
    void crossWorldHasNoDistanceEver() {
        Optional<Double> none = DistanceService.horizontalDistance(WORLD, 0, 0, NETHER, 0, 0);
        assertTrue(none.isEmpty(), "cross-world distance must never be a number");
        Optional<Double> none2 = DistanceService.horizontalDistance(NETHER, 0, 0, WORLD, 0, 0);
        assertTrue(none2.isEmpty(), "cross-world distance must never be a number (either way)");
        Optional<Double> same = DistanceService.horizontalDistance(WORLD, 0, 0, WORLD, 3, 4);
        assertTrue(same.isPresent(), "same-world always resolves");
    }

    @Test
    void squaredFormMatchesSqrtForm() {
        double squared = DistanceService.horizontalDistanceSquared(10, -7, -2, 5);
        double rooted = Math.sqrt(squared);
        assertEquals(DistanceService.horizontalDistance(WORLD, 10, -7, WORLD, -2, 5)
                .orElseThrow(), rooted, 1e-9);
    }

    // ------------------------------------------------------------------
    // The kilometer gate
    // ------------------------------------------------------------------

    @Test
    void uncalibratedWorldReportsBlocksAndNeverKm() {
        DistanceService service = new DistanceService(config(Map.of(), "blocks"));
        String near = service.format(2400.4, WORLD);
        assertTrue(near.endsWith("blocks"), "expected blocks label, got: " + near);
        assertFalse(near.contains("km"), "uncalibrated world must never print km");
        assertEquals("2400 blocks", near);
        assertEquals("0 blocks", service.format(0, WORLD));
        assertEquals("17 blocks", service.format(16.6, WORLD));
    }

    @Test
    void uncalibratedUnitMetersIsRespected() {
        DistanceService service = new DistanceService(config(Map.of(), "meters"));
        assertEquals("100 meters", service.format(100.2, WORLD));
        assertFalse(service.format(100, WORLD).contains("km"));
    }

    @Test
    void calibratedWorldReportsKilometers() {
        DistanceService service = new DistanceService(
                config(Map.of(WORLD, 1000.0), "blocks"));
        // 2400 blocks at 1000 units/km = 2.4 km - the master prompt's example.
        assertEquals("2.4 km", service.format(2400, WORLD));
        assertEquals("0.3 km", service.format(256.4, WORLD));
        assertEquals("13 km", service.format(13_000, WORLD));
        assertEquals("10 km", service.format(10_000, WORLD));
    }

    @Test
    void calibrationIsPerWorldIndependent() {
        DistanceService service = new DistanceService(
                config(Map.of(WORLD, 1000.0, NETHER, 125.0), "blocks"));
        assertEquals("2.4 km", service.format(2400, WORLD));
        assertEquals("2.4 km", service.format(300, NETHER)); // 300/125
        // A third world stays uncalibrated even when others are not.
        assertEquals("2400 blocks", service.format(2400, "world_the_end"));
    }

    @Test
    void calibratedScaleIsAppliedNotGuessed() {
        DistanceService service = new DistanceService(config(Map.of(NETHER, 125.0), "blocks"));
        // Nether at 1:8 on a 1:1000 overworld scale means 125 u/km: 1000
        // nether blocks = 8 km.
        assertEquals("8.0 km", service.format(1000, NETHER));
    }

    @Test
    void formatDistanceCombinesMeasurementAndCalibration() {
        DistanceService service = new DistanceService(config(Map.of(WORLD, 1000.0), "blocks"));
        // Ward anchor at (1000, 1000); player at (1300, 1000) -> 300 blocks -> 0.3 km.
        double blocks = DistanceService.horizontalDistance(WORLD, 1300, 1000, WORLD, 1000, 1000)
                .orElseThrow();
        assertEquals("0.3 km", service.format(blocks, WORLD));
    }
}
