package net.zaminmc.rebellion.alchemy;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One player-defined auto-consumption rule row (ADR-0019 §4): the chosen
 * potion, the trigger that fires it, the policy-respecting cooldown, the
 * deterministic conflict order and the storage requirement. Live trigger
 * evaluation is controller-phase; every bound here was validated against the
 * operator policy at define time and the cooldown authority
 * ({@code last_fired_utc}) is stamped only by a committed execution.
 *
 * @param ruleId server-issued idempotency key (controller-supplied UUID)
 * @param playerUuid the owning player (rules are per-player and opt-in)
 * @param trigger when this rule wants to fire
 * @param potionType the chosen potion (validated against the policy
 *        whitelist; stored as its enum name)
 * @param threshold the HEALTH_BELOW percent bound (null for every other
 *        trigger)
 * @param cooldownSeconds >= the policy floor at define time
 * @param priority lower = evaluated earlier; ties broken by createdUtc then
 *        ruleId (deterministic conflict handling)
 * @param storage where the potion may be drawn from
 * @param enabled the toggle (toggle is an audited ownership-guarded CAS)
 * @param createdUtc define time
 * @param updatedUtc last mutation time
 * @param removedUtc removal stamp (null while live; removed rows stop
 *        resolving but are kept for forensics)
 * @param lastFiredUtc the cooldown authority — stamped only by committed
 *        executions
 */
public record AutoRule(UUID ruleId, UUID playerUuid, AutoTrigger trigger,
        String potionType, @Nullable Integer threshold, int cooldownSeconds,
        int priority, StorageScope storage, boolean enabled, long createdUtc,
        long updatedUtc, @Nullable Long removedUtc, @Nullable Long lastFiredUtc) {

    /** @return true while the rule still resolves (not removed). */
    public boolean live() {
        return removedUtc == null;
    }

    /** @return true when the rule can fire at the given time (cooldown
     *  authority over the stamped last-fires). */
    public boolean offCooldown(long nowUtc) {
        return lastFiredUtc == null
                || lastFiredUtc + cooldownSeconds * 1000L <= nowUtc;
    }
}
