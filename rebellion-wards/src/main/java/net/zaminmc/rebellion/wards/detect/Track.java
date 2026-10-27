package net.zaminmc.rebellion.wards.detect;

/**
 * Enter/leave state for one (player, ward) pair. Immutable; the detector
 * replaces values atomically through {@link DetectionState}.
 *
 * @param inside       whether the player is currently within the radius of
 *                     an ACTIVE ward (the detector's view)
 * @param enteredAtMs  sweep timestamp of the last ENTER transition
 * @param dwellPending true when the ENTER still owes a dwell-delayed commit
 * @param lastAlertAtMs timestamp of the last ENTER alert that actually fired
 *                      (survives LEAVE — that is the re-alert cooldown)
 * @param alerted      whether the enter alert was delivered (suppresses the
 *                     leave notice when it never was)
 */
record Track(boolean inside, long enteredAtMs, boolean dwellPending,
        long lastAlertAtMs, boolean alerted) {

    static final Track OUTSIDE = new Track(false, 0L, false, 0L, false);

    Track enter(long nowMs, boolean dwellPending) {
        return new Track(true, nowMs, dwellPending, lastAlertAtMs, false);
    }

    Track commitAlert(long nowMs) {
        return new Track(inside, enteredAtMs, false, nowMs, true);
    }

    Track suppressDwell() {
        return new Track(inside, enteredAtMs, false, lastAlertAtMs, alerted);
    }

    Track leave() {
        return new Track(false, enteredAtMs, false, lastAlertAtMs, false);
    }

    /** True when the pair is idle and old enough to prune (bounded memory). */
    boolean pruneable(long nowMs, long pruneAfterMs) {
        return !inside && (nowMs - Math.max(lastAlertAtMs, enteredAtMs)) > pruneAfterMs;
    }
}
