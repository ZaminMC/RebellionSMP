package net.zaminmc.rebellion.alchemy;

import java.util.UUID;

/**
 * One unlock-ledger row (ADR-0019 §1): a player owns an unlock — a fact,
 * idempotent by the {@code (player_uuid, unlock_key)} primary key, with no
 * UPDATE path. The purchasing claim id is carried for audit.
 *
 * @param playerUuid the owner
 * @param unlockKey the config-defined unlock key
 * @param grantedUtc grant time
 * @param claimId the purchase claim that paid for it (null for admin grants)
 */
public record UnlockGrant(UUID playerUuid, String unlockKey, long grantedUtc,
        UUID claimId) {
}
