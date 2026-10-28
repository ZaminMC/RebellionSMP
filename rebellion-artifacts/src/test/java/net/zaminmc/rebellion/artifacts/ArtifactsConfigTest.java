package net.zaminmc.rebellion.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * ArtifactsConfig validation (ADR-0013): the per-dimension origin anchors
 * (config-owned world-setup data), the return-delay bound, the
 * rebellion-event toggle, and the loud refusal of the unimplemented
 * team-wide trigger scope (master prompt §5.5 demands it be a separate
 * explicit option, never a silent stretch). The shipped resource file itself
 * is loaded and must validate with zero issues, and the
 * {@link ArtifactsConfig#defaults()} factory must stay in lockstep with it.
 */
final class ArtifactsConfigTest {

    private static YamlConfiguration yaml(String body) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(new StringReader(body));
        } catch (Exception e) {
            throw new IllegalStateException("test yaml invalid", e);
        }
        return config;
    }

    /** The actual bundled config.yml (the file the module ships with). */
    private static YamlConfiguration shipped() {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream in = ArtifactsConfigTest.class
                .getResourceAsStream("/config.yml")) {
            assertTrue(in != null, "bundled config.yml must exist in test resources");
            config.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("shipped config invalid", e);
        }
        return config;
    }

    // ------------------------------------------------------------------
    // Shipped resource + defaults factory
    // ------------------------------------------------------------------

    @Test
    void shippedResourceFileIsValid() {
        ArtifactsConfig.LoadResult result = ArtifactsConfig.loadResult(shipped());
        assertTrue(result.valid(), () -> "shipped config issues: " + result.issues());
        assertEquals(3, ArtifactsPolicy.ARTIFACT_COUNT, "exactly three dimensions");
    }

    @Test
    void defaultsFactoryMatchesTheShippedFile() {
        ArtifactsConfig shipped = ArtifactsConfig.load(shipped());
        ArtifactsConfig factory = ArtifactsConfig.defaults();
        assertEquals(shipped, factory,
                "defaults() must mirror the shipped config.yml exactly");
    }

    @Test
    void emptyConfigGainsTheDocumentedDefaults() {
        ArtifactsConfig config = ArtifactsConfig.load(yaml("artifacts: {}\n"));
        assertEquals("world", config.origin(ArtifactKey.OVERWORLD).world());
        assertEquals("world_nether", config.origin(ArtifactKey.NETHER).world());
        assertEquals("world_the_end", config.origin(ArtifactKey.END).world());
        assertEquals(3600L, config.returnDelaySeconds(), "1 hour default");
        assertEquals(3_600_000L, config.returnDelayMs());
        assertTrue(config.rebellionEventEnabled());
    }

    // ------------------------------------------------------------------
    // Origin anchors (config-owned world-setup data)
    // ------------------------------------------------------------------

    @Test
    void originAnchorsAreFullyConfigurable() {
        ArtifactsConfig config = ArtifactsConfig.load(yaml("""
                artifacts:
                  origins:
                    overworld:
                      world: earth_world
                      x: 1200
                      y: 80
                      z: -4500
                """));
        var anchor = config.origin(ArtifactKey.OVERWORLD);
        assertEquals("earth_world", anchor.world());
        assertEquals(1200, anchor.x());
        assertEquals(80, anchor.y());
        assertEquals(-4500, anchor.z());
        // Untouched segments keep their per-dimension defaults.
        assertEquals("world_nether", config.origin(ArtifactKey.NETHER).world());
    }

    @Test
    void blankOrOverlongOriginWorldIsALoudLoadError() {
        ArtifactsConfig.LoadResult blank = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  origins:
                    end:
                      world: "  "
                """));
        assertFalse(blank.valid());
        assertTrue(blank.issues().stream().anyMatch(i -> i.contains("origins.end.world")));

        ArtifactsConfig.LoadResult overlong = ArtifactsConfig.loadResult(yaml(
                "artifacts:\n  origins:\n    end:\n      world: "
                        + "x".repeat(65) + "\n"));
        assertFalse(overlong.valid());
        assertTrue(overlong.issues().stream().anyMatch(i -> i.contains("origins.end.world")));
    }

    @Test
    void originCoordinatesAreBounded() {
        ArtifactsConfig.LoadResult offBorder = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  origins:
                    nether:
                      x: 31000000
                """));
        assertFalse(offBorder.valid());
        assertTrue(offBorder.issues().stream().anyMatch(i -> i.contains(".x/.z")));

        ArtifactsConfig.LoadResult offBand = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  origins:
                    nether:
                      y: 999
                """));
        assertFalse(offBand.valid());
        assertTrue(offBand.issues().stream().anyMatch(i -> i.contains(".y")));
    }

    // ------------------------------------------------------------------
    // Return delay
    // ------------------------------------------------------------------

    @Test
    void returnDelayIsBoundedAndZeroMeansImmediatelyEligible() {
        ArtifactsConfig immediate = ArtifactsConfig.load(yaml("""
                artifacts:
                  return:
                    delay-seconds: 0
                """));
        assertEquals(0L, immediate.returnDelaySeconds());

        ArtifactsConfig.LoadResult negative = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  return:
                    delay-seconds: -1
                """));
        assertFalse(negative.valid());
        assertTrue(negative.issues().stream().anyMatch(i -> i.contains("delay-seconds")));

        ArtifactsConfig.LoadResult overMax = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  return:
                    delay-seconds: 2592001
                """));
        assertFalse(overMax.valid());
    }

    // ------------------------------------------------------------------
    // Rebellion event toggle + trigger scope
    // ------------------------------------------------------------------

    @Test
    void rebellionEventCanBeDisabledByConfig() {
        ArtifactsConfig config = ArtifactsConfig.load(yaml("""
                artifacts:
                  rebellion-event:
                    enabled: false
                """));
        assertFalse(config.rebellionEventEnabled());
    }

    @Test
    void teamWideTriggerScopeIsALoudUnimplementedRefusal() {
        // §5.5: a team-wide trigger must be a separate explicit configuration
        // option — refusing loudly is the honest state, never a silent stretch.
        ArtifactsConfig.LoadResult result = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  rebellion-event:
                    trigger-scope: TEAM
                """));
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(
                i -> i.contains("trigger-scope") && i.contains("HOLDER")));
        assertThrows(IllegalStateException.class,
                () -> ArtifactsConfig.load(yaml("""
                        artifacts:
                          rebellion-event:
                            trigger-scope: TEAM
                        """)));
    }

    @Test
    void holderScopeIsCaseInsensitive() {
        ArtifactsConfig config = ArtifactsConfig.load(yaml("""
                artifacts:
                  rebellion-event:
                    trigger-scope: holder
                """));
        assertTrue(config.rebellionEventEnabled());
    }

    // ------------------------------------------------------------------
    // Controller sections (ADR-0015): materials, sweep, event, purge
    // ------------------------------------------------------------------

    @Test
    void controllerDefaultsMatchTheShippedFile() {
        ArtifactsConfig shipped = ArtifactsConfig.load(shipped());
        ArtifactsConfig defaults = ArtifactsConfig.defaults();
        assertEquals(shipped.itemMaterials(), defaults.itemMaterials());
        assertEquals(shipped.sweepIntervalSeconds(), defaults.sweepIntervalSeconds());
        assertEquals(shipped.eventDurationMinutes(), defaults.eventDurationMinutes());
        assertEquals(shipped.eventReminderMinutes(), defaults.eventReminderMinutes());
        assertEquals(shipped.purge(), defaults.purge());
    }

    @Test
    void itemMaterialsAreValidatedPerDimension() {
        ArtifactsConfig.LoadResult garbage = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  items:
                    nether:
                      material: NOT_A_MATERIAL
                """));
        assertFalse(garbage.valid());
        assertTrue(garbage.issues().stream().anyMatch(
                i -> i.contains("artifacts.items.nether.material")));

        ArtifactsConfig.LoadResult air = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  items:
                    end:
                      material: AIR
                """));
        assertFalse(air.valid());
        assertTrue(air.issues().stream().anyMatch(
                i -> i.contains("artifacts.items.end.material")));

        // A legal override validates and only for that dimension.
        ArtifactsConfig.LoadResult legal = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  items:
                    overworld:
                      material: NETHERITE_INGOT
                """));
        assertTrue(legal.valid(), () -> legal.issues().toString());
        assertEquals(org.bukkit.Material.NETHERITE_INGOT,
                legal.config().itemMaterial(ArtifactKey.OVERWORLD));
    }

    @Test
    void sweepIntervalIsBounded() {
        for (int bad : new int[] {4, 601, -1}) {
            ArtifactsConfig.LoadResult result = ArtifactsConfig.loadResult(yaml(
                    "artifacts:\n  sweep:\n    interval-seconds: " + bad + "\n"));
            assertFalse(result.valid(), "sweep " + bad + " must be refused");
            assertTrue(result.issues().stream().anyMatch(
                    i -> i.contains("sweep.interval-seconds")));
        }
        ArtifactsConfig.LoadResult ok = ArtifactsConfig.loadResult(yaml(
                "artifacts:\n  sweep:\n    interval-seconds: 120\n"));
        assertTrue(ok.valid(), () -> ok.issues().toString());
        assertEquals(120, ok.config().sweepIntervalSeconds());
    }

    @Test
    void eventDurationAndReminderAreBounded() {
        ArtifactsConfig.LoadResult bad = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  event:
                    duration-minutes: 4
                    reminder-minutes: -1
                """));
        assertFalse(bad.valid());
        assertTrue(bad.issues().stream().anyMatch(i -> i.contains("duration-minutes")));
        assertTrue(bad.issues().stream().anyMatch(i -> i.contains("reminder-minutes")));

        ArtifactsConfig.LoadResult off = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  event:
                    reminder-minutes: 0
                """));
        assertTrue(off.valid(), () -> off.issues().toString());
        assertEquals(0, off.config().eventReminderMinutes(), "0 disables the reminder");
    }

    @Test
    void purgeShipsDisabledAndOnlineOnly() {
        ArtifactsConfig defaults = ArtifactsConfig.defaults();
        assertFalse(defaults.purge().enabled(), "the purge must ship default-off");
        assertTrue(defaults.purge().targetsOnline());
        assertTrue(defaults.purge().spareHolder(), "the holder is spared by default");
    }

    @Test
    void storedTargetSetIsALoudUnimplementedRefusal() {
        ArtifactsConfig.LoadResult stored = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  purge:
                    enabled: true
                    target-set: STORED
                """));
        assertFalse(stored.valid());
        assertTrue(stored.issues().stream().anyMatch(
                i -> i.contains("purge.target-set") && i.contains("STORED")),
                "STORED must be refused loudly: " + stored.issues());
    }

    @Test
    void purgeDelaysAndWarningAreBounded() {
        ArtifactsConfig.LoadResult bad = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  purge:
                    min-delay-minutes: -5
                    warning-seconds: 2
                """));
        assertFalse(bad.valid());
        assertTrue(bad.issues().stream().anyMatch(i -> i.contains("min-delay-minutes")));
        assertTrue(bad.issues().stream().anyMatch(i -> i.contains("warning-seconds")));

        ArtifactsConfig.LoadResult ok = ArtifactsConfig.loadResult(yaml("""
                artifacts:
                  purge:
                    enabled: true
                    min-delay-minutes: 0
                    warning-seconds: 600
                """));
        assertTrue(ok.valid(), () -> ok.issues().toString());
        assertTrue(ok.config().purge().enabled());
    }
}
