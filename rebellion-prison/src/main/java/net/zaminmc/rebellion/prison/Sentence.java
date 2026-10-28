package net.zaminmc.rebellion.prison;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One imprisonment sentence (ADR-0021). Immutable row snapshot. The
 * {@code live_guard} uniqueness discipline lives in the schema/store; from
 * the record's view a sentence is either live ({@link SentenceState#live()
 * PENDING/JAILED}) or released.
 *
 * <p>The jail anchor is stamped at creation from the operator's configured
 * prison anchor — a config change never rewrites an existing sentence's
 * destination, and the controller phase teleports the player to exactly
 * these coordinates (integers all the way down; the live World object is
 * resolved at teleport time, never persisted).</p>
 */
public record Sentence(@NotNull UUID sentenceId, @NotNull UUID player,
        @NotNull SentenceState state, @NotNull String jailWorld, long jailX,
        long jailY, long jailZ, int jailYaw, int jailPitch, long createdUtc,
        @Nullable Long releasedUtc, @Nullable ReleaseReason releaseReason) {

    /** @return whether this sentence occupies the player's live slot. */
    public boolean live() {
        return state.live();
    }
}
