package net.zaminmc.rebellion.prison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.zaminmc.rebellion.prison.PrisonConfig.DeathPolicy;
import net.zaminmc.rebellion.prison.PrisonConfig.RehabTask;
import org.junit.jupiter.api.Test;

/**
 * Pins the operator contract of {@link PrisonConfig} (ADR-0021): the
 * death family (score, penalty, hard floor, routing policy, threshold),
 * the release family (exact-decimal factor, multiplier, rounding,
 * min/max clamps), the rehab task family (heal bounds against the
 * active-penalty ceiling, occurrence caps), the jail anchor bounds, and
 * the collect-all issue behavior on invalid documents.
 */
final class PrisonConfigTest {

    @Test
    void defaultsMatchTheProductContract() {
        PrisonConfig config = PrisonConfig.defaults();
        DeathPolicy death = config.death();
        assertEquals(1L, death.scorePerDeath());
        assertEquals(2, death.healthPenaltyPoints());
        assertEquals(6, death.healthFloorPoints());
        assertEquals(20, death.maxHealthPoints());
        assertEquals(14, death.maxActivePenalty());
        assertEquals(DeathPolicy.Route.ALWAYS, death.route());

        assertEquals("1.00", config.release().inflationFactor().toPlainString());
        assertEquals(5, config.release().multiplier());
        assertEquals(0L, config.release().minFeeMinor());
        assertTrue(config.rehab().containsKey("convalescence_rest"));
        assertTrue(config.rehab().containsKey("field_medicine"));
        assertEquals("world_prison", config.anchor().world());
    }

    @Test
    void minimalValidDocumentLoads() {
        PrisonConfig.LoadResult result = PrisonConfig.load("""
                death:
                  score-per-death: 2
                  health-penalty-points: 4
                  health-floor-points: 10
                  max-health-points: 40
                  imprison-route: SCORE_THRESHOLD
                  imprison-threshold: 5
                release:
                  inflation-factor: '1.35'
                  multiplier: 5
                  rounding: HALF_UP
                  min-fee: 100
                  max-fee: 999999
                rehab:
                  tasks:
                    field_work:
                      enabled: true
                      heal-points: 8
                      max-occurrences: 3
                prison:
                  world: jail_world
                  x: -1200
                  y: 70
                  z: 300
                  yaw: -90
                  pitch: 0
                """);
        assertTrue(result.valid(), () -> String.join("; ", result.issues()));
        assertEquals(DeathPolicy.Route.SCORE_THRESHOLD, result.config().death().route());
        assertEquals(30, result.config().death().maxActivePenalty());
        assertEquals("1.35", result.config().release().inflationFactor().toPlainString());
        assertEquals(8, result.config().rehabTask("field_work").healPoints());
        assertEquals(-1200L, result.config().anchor().x());
    }

    @Test
    void emptyDocumentFallsBackToDefaults() {
        PrisonConfig.LoadResult result = PrisonConfig.load("");
        assertTrue(result.valid(), () -> String.join("; ", result.issues()));
        assertEquals(PrisonConfig.defaults(), result.config());
    }

    @Test
    void floorAtOrAboveTheBarIsRejected() {
        assertFalse(PrisonConfig.load("""
                death:
                  health-floor-points: 20
                  max-health-points: 20
                """).valid());
        assertFalse(PrisonConfig.load("""
                death:
                  health-floor-points: 25
                  max-health-points: 20
                """).valid());
    }

    @Test
    void everyInvalidFieldIsCollected() {
        PrisonConfig.LoadResult result = PrisonConfig.load("""
                death:
                  score-per-death: -1
                  health-penalty-points: 5000
                  imprison-route: sometimes
                  imprison-threshold: 0
                release:
                  inflation-factor: '1.1234567'
                  multiplier: 0
                  rounding: SILLY
                  min-fee: 500
                  max-fee: 100
                rehab:
                  tasks:
                    BAD KEY:
                      heal-points: 99
                      max-occurrences: 0
                    valid_key_bad_values:
                      heal-points: 99
                      max-occurrences: 0
                prison:
                  world: '../etc/passwd'
                  x: 40000000
                  y: 5000
                  z: -40000000
                  yaw: 200
                """);
        assertFalse(result.valid());
        String issues = String.join("; ", result.issues());
        assertTrue(issues.contains("score-per-death"), issues);
        assertTrue(issues.contains("health-penalty-points"), issues);
        assertTrue(issues.contains("imprison-route"), issues);
        assertTrue(issues.contains("inflation-factor"), issues);
        assertTrue(issues.contains("multiplier"), issues);
        assertTrue(issues.contains("rounding"), issues);
        assertTrue(issues.contains("max-fee"), issues);
        assertTrue(issues.contains("BAD KEY"), issues);
        assertTrue(issues.contains("heal-points"), issues);
        assertTrue(issues.contains("max-occurrences"), issues);
        assertTrue(issues.contains("prison.world"), issues);
        assertTrue(issues.contains("prison.x"), issues);
        assertTrue(issues.contains("prison.yaw"), issues);
    }

    @Test
    void rehabHealingIsBoundedByTheActivePenaltyCeiling() {
        assertFalse(PrisonConfig.load("""
                death:
                  health-penalty-points: 2
                  health-floor-points: 6
                  max-health-points: 20
                rehab:
                  tasks:
                    miracle_cure:
                      heal-points: 15
                """).valid());
        // ceiling = 14: 15 is refused, 14 is accepted.
        assertTrue(PrisonConfig.load("""
                death:
                  health-penalty-points: 2
                  health-floor-points: 6
                  max-health-points: 20
                rehab:
                  tasks:
                    full_recovery:
                      heal-points: 14
                """).valid());
    }

    @Test
    void negativeAndOversizedFactorsAreRejected() {
        assertFalse(PrisonConfig.load(
                "release:\n  inflation-factor: '-1.5'").valid());
        assertFalse(PrisonConfig.load(
                "release:\n  inflation-factor: '2000000'").valid());
        assertTrue(PrisonConfig.load(
                "release:\n  inflation-factor: '1.000001'").valid());
    }

    @Test
    void maxFeeCannotExceedTheLongSafeLimit() {
        assertFalse(PrisonConfig.load(
                "release:\n  max-fee: 9223372036854775807").valid());
        // The limit keeps clamped math comfortably inside long range.
        assertTrue(PrisonConfig.load(
                "release:\n  max-fee: 4611686018427387904").valid());
    }

    @Test
    void keyGrammarsArePinned() {
        assertTrue(PrisonConfig.EVENT_KEY.matcher("death:tick:100").matches());
        assertTrue(PrisonConfig.EVENT_KEY.matcher("rest-camp:7").matches());
        assertFalse(PrisonConfig.EVENT_KEY.matcher("UPPER").matches());
        assertFalse(PrisonConfig.EVENT_KEY.matcher("with space").matches());

        assertTrue(PrisonConfig.WORLD.matcher("world_prison").matches());
        assertTrue(PrisonConfig.WORLD.matcher("Overworld-Nether").matches());
        assertFalse(PrisonConfig.WORLD.matcher("a b").matches());
        assertFalse(PrisonConfig.WORLD.matcher("a/b").matches());
    }
}
