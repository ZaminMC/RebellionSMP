package net.zaminmc.rebellion.core.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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
 *
 * <p>Routing is <em>viewer-threaded</em>: a request scoped to a player calls
 * {@link PlaceholderContributor#resolveFor} with that player, so module
 * parameters like {@code ritual_<key>_discovered} (which depend on who is
 * looking) actually resolve. Player-independent {@link #resolve} routes with
 * a null viewer, which viewer-scoped contributors answer with null by
 * contract. This instance is excluded from its own routing to avoid
 * self-recursion; contributors are ordered by registration and their
 * parameter names must not collide.</p>
 */
public final class CorePlaceholders implements PlaceholderContributor {

    private final String version;
    private final Supplier<String> healthyRatio;
    private final Supplier<String> backend;
    private final Supplier<String> degradedModules;
    private final Supplier<List<PlaceholderContributor>> contributors;

    public CorePlaceholders(String version,
            Supplier<String> healthyRatio,
            Supplier<String> backend,
            Supplier<String> degradedModules) {
        this(version, healthyRatio, backend, degradedModules,
                CorePlaceholders::registeredContributors);
    }

    /** Test/embedder variant with an explicit contributor source (no Bukkit). */
    CorePlaceholders(String version,
            Supplier<String> healthyRatio,
            Supplier<String> backend,
            Supplier<String> degradedModules,
            Supplier<List<PlaceholderContributor>> contributors) {
        this.version = version;
        this.healthyRatio = healthyRatio;
        this.backend = backend;
        this.degradedModules = degradedModules;
        this.contributors = contributors;
    }

    @Override
    public @Nullable String resolve(@NotNull String params) {
        String coreAnswer = coreParameter(params);
        return coreAnswer != null ? coreAnswer : route(null, params);
    }

    @Override
    public @Nullable String resolveFor(@Nullable Player player, @NotNull String params) {
        String coreAnswer = coreParameter(params);
        return coreAnswer != null ? coreAnswer : route(player, params);
    }

    /** Core's own player-independent parameters, shared by both entries. */
    private String coreParameter(String params) {
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "version" -> version;
            case "modules_healthy" -> healthyRatio.get();
            case "database_backend" -> backend.get();
            case "modules_degraded" -> degradedModules.get();
            default -> null;
        };
    }

    /**
     * First non-null answer from the registered module contributors, asked
     * with the requesting viewer (null for player-independent requests).
     */
    private String route(Player player, String params) {
        for (PlaceholderContributor contributor : contributors.get()) {
            if (contributor == this) {
                continue;
            }
            String answer = contributor.resolveFor(player, params);
            if (answer != null) {
                return answer;
            }
        }
        return null;
    }

    private static List<PlaceholderContributor> registeredContributors() {
        List<PlaceholderContributor> out = new ArrayList<>();
        for (var registration : Bukkit.getServicesManager()
                .getRegistrations(PlaceholderContributor.class)) {
            out.add(registration.getProvider());
        }
        return out;
    }
}
