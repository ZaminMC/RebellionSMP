package net.zaminmc.rebellion.core.integration;

import java.util.ArrayList;
import java.util.List;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Core-level placeholder parameters plus routing to every registered
 * {@link PlaceholderContributor}.
 *
 * <p>Contributors are looked up through the ServicesManager at request time —
 * a map lookup, no cached plugin state — so modules can register and
 * unregister freely. Unknown parameters return null (PlaceholderAPI leaves
 * the text untouched). No contributor or core lookup may touch the
 * database; all read in-memory runtime state only.</p>
 */
public final class CorePlaceholders implements PlaceholderContributor {

    private final String version;
    private final java.util.function.Supplier<String> healthyRatio;
    private final java.util.function.Supplier<String> backend;
    private final java.util.function.Supplier<String> degradedModules;

    public CorePlaceholders(String version,
            java.util.function.Supplier<String> healthyRatio,
            java.util.function.Supplier<String> backend,
            java.util.function.Supplier<String> degradedModules) {
        this.version = version;
        this.healthyRatio = healthyRatio;
        this.backend = backend;
        this.degradedModules = degradedModules;
    }

    @Override
    public @Nullable String resolve(@NotNull String params) {
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "version" -> version;
            case "modules_healthy" -> healthyRatio.get();
            case "database_backend" -> backend.get();
            case "modules_degraded" -> degradedModules.get();
            default -> route(params);
        };
    }

    /**
     * First non-null answer from the registered module contributors. This
     * instance is excluded to avoid self-recursion; contributors are ordered
     * by registration and their parameter names must not collide.
     */
    private String route(String params) {
        for (PlaceholderContributor contributor : contributors()) {
            if (contributor == this) {
                continue;
            }
            String answer = contributor.resolve(params);
            if (answer != null) {
                return answer;
            }
        }
        return null;
    }

    private List<PlaceholderContributor> contributors() {
        List<PlaceholderContributor> out = new ArrayList<>();
        for (var registration : Bukkit.getServicesManager()
                .getRegistrations(PlaceholderContributor.class)) {
            out.add(registration.getProvider());
        }
        return out;
    }
}
