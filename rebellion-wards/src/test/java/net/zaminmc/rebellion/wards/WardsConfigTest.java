package net.zaminmc.rebellion.wards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import net.zaminmc.rebellion.wards.WardsConfig.AlertChannel;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * {@link WardsConfig} load + validation. The load-bearing assertion set:
 * the kilometer map ships EMPTY (kilometers disabled by default), invalid
 * scales are rejected (never guessed), and every bounded field reports a
 * precise issue instead of failing on the first problem.
 */
class WardsConfigTest {

    private static YamlConfiguration yaml(String body) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(body);
        } catch (Exception e) {
            throw new AssertionError("test yaml broke", e);
        }
        return config;
    }

    @Test
    void defaultsAreValidAndKilometersShipDisabled() {
        var result = WardsConfig.loadResult(yaml(""));
        assertTrue(result.valid(), () -> String.join("; ", result.issues()));
        WardsConfig config = result.config();
        assertEquals("LODESTONE", config.anchorMaterial());
        assertEquals(16.0, config.minRadius());
        assertEquals(256.0, config.maxRadius());
        assertEquals(3, config.maxWardsPerPlayer());
        assertEquals(20, config.sweepIntervalTicks());
        assertEquals(0, config.dwellSeconds());
        assertEquals(AlertChannel.TEAM, config.alertChannel());
        assertEquals(300, config.realertCooldownSeconds());
        assertTrue(config.notifyLeave());
        assertTrue(config.decoysEnabled());
        assertTrue(config.maskingEnabled());
        assertEquals("AMETHYST_SHARD", config.maskingHeldMaterial());
        // THE gate: no world is calibrated by default, so nothing can be
        // labeled km until an admin calibrates it.
        assertTrue(config.worldUnitsPerKilometer().isEmpty(),
                "kilometer calibration must ship disabled");
        assertFalse(config.kilometersCalibrated("world"));
        assertEquals("blocks", config.uncalibratedUnit());
        assertFalse(config.publishWardMarkers(), "map markers must ship disabled");
        assertEquals(Set.of("leader", "officer"), config.manageRanks());
    }

    @Test
    void fullConfigParses() {
        var result = WardsConfig.loadResult(yaml("""
                wards:
                  anchor-material: LODESTONE
                  create-reach-blocks: 8
                  min-radius: 32.0
                  max-radius: 512.0
                  max-wards-per-player: 5
                  max-wards-per-team: 20
                  max-wards-total: 500
                  manage-ranks: [leader]
                  decoys:
                    enabled: false
                detection:
                  sweep-interval-ticks: 40
                  dwell-seconds: 10
                  masking:
                    enabled: false
                    held-material: DIAMOND
                alerts:
                  channel: both
                  realert-cooldown-seconds: 60
                  notify-leave: false
                distance:
                  world-units-per-kilometer:
                    world: 1000.0
                    world_nether: 125.0
                  uncalibrated-unit: meters
                map-markers:
                  publish-wards: true
                """));
        assertTrue(result.valid(), () -> String.join("; ", result.issues()));
        WardsConfig config = result.config();
        assertEquals(8, config.createReachBlocks());
        assertEquals(512.0, config.maxRadius());
        assertEquals(5, config.maxWardsPerPlayer());
        assertFalse(config.decoysEnabled());
        assertEquals(10, config.dwellSeconds());
        assertFalse(config.maskingEnabled());
        assertEquals(AlertChannel.BOTH, config.alertChannel());
        assertFalse(config.notifyLeave());
        assertEquals(Map.of("world", 1000.0, "world_nether", 125.0),
                config.worldUnitsPerKilometer());
        assertTrue(config.kilometersCalibrated("world"));
        assertEquals("meters", config.uncalibratedUnit());
        assertTrue(config.publishWardMarkers());
        assertEquals(Set.of("leader"), config.manageRanks());
    }

    @Test
    void invalidScalesAreRejectedNotGuessed() {
        var result = WardsConfig.loadResult(yaml("""
                distance:
                  world-units-per-kilometer:
                    world: 0.0
                    world_nether: -5.0
                    world_the_end: 999999999.0
                """));
        assertFalse(result.valid());
        assertEquals(3, result.issues().size());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("world:")
                || i.contains(".world")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("world_nether")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("world_the_end")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("do not guess")));
        assertNull(result.config());
    }

    @Test
    void everyInvalidFieldReportsItsOwnIssue() {
        var result = WardsConfig.loadResult(yaml("""
                wards:
                  anchor-material: NOT_A_BLOCK
                  create-reach-blocks: 1
                  min-radius: 2.0
                  max-radius: 1.0
                  max-wards-per-player: 0
                  max-wards-per-team: 0
                  max-wards-total: 1
                  manage-ranks: []
                detection:
                  sweep-interval-ticks: 0
                  dwell-seconds: -1
                  masking:
                    enabled: true
                    held-material: ALSO_NOT_A_BLOCK
                alerts:
                  channel: smoke-signals
                  realert-cooldown-seconds: -1
                distance:
                  uncalibrated-unit: furlongs
                """));
        assertFalse(result.valid());
        // Every field with a problem is named.
        assertTrue(result.issues().size() >= 12,
                "expected every invalid field reported, got: " + result.issues());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("anchor-material")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("create-reach")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("min-radius")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("max-radius")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("max-wards-per-player")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("max-wards-per-team")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("max-wards-total")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("sweep-interval")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("dwell-seconds")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("held-material")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("alerts.channel")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("realert-cooldown")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("uncalibrated-unit")));
    }

    @Test
    void loadThrowsOnInvalidInsteadOfReturningBrokenConfig() {
        assertThrows_(yaml("wards:\n  max-radius: 1.0\n"));
    }

    private static void assertThrows_(YamlConfiguration yaml) {
        try {
            WardsConfig.load(yaml);
            throw new AssertionError("load must refuse an invalid config");
        } catch (IllegalStateException expected) {
            // precise issues, not a silent fallback
        }
    }
}
