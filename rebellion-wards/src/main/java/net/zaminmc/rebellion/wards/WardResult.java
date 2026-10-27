package net.zaminmc.rebellion.wards;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Result of a ward mutation: a typed outcome, a human-readable detail for
 * the command layer, and the affected ward when one exists. Mirrors the
 * teams module's {@code TeamResult} contract so command rendering stays
 * mechanical.
 */
public record WardResult(@NotNull WardOutcome outcome, @Nullable String detail,
        @Nullable Ward ward) {

    public static WardResult ok(Ward ward) {
        return new WardResult(WardOutcome.OK, null, ward);
    }

    public static WardResult failure(WardOutcome outcome, String detail) {
        return new WardResult(outcome, detail, null);
    }

    public boolean success() {
        return outcome == WardOutcome.OK;
    }
}
