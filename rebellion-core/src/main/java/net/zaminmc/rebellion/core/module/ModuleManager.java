package net.zaminmc.rebellion.core.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleHealth;
import net.zaminmc.rebellion.api.module.ModuleRegistry;
import net.zaminmc.rebellion.api.module.ModuleState;

/**
 * Thread-safe implementation of the {@link ModuleRegistry} contract, owned by
 * Core and registered with the ServicesManager. Feature modules update their
 * state from any thread; snapshots are always consistent copies.
 */
public final class ModuleManager implements ModuleRegistry {

    private final Map<String, ModuleHealth> modules = new ConcurrentHashMap<>();

    @Override
    public void register(ModuleDescriptor descriptor) {
        modules.computeIfAbsent(descriptor.moduleKey(),
                k -> new ModuleHealth(descriptor, ModuleState.PENDING, "", java.time.Instant.now()));
    }

    @Override
    public void update(String moduleKey, ModuleState state, String detail) {
        modules.computeIfPresent(moduleKey, (k, health) -> new ModuleHealth(
                health.descriptor(), state, detail == null ? "" : detail, java.time.Instant.now()));
    }

    @Override
    public void unregister(String moduleKey) {
        modules.remove(moduleKey);
    }

    @Override
    public Optional<ModuleHealth> health(String moduleKey) {
        return Optional.ofNullable(modules.get(moduleKey));
    }

    @Override
    public Collection<ModuleHealth> snapshot() {
        return List.copyOf(modules.values());
    }

    /** @return modules that are not in a healthy state, for status output. */
    public List<ModuleHealth> unhealthy() {
        List<ModuleHealth> out = new ArrayList<>();
        for (ModuleHealth h : modules.values()) {
            if (h.state() != ModuleState.OPERATIONAL) {
                out.add(h);
            }
        }
        return out;
    }

    /** @return {@code "3/4"} style count of operational modules. */
    public String healthyRatio() {
        int total = modules.size();
        long healthy = modules.values().stream().filter(ModuleHealth::operational).count();
        return healthy + "/" + total;
    }
}
