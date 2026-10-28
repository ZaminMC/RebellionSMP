package net.zaminmc.rebellion.dragons;

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
 * DragonsConfig validation: the lineage cap contract (master prompt §5.6)
 * plus every controller bound (ADR-0008 measured defaults; scan bounds from
 * ADR-0014). The shipped resource file itself is loaded and must validate
 * with zero issues, and the {@link DragonsConfig#defaults} factory must stay
 * in lockstep with it (tests below pin both).
 */
final class DragonsConfigTest {

    private static YamlConfiguration yaml(String body) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(new StringReader(body));
        } catch (Exception e) {
            throw new IllegalStateException("test yaml invalid", e);
        }
        return config;
    }

    /** The actual bundled config.yml (the file the server boots with). */
    private static YamlConfiguration shipped() {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream in = DragonsConfigTest.class
                .getResourceAsStream("/config.yml")) {
            assertNotNull(in, "bundled config.yml must exist in test resources");
            config.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("shipped config invalid", e);
        }
        return config;
    }

    private static void assertNotNull(Object o, String message) {
        assertTrue(o != null, message);
    }

    // ------------------------------------------------------------------
    // Lineage cap (§5.6)
    // ------------------------------------------------------------------

    @Test
    void defaultCapIsTwenty() {
        DragonsConfig config = DragonsConfig.load(yaml("dragons:\n  max-lineages: 20\n"));
        assertEquals(20, config.maxLineages());
        assertEquals(20, config.effectiveMaxLineages());
    }

    @Test
    void missingKeyFallsBackToTheDefault() {
        DragonsConfig config = DragonsConfig.load(yaml("dragons: {}\n"));
        assertEquals(20, config.maxLineages());
    }

    @Test
    void capCanBeConfiguredLower() {
        DragonsConfig config = DragonsConfig.load(yaml("dragons:\n  max-lineages: 5\n"));
        assertEquals(5, config.maxLineages());
        assertEquals(5, config.effectiveMaxLineages());
    }

    @Test
    void capAboveTheCeilingIsALoudLoadError() {
        DragonsConfig.LoadResult result =
                DragonsConfig.loadResult(yaml("dragons:\n  max-lineages: 21\n"));
        assertFalse(result.valid());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).contains("max-lineages"));
        assertTrue(result.issues().get(0).contains("20"),
                "the issue must explain the ceiling");
        assertThrows(IllegalStateException.class,
                () -> DragonsConfig.load(yaml("dragons:\n  max-lineages: 100\n")));
    }

    @Test
    void capBelowOneIsALoudLoadError() {
        DragonsConfig.LoadResult result =
                DragonsConfig.loadResult(yaml("dragons:\n  max-lineages: 0\n"));
        assertFalse(result.valid());
        assertEquals(1, result.issues().size());
    }

    @Test
    void policyCeilingClampsEvenDirectlyConstructedConfigs() {
        // Defense in depth: the service clamps at runtime even when a caller
        // bypasses loader validation entirely (LineageServiceIT exercises the
        // mint guard against this).
        assertEquals(20, LineagePolicy.effectiveCap(30));
        assertEquals(20, LineagePolicy.effectiveCap(Integer.MAX_VALUE));
        assertEquals(7, LineagePolicy.effectiveCap(7));
        assertEquals(1, LineagePolicy.effectiveCap(1));
        assertEquals(1, LineagePolicy.effectiveCap(0), "never clamps below one");
    }

    // ------------------------------------------------------------------
    // Shipped resource + defaults factory
    // ------------------------------------------------------------------

    @Test
    void shippedResourceFileIsValid() {
        DragonsConfig.LoadResult result = DragonsConfig.loadResult(shipped());
        assertTrue(result.valid(), () -> "shipped config issues: " + result.issues());
        assertEquals(20, result.config().effectiveMaxLineages());
    }

    @Test
    void defaultsFactoryMatchesTheShippedFile() {
        DragonsConfig shipped = DragonsConfig.load(shipped());
        DragonsConfig factory = DragonsConfig.defaults(20);
        assertEquals(shipped, factory,
                "defaults() must mirror the shipped config.yml exactly");
    }

    @Test
    void shippedControllerValuesAreTheMeasuredOnes() {
        DragonsConfig config = DragonsConfig.load(shipped());
        // ADR-0008 measured values (E1/E2c/E3/E5/E6) and §5.6 budget.
        assertEquals(200.0, config.hatchHealth(), "vanilla dragon max (E3/E5)");
        assertEquals(25.0, config.pearlHealAmount(), "E5 measured +25");
        assertEquals(30, config.fireballMaxPerWindow(), "§5.6 default budget");
        assertEquals(60, config.fireballWindowSeconds(), "§5.6 rolling window");
        assertFalse(config.rideEnabled(),
                "riding ships disabled until the ADR-0008 ride-feel gate passes");
        assertEquals(1.25, config.waterWeaknessMultiplier(), "slight water penalty");
        // §5.6: pearl heal cooldown exists and is positive; call has one too.
        assertEquals(10_000L, config.pearlHealCooldownMs());
        assertEquals(30_000L, config.callCooldownMs());
        assertTrue(config.victoryObservationEnabled());
    }

    // ------------------------------------------------------------------
    // Controller bounds (loud errors, never silent stretches)
    // ------------------------------------------------------------------

    @Test
    void unknownEggMaterialIsALoudLoadError() {
        DragonsConfig.LoadResult result = DragonsConfig.loadResult(yaml("""
                dragons:
                  max-lineages: 20
                  egg:
                    material: NOT_A_MATERIAL
                """));
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("egg.material")));
    }

    @Test
    void fireballBudgetIsBoundedAbove() {
        DragonsConfig.LoadResult result = DragonsConfig.loadResult(yaml("""
                dragons:
                  pet:
                    attack:
                      max-per-window: 999
                """));
        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("max-per-window")));
    }

    @Test
    void waterWeaknessCannotBecomeAnInstantDeath() {
        DragonsConfig.LoadResult result = DragonsConfig.loadResult(yaml("""
                dragons:
                  pet:
                    water-weakness:
                      damage-multiplier: 25.0
                """));
        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("damage-multiplier")));
        // And below 1.0 (a water BONUS) is equally invalid.
        DragonsConfig.LoadResult buffed = DragonsConfig.loadResult(yaml("""
                dragons:
                  pet:
                    water-weakness:
                      damage-multiplier: 0.5
                """));
        assertFalse(buffed.valid());
    }

    @Test
    void scanBoundsAreRangeChecked() {
        DragonsConfig.LoadResult inverted = DragonsConfig.loadResult(yaml("""
                dragons:
                  boss-victory:
                    scan:
                      radius-min-blocks: 100
                      radius-max-blocks: 50
                """));
        assertFalse(inverted.valid());
        assertTrue(inverted.issues().stream()
                .anyMatch(i -> i.contains("radius-max-blocks")));

        DragonsConfig.LoadResult yBand = DragonsConfig.loadResult(yaml("""
                dragons:
                  boss-victory:
                    scan:
                      y-min: 200
                      y-max: 100
                """));
        assertFalse(yBand.valid());
        assertTrue(yBand.issues().stream().anyMatch(i -> i.contains("y-min")));
    }

    @Test
    void pearlHealAndHatchValuesAreSanityChecked() {
        DragonsConfig.LoadResult negative = DragonsConfig.loadResult(yaml("""
                dragons:
                  pet:
                    pearl-heal:
                      amount: -5
                """));
        assertFalse(negative.valid());

        DragonsConfig.LoadResult hatchHealth = DragonsConfig.loadResult(yaml("""
                dragons:
                  pet:
                    hatch-health: 99999
                """));
        assertFalse(hatchHealth.valid());
        assertTrue(hatchHealth.issues().stream().anyMatch(i -> i.contains("hatch-health")));
    }

    @Test
    void steerAndIntervalValuesAreTickBounded() {
        DragonsConfig.LoadResult zero = DragonsConfig.loadResult(yaml("""
                dragons:
                  pet:
                    ride:
                      steer-interval-ticks: 0
                """));
        assertFalse(zero.valid());
        assertTrue(zero.issues().stream()
                .anyMatch(i -> i.contains("steer-interval-ticks")));
    }
}
