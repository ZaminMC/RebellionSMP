package net.zaminmc.rebellion.alchemy;

import java.util.UUID;

/**
 * One alchemy claim row (ADR-0019 §3): the transactional anchor that makes
 * "atomic potion/cost consumption and safe fallback" honest across the
 * inventory/database boundary. The controller removes physical items only
 * while a live RESERVED claim exists; commit all-or-none CASes the claim
 * together with the effect row (unlock grant or cooldown stamp) and the
 * audit event.
 *
 * @param claimId server-issued idempotency key
 * @param playerUuid the acting player
 * @param purpose what this claim pays for
 * @param subjectKey the unlock key, or the rule id as a string (purpose-
 *        dependent subject)
 * @param state the row state
 * @param digest the controller's snapshot of the exact item set it verified
 *        at reserve time — refund evidence for the crash window
 * @param createdUtc reserve time
 * @param resolvedUtc commit/release time (null while RESERVED)
 */
public record AlchemyClaim(UUID claimId, UUID playerUuid, ClaimPurpose purpose,
        String subjectKey, ClaimState state, String digest, long createdUtc,
        Long resolvedUtc) {
}
