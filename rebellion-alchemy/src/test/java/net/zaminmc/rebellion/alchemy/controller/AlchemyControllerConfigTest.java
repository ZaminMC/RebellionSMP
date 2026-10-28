package net.zaminmc.rebellion.alchemy.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Pins the controller-config contract (ADR-0020 §5): the optional
 * section's defaults and every bound. An absent section yields the
 * shipped defaults without a single issue — the persistence-only contract
 * of ADR-0019-era config files stays intact.
 */
final class AlchemyControllerConfigTest {

    @Test
    void absentSectionYieldsDefaults() {
        AlchemyControllerConfig.LoadResult result =
                AlchemyControllerConfig.load(new YamlConfiguration());
        assertTrue(result.valid());
        assertEquals(AlchemyControllerConfig.defaults(), result.config());
        assertEquals(2, result.config().autoEvalIntervalSeconds());
        assertEquals(60, result.config().sweepIntervalSeconds());
        assertEquals(120L, result.config().reservationStaleSeconds());
        assertEquals(8, result.config().fallThresholdBlocks());
        assertTrue(result.config().autoConsumeNotify());
    }

    @Test
    void fullSectionLoads() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("alchemy.controller.auto-eval-interval-seconds", 5);
        yaml.set("alchemy.controller.sweep-interval-seconds", 30);
        yaml.set("alchemy.controller.reservation-stale-seconds", 600);
        yaml.set("alchemy.controller.fall-threshold-blocks", 12);
        yaml.set("alchemy.controller.auto-consume-notify", false);
        AlchemyControllerConfig.LoadResult result = AlchemyControllerConfig.load(yaml);
        assertTrue(result.valid(), () -> result.issues().toString());
        assertEquals(5, result.config().autoEvalIntervalSeconds());
        assertEquals(30, result.config().sweepIntervalSeconds());
        assertEquals(600L, result.config().reservationStaleSeconds());
        assertEquals(12, result.config().fallThresholdBlocks());
        assertFalse(result.config().autoConsumeNotify());
    }

    @Test
    void boundsAreCollected() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("alchemy.controller.auto-eval-interval-seconds", 0);
        yaml.set("alchemy.controller.sweep-interval-seconds", 601);
        yaml.set("alchemy.controller.reservation-stale-seconds", 5);
        yaml.set("alchemy.controller.fall-threshold-blocks", 2);
        AlchemyControllerConfig.LoadResult result = AlchemyControllerConfig.load(yaml);
        assertFalse(result.valid());
        assertEquals(4, result.issues().size());
        assertTrue(result.issues().stream().anyMatch(i ->
                i.contains("auto-eval-interval-seconds")));
        assertTrue(result.issues().stream().anyMatch(i ->
                i.contains("sweep-interval-seconds")));
        assertTrue(result.issues().stream().anyMatch(i ->
                i.contains("reservation-stale-seconds")));
        assertTrue(result.issues().stream().anyMatch(i ->
                i.contains("fall-threshold-blocks")));
    }

    @Test
    void issueStringsCarryTheFullPath() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("alchemy.controller.auto-eval-interval-seconds", 999);
        List<String> issues = AlchemyControllerConfig.load(yaml).issues();
        assertTrue(issues.get(0).startsWith("alchemy.controller."
                        + "auto-eval-interval-seconds"),
                () -> issues.toString());
    }
}
