package net.zaminmc.rebellion.rpg;

import java.util.UUID;

/**
 * One tracking-session row (ADR-0016 §3): the durable outcome of a
 * successfully cast tracking ritual. Exactly one session exists per
 * committed claim (UNIQUE {@code claim_id}).
 *
 * <p>Expiry is <em>derived</em>: a session resolves while
 * {@code nowUtc < expiresUtc} and {@code revokedUtc == null} — no sweep
 * flips a state column, so an expired session stops resolving the moment
 * time passes it, crash or no crash. A counter-ritual mask does not touch
 * this row at all: masks live on {@code rb_rpg_effects} and degrade the
 * <em>effective</em> precision at resolution time (service composition,
 * never a state edit).</p>
 *
 * @param sessionId durable session id
 * @param claimId the committing claim (UNIQUE)
 * @param casterUuid the tracking player
 * @param targetUuid the resolved target (offline players are legal
 *        targets; unknown names never reach persistence)
 * @param precision the tier the config granted at cast time — the ceiling
 *        of what any signal may ever reveal for this session
 * @param beganUtc cast time
 * @param expiresUtc beganUtc + configured duration
 * @param revokedUtc admin/recovery revocation stamp (null = not revoked)
 * @param revokeReason short revocation reason (forensics)
 */
public record TrackingSession(UUID sessionId, UUID claimId, UUID casterUuid,
        UUID targetUuid, Precision precision, long beganUtc, long expiresUtc,
        Long revokedUtc, String revokeReason) {

    /** @return true while unrevoked and unexpired at the given time. */
    public boolean activeAt(long nowUtc) {
        return revokedUtc == null && nowUtc < expiresUtc && beganUtc <= nowUtc;
    }
}
