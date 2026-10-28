package net.zaminmc.rebellion.artifacts;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One persisted Rebellion-event occurrence (ADR-0013 §4): a durable
 * {@code eventId}, the trigger holder who controlled all three artifacts, an
 * auditable initiation reason, and the {@code ACTIVE → COMPLETED} lifecycle.
 * At most one occurrence is active at a time — enforced structurally by the
 * singleton guard row, not by event-layer dedup code.
 *
 * <p>The event is once <em>per occurrence</em>, not once per server: after
 * {@code COMPLETED} the artifacts return to their origins, the world can
 * reconquer, and a new full possession mints a new durable event id. The SMP
 * has no permanent winner (master prompt §5.5).</p>
 */
public record RebellionEvent(
        @NotNull UUID eventId,
        @NotNull UUID triggerHolder,
        @NotNull String reason,
        @NotNull State state,
        long initiatedAtUtc,
        @Nullable Long completedAtUtc) {

    /** Event lifecycle. */
    public enum State {
        ACTIVE,
        COMPLETED;

        /** Parses the stored representation; unknown values fail loudly. */
        public static @NotNull State parse(@NotNull String raw) {
            return State.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }
}
