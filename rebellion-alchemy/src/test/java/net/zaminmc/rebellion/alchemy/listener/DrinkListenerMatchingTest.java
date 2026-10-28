package net.zaminmc.rebellion.alchemy.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import net.zaminmc.rebellion.alchemy.AlchemyConfig;
import net.zaminmc.rebellion.alchemy.CostItem;
import net.zaminmc.rebellion.alchemy.UnlockDefinition;
import net.zaminmc.rebellion.alchemy.UnlockKind;
import org.bukkit.Material;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure POWER-unlock matching rule (ADR-0020 §2): strongest
 * amplifier grant wins, ties by the longer duration grant, disabled and
 * unowned and wrong-type definitions never match. Deterministic when
 * operators define overlapping unlocks.
 */
final class DrinkListenerMatchingTest {

    private static UnlockDefinition power(String key, PotionType type,
            int amplifier, double multiplier, boolean enabled) {
        return new UnlockDefinition(key, enabled, UnlockKind.POWER, 1,
                java.util.List.of(), java.util.List.of(new CostItem(
                        Material.AMETHYST_SHARD, 1)),
                java.util.Optional.of(type), amplifier, multiplier, 0.0, 0, 0);
    }

    private static AlchemyConfig config(UnlockDefinition... definitions) {
        var unlocks = new java.util.LinkedHashMap<String, UnlockDefinition>();
        for (UnlockDefinition definition : definitions) {
            unlocks.put(definition.key(), definition);
        }
        return new AlchemyConfig(AlchemyConfig.defaults().brewing(), unlocks,
                AlchemyConfig.defaults().bags(), AlchemyConfig.defaults().autoPolicy());
    }

    @Test
    void matchingNeedsOwnershipEnablementAndType() {
        UnlockDefinition swiftness = power("boost", PotionType.SWIFTNESS, 1, 1.5,
                true);
        AlchemyConfig cfg = config(swiftness);
        assertEquals(swiftness, DrinkListener.matching(cfg,
                Set.of("boost"), PotionType.SWIFTNESS));
        assertNull(DrinkListener.matching(cfg, Set.of(), PotionType.SWIFTNESS),
                "not owned");
        assertNull(DrinkListener.matching(cfg, Set.of("boost"), PotionType.HEALING),
                "wrong type");
        assertNull(DrinkListener.matching(cfg, Set.of("other"), PotionType.HEALING),
                "owned key belongs to another definition");
    }

    @Test
    void disabledDefinitionsNeverBoost() {
        UnlockDefinition disabled = power("off", PotionType.SWIFTNESS, 1, 1.5,
                false);
        assertNull(DrinkListener.matching(config(disabled), Set.of("off"),
                PotionType.SWIFTNESS));
    }

    @Test
    void efficiencyUnlocksNeverBoost() {
        var efficiency = new UnlockDefinition("frugal", true, UnlockKind.EFFICIENCY,
                1, java.util.List.of(), java.util.List.of(new CostItem(
                        Material.GOLD_INGOT, 1)), java.util.Optional.empty(),
                0, 1.0, 0.10, 0, 0);
        assertNull(DrinkListener.matching(config(efficiency), Set.of("frugal"),
                PotionType.SWIFTNESS), "efficiency has no combat strength - by shape");
    }

    @Test
    void strongestAmplifierWinsTiesByDuration() {
        UnlockDefinition weak = power("weak", PotionType.SWIFTNESS, 1, 2.0, true);
        UnlockDefinition strong = power("strong", PotionType.SWIFTNESS, 2, 1.0,
                true);
        UnlockDefinition longer = power("longer", PotionType.SWIFTNESS, 2, 3.0,
                true);
        AlchemyConfig cfg = config(weak, strong, longer);
        assertEquals(strong, DrinkListener.matching(cfg,
                Set.of("weak", "strong"), PotionType.SWIFTNESS),
                "amplifier beats duration");
        assertEquals(longer, DrinkListener.matching(cfg,
                Set.of("strong", "longer"), PotionType.SWIFTNESS),
                "amplifier tie broken by the longer duration grant");
    }

    @Test
    void powerGrantWithoutPotionTypeNeverMatches() {
        var typeless = new UnlockDefinition("weird", true, UnlockKind.POWER, 1,
                java.util.List.of(), java.util.List.of(new CostItem(
                        Material.DIAMOND, 1)), java.util.Optional.empty(),
                1, 1.5, 0.0, 0, 0);
        assertNull(DrinkListener.matching(config(typeless), Set.of("weird"),
                PotionType.SWIFTNESS), "a POWER grant without a potion type is inert");
    }
}
