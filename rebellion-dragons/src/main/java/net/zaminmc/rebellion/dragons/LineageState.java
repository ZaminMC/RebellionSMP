package net.zaminmc.rebellion.dragons;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * Persistent lifecycle state of a dragon lineage (ADR-0009). One row is one
 * lineage; the state travels in the database and every transition is a
 * compare-and-set guarded by the current state plus the row's live egg or
 * entity identity — never a blind write.
 *
 * <p>EGG states mean the lineage currently exists as its protected egg
 * (held by a player, or parked in the recovery mailbox awaiting
 * claim/route). HATCHED means the lineage is a living pet dragon bound to
 * one entity. RETIRED is the audited, terminal admin outcome — the slot and
 * the gateway anchor stay consumed forever.</p>
 *
 * <p>Structural no-mint contract (master prompt §5.6): no state transition
 * creates a registry row. Rows are created only by gateway issuance; every
 * later transition is an UPDATE.</p>
 */
public enum LineageState {
    /** Egg is (to be) delivered and held by {@code owner}. */
    EGG_HELD,
    /** Egg could not be safely delivered or no valid killer exists; parked
     *  in the protected recovery mailbox, claimant recorded. */
    EGG_RECOVERY,
    /** Living pet dragon bound to {@code entity_uuid}, owned by {@code owner}. */
    HATCHED,
    /** Audited admin retirement; terminal. Slot and gateway stay consumed. */
    RETIRED;

    /** Parses the stored representation; unknown values fail loudly. */
    public static @NotNull LineageState parse(@NotNull String raw) {
        return LineageState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    /** @return true when the lineage currently exists as its protected egg. */
    public boolean isEgg() {
        return this == EGG_HELD || this == EGG_RECOVERY;
    }
}
