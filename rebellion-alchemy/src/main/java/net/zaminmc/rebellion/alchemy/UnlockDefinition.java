package net.zaminmc.rebellion.alchemy;

import java.util.List;
import java.util.Optional;
import org.bukkit.potion.PotionType;

/**
 * One unlock definition (ADR-0019 §5, config family {@code alchemy.unlocks}).
 * The two §5.7 paths are structurally distinct: a POWER definition names the
 * potion type it upgrades and by how much; an EFFICIENCY definition names
 * cost/storage/automation conveniences and <em>cannot</em> carry amplifier or
 * duration fields — "efficiency must not quietly add combat strength" is
 * enforced by the record shape itself, not by convention.
 *
 * @param key the config-defined unlock key
 * @param enabled operator toggle
 * @param kind POWER or EFFICIENCY
 * @param requiredLevel the derived brewing level a buyer must hold
 * @param prerequisites other unlock keys that must already be owned
 *        (validated to exist and be cycle-free at config load)
 * @param cost the cost items the controller removes under a reservation
 * @param potionType POWER only: the potion this unlock strengthens
 * @param amplifierBonus POWER only: 0..4 (the vanilla amplifier ceiling)
 * @param durationMultiplier POWER only: 1.0..5.0
 * @param costReduction EFFICIENCY only: 0.0 to below 1.0 (1.0 is rejected —
 *        it would mint free potions)
 * @param bagCapacityBonus EFFICIENCY only: 0..54 extra bag slots
 * @param autoCooldownReduction EFFICIENCY only: 0..3600 s subtracted from
 *        rule cooldowns at evaluation, floored at the policy minimum
 */
public record UnlockDefinition(String key, boolean enabled, UnlockKind kind,
        int requiredLevel, List<String> prerequisites, List<CostItem> cost,
        Optional<PotionType> potionType, int amplifierBonus,
        double durationMultiplier, double costReduction, int bagCapacityBonus,
        int autoCooldownReduction) {

    public static final int MAX_AMPLIFIER_BONUS = 4;
    public static final double MAX_DURATION_MULTIPLIER = 5.0;
    public static final int MAX_BAG_CAPACITY_BONUS = 54;
    public static final int MAX_AUTO_COOLDOWN_REDUCTION = 3600;

    public UnlockDefinition {
        prerequisites = List.copyOf(prerequisites);
        cost = List.copyOf(cost);
    }
}
