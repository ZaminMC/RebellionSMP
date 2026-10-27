package net.zaminmc.rebellion.api.module;

import java.time.Instant;

/**
 * Immutable health snapshot of a registered module.
 *
 * <p>{@code detail} must never contain secrets, credentials or raw stack traces —
 * it is shown in {@code /rebellion modules} output and diagnostic reports.
 * Reference an incident ID instead when details are sensitive.</p>
 *
 * @param descriptor  module identity
 * @param state       current lifecycle state
 * @param detail      one-line human-readable explanation, may be empty
 * @param sinceUtc    when this state was entered
 */
public record ModuleHealth(ModuleDescriptor descriptor, ModuleState state, String detail, Instant sinceUtc) {

    public ModuleHealth {
        java.util.Objects.requireNonNull(descriptor, "descriptor");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(sinceUtc, "sinceUtc");
        detail = detail == null ? "" : detail;
    }

    public static ModuleHealth pending(ModuleDescriptor descriptor) {
        return new ModuleHealth(descriptor, ModuleState.PENDING, "", Instant.now());
    }

    public boolean operational() {
        return state == ModuleState.OPERATIONAL;
    }
}
