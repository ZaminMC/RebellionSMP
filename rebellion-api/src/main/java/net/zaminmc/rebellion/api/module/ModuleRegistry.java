package net.zaminmc.rebellion.api.module;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry of module health states, owned and implemented by RebellionCore.
 *
 * <p>Every feature module registers itself in {@code onEnable} before doing any
 * work, transitions to {@link ModuleState#OPERATIONAL} when ready, and updates to
 * {@link ModuleState#DEGRADED} or {@link ModuleState#FAILED} when a dependency
 * breaks at runtime. {@code DISABLED} is reported from {@code onDisable}.</p>
 *
 * <p>Implementations must be safe to call from any thread; updates are applied
 * atomically per module.</p>
 */
public interface ModuleRegistry {

    /** Registers a module as PENDING. Second registration of the same key is ignored. */
    void register(ModuleDescriptor descriptor);

    /** Atomically replaces the health snapshot of a registered module. */
    void update(String moduleKey, ModuleState state, String detail);

    /** Removes a module from the registry (used at disable time). */
    void unregister(String moduleKey);

    /** @return current health snapshot, or empty when not registered. */
    Optional<ModuleHealth> health(String moduleKey);

    /** @return snapshot of all registered modules, never null, unordered copy. */
    Collection<ModuleHealth> snapshot();
}
