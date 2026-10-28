package net.zaminmc.rebellion.rpg.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import net.zaminmc.rebellion.rpg.Discovery;
import net.zaminmc.rebellion.rpg.RpgService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionRPG's contribution to the shared {@code rebellion} expansion
 * (master prompt §10):
 *
 * <ul>
 *   <li>{@code %rebellion_ritual_<key>_discovered%} — {@code true}/
 *       {@code false}: has the viewer discovered knowledge key
 *       {@code <key>}?</li>
 * </ul>
 *
 * <p>Contract compliance (PlaceholderContributor): resolution is a pure
 * in-memory read — the viewer's discovery keys are loaded once per join
 * into {@link #cache} (and refreshed by every granting path), never per
 * placeholder request; unknown parameters return null; only the viewer's
 * own knowledge is ever exposed.</p>
 */
public final class RpgPlaceholders implements PlaceholderContributor {

    /** Viewer uuid -> the discovery keys they know (loaded on join). */
    private final Map<UUID, CompletableFuture<Set<String>>> cache =
            new ConcurrentHashMap<>();
    private final RpgService service;

    public RpgPlaceholders(@NotNull RpgService service) {
        this.service = service;
    }

    @Override
    public @Nullable String resolve(@NotNull String params) {
        return null; // every RPG placeholder is viewer-scoped
    }

    @Override
    public @Nullable String resolveFor(@Nullable Player player, @NotNull String params) {
        if (player == null) {
            return null;
        }
        String lower = params.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("ritual_") || !lower.endsWith("_discovered")) {
            return null;
        }
        String key = params.substring("ritual_".length(),
                params.length() - "_discovered".length())
                .toLowerCase(Locale.ROOT);
        if (key.isEmpty() || !key.matches("[a-z0-9_]{1,32}")) {
            return null;
        }
        CompletableFuture<Set<String>> known = cache.get(player.getUniqueId());
        if (known == null || !known.isDone() || known.isCompletedExceptionally()) {
            return null; // not loaded yet: PlaceholderAPI leaves the text untouched
        }
        return String.valueOf(known.join().contains(key));
    }

    /** Loads (or reloads) one viewer's discovery set (join + grant paths). */
    public void load(@NotNull UUID player) {
        cache.put(player, service.discoveries(player).thenApply(rows -> rows.stream()
                .map(Discovery::key)
                .collect(Collectors.toUnmodifiableSet())));
    }

    /** Drops the viewer's cache entry (quit). */
    public void evict(@NotNull UUID player) {
        cache.remove(player);
    }

    /** Loads every online viewer (module enable after a reload). */
    public void loadAllOnline(@NotNull List<Player> online) {
        online.forEach(p -> load(p.getUniqueId()));
    }
}
