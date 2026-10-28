package net.zaminmc.rebellion.alchemy.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.zaminmc.rebellion.alchemy.CostItem;
import net.zaminmc.rebellion.alchemy.UnlockDefinition;
import net.zaminmc.rebellion.alchemy.UnlockKind;
import org.bukkit.Material;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure POWER-boost math (ADR-0020 §2): the unlock scales the
 * potion's own amplifier and duration, both clamped against protocol
 * ceilings. Only primitives here — no PotionEffect, no registry.
 */
final class PotionBoostTest {

    private static UnlockDefinition power(int amplifierBonus,
            double durationMultiplier) {
        return new UnlockDefinition("test_unlock", true, UnlockKind.POWER, 1,
                java.util.List.of(), java.util.List.of(new CostItem(
                        Material.AMETHYST_SHARD, 1)),
                Optional.of(PotionType.SWIFTNESS), amplifierBonus,
                durationMultiplier, 0.0, 0, 0);
    }

    @Test
    void boostScalesAmplifierAndDuration() {
        PotionBoost.Boost boost = PotionBoost.boost(0, 3600, power(1, 1.5));
        assertEquals(1, boost.amplifier());
        assertEquals(5400, boost.durationTicks());
    }

    @Test
    void boostScalesWhatWasInTheBottle() {
        // A strong brewed potion stays strong: amplifier 2 + 1 = 3, the
        // extended duration carries the multiplier.
        PotionBoost.Boost boost = PotionBoost.boost(2, 9600, power(1, 1.5));
        assertEquals(3, boost.amplifier());
        assertEquals(14_400, boost.durationTicks());
    }

    @Test
    void amplifierClampsAtTheProtocolCeiling() {
        PotionBoost.Boost boost = PotionBoost.boost(255, 100, power(4, 1.0));
        assertEquals(PotionBoost.MAX_AMPLIFIER, boost.amplifier());
    }

    @Test
    void durationClampsAgainstOverflow() {
        long huge = Long.MAX_VALUE / 2;
        assertTrue(PotionBoost.boost(0, (int) Math.min(huge, Integer.MAX_VALUE),
                power(0, 5.0)).durationTicks() <= PotionBoost.MAX_DURATION_TICKS);
    }

    @Test
    void zeroBonusIdentityStillRoundTrips() {
        PotionBoost.Boost boost = PotionBoost.boost(3, 1200, power(0, 1.0));
        assertEquals(3, boost.amplifier());
        assertEquals(1200, boost.durationTicks());
    }
}
