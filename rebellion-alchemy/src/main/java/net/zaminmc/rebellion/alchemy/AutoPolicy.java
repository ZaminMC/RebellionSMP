package net.zaminmc.rebellion.alchemy;

import java.util.List;
import java.util.Map;
import org.bukkit.potion.PotionType;

/**
 * The operator's auto-consumption server policy (ADR-0019 §4/§5, config
 * family {@code alchemy.auto-rules}) — the master prompt's "whitelist of
 * allowed triggers/effects" and the anti-bot floor, all enforced at rule
 * definition time and re-read on every mutation:
 *
 * <ul>
 *   <li>per-trigger enable flags (nothing player-usable unless enabled);</li>
 *   <li>HEALTH_BELOW threshold bounds (a rule may only pick a percent inside
 *       them);</li>
 *   <li>{@code minCooldownSeconds} — the floor every player rule must
 *       respect ("never make an infinitely replenishing potion bot");</li>
 *   <li>the priority range;</li>
 *   <li>{@code maxRulesPerPlayer} — the per-player cap;</li>
 *   <li>{@code allowedPotionTypes} — the whitelist of potions a rule may
 *       reference (PotionType enum-validated at load).</li>
 * </ul>
 *
 * @param triggers enable flag per trigger (every known trigger present)
 * @param healthMin HEALTH_BELOW bound: lowest legal percent
 * @param healthMax HEALTH_BELOW bound: highest legal percent
 * @param minCooldownSeconds the per-rule cooldown floor
 * @param minPriority / {@code maxPriority} the inclusive priority range
 * @param maxRulesPerPlayer the live-rule cap per player
 * @param allowedPotionTypes the potion whitelist (never empty)
 */
public record AutoPolicy(Map<AutoTrigger, Boolean> triggers, int healthMin,
        int healthMax, int minCooldownSeconds, int minPriority,
        int maxPriority, int maxRulesPerPlayer,
        List<PotionType> allowedPotionTypes) {

    public static final int MAX_COOLDOWN_SECONDS = 86_400;
    public static final int ABSOLUTE_MIN_PRIORITY = 1;
    public static final int ABSOLUTE_MAX_PRIORITY = 100;
    public static final int ABSOLUTE_MAX_RULES = 10;

    public AutoPolicy {
        triggers = Map.copyOf(triggers);
        allowedPotionTypes = List.copyOf(allowedPotionTypes);
    }

    /** @return whether the trigger is player-usable under this policy. */
    public boolean triggerEnabled(AutoTrigger trigger) {
        return triggers.getOrDefault(trigger, false);
    }

    /** @return true when {@code percent} is inside the HEALTH_BELOW bounds. */
    public boolean healthThresholdLegal(int percent) {
        return percent >= healthMin && percent <= healthMax;
    }

    /** @return true when {@code cooldownSeconds} respects the floor. */
    public boolean cooldownLegal(int cooldownSeconds) {
        return cooldownSeconds >= minCooldownSeconds
                && cooldownSeconds <= MAX_COOLDOWN_SECONDS;
    }

    /** @return true when {@code priority} is inside the legal range. */
    public boolean priorityLegal(int priority) {
        return priority >= minPriority && priority <= maxPriority;
    }

    /** @return true when the potion type is whitelisted. */
    public boolean potionAllowed(PotionType type) {
        return allowedPotionTypes.contains(type);
    }
}
