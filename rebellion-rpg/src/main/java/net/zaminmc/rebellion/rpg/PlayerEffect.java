package net.zaminmc.rebellion.rpg;

import java.util.UUID;

/**
 * One time-bounded player-effect row (ADR-0016 §3): the persistence half
 * of counter-rituals. The in-slice effect key is {@code masking} — an
 * active mask on the <em>target</em> degrades incoming tracking precision
 * by one tier at resolution time. Expiry is derived
 * ({@code expiresUtc > now}); rows are kept after expiry for forensics.
 *
 * <p>Masks do not stack: a second mask for the same player and key
 * extends protection (a new row with a later expiry) rather than
 * compounding — the tracker always retains a floor signal unless the
 * session was granted at {@code DIMENSION}.</p>
 *
 * @param effectId durable effect id
 * @param playerUuid the protected player
 * @param effectKey the effect family ({@code masking})
 * @param grantedUtc grant time
 * @param expiresUtc grant time + configured duration
 * @param grantedBy the counter-ritual caster (null = admin grant)
 */
public record PlayerEffect(UUID effectId, UUID playerUuid, String effectKey,
        long grantedUtc, long expiresUtc, UUID grantedBy) {

    public static final String KEY_MASKING = "masking";

    /** @return true while unexpired at the given time. */
    public boolean activeAt(long nowUtc) {
        return grantedUtc <= nowUtc && nowUtc < expiresUtc;
    }
}
