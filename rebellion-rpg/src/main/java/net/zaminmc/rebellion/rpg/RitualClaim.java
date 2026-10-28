package net.zaminmc.rebellion.rpg;

import java.util.UUID;

/**
 * One ritual-claim row (ADR-0016 §2): the transactional anchor that makes
 * "ritual state changes and consumed ingredients are committed as one
 * logical operation" honest across the inventory/database boundary.
 *
 * <p>Lifecycle: RESERVED (the controller holds a live reservation and may
 * remove ingredients) → COMMITTED (all-or-none with the ritual's effect
 * rows and the audit event) or RELEASED (refund path: inventory refusal,
 * session-cap auto-release, or the stale-reservation sweep).</p>
 *
 * @param claimId server-issued idempotency key (also the PDC-safe handle
 *        the controller carries between reserve and commit)
 * @param playerUuid the casting player
 * @param ritualKey the config-defined ritual key
 * @param state the row state
 * @param ingredientsDigest the controller's snapshot of the exact
 *        ingredient set it verified at reserve time — refund evidence for
 *        the crash-between-removal-and-commit window
 * @param createdUtc reserve time
 * @param resolvedUtc commit/release time (null while RESERVED)
 */
public record RitualClaim(UUID claimId, UUID playerUuid, String ritualKey,
        ClaimState state, String ingredientsDigest, long createdUtc,
        Long resolvedUtc) {
}
