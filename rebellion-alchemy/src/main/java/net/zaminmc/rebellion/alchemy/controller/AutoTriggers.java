package net.zaminmc.rebellion.alchemy.controller;

import org.bukkit.World.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * The pure live-condition predicates behind auto-consumption rule
 * evaluation (ADR-0020 §4). Everything decidable without a live entity is
 * here and serverless-tested; the controller passes in exactly the values
 * the listeners read off the player. FALLING ships policy-disabled by
 * default (ADR-0019), but its predicate is complete because the operator
 * whitelist can turn it on without a code change.
 */
public final class AutoTriggers {

    private AutoTriggers() {
    }

    /**
     * HEALTH_BELOW: the player's health percentage dropped to or below the
     * rule's threshold percent.
     *
     * @param health current health (half-hearts)
     * @param maxHealth the attribute ceiling (never 0 — callers guard)
     * @param thresholdPercent the rule's bound, 1..99 (config-validated)
     */
    public static boolean healthBelow(double health, double maxHealth,
            int thresholdPercent) {
        if (maxHealth <= 0 || thresholdPercent < 1 || thresholdPercent > 99) {
            return false;
        }
        double percent = health * 100.0 / maxHealth;
        return percent <= thresholdPercent;
    }

    /**
     * BURNING: any active fire ticks. The predicate stays boolean — the
     * "burning" state a player would name, not a tick-count threshold.
     */
    public static boolean burning(int fireTicks) {
        return fireTicks > 0;
    }

    /**
     * NETHER_ENTRY: the player currently stands in a Nether-environment
     * world. The periodic evaluator reads it as a *state* (standing in the
     * Nether), which under the cooldown authority means one trigger per
     * cooldown while the player remains — never a loop.
     */
    public static boolean inNether(@Nullable Environment environment) {
        return environment == Environment.NETHER;
    }

    /**
     * FALLING: airborne and accumulating fall distance at or beyond the
     * configured threshold blocks (3..100, controller config). Players on
     * ladders/water/ground never fire ({@code onGround} false is
     * required).
     */
    public static boolean falling(float fallDistance, boolean onGround,
            int thresholdBlocks) {
        return !onGround && fallDistance >= thresholdBlocks;
    }
}
