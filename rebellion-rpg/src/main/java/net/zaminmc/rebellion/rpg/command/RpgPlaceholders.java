package net.zaminmc.rebellion.rpg.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
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
 *   <li>{@code %rebellion_ritual_<key>_discovered%} — the operator-localized
 *       discovery-state wording for knowledge key {@code <key>}: the same
 *       {@code ritual.list.state.known}/{@code unknown} vocabulary that
 *       {@code /ritual list} renders, MiniMessage-colored so menus and PAPI
 *       consumers that parse MiniMessage show it as designed. Before session
 *       15 this returned a bare {@code true}/{@code false}.</li>
 * </ul>
 *
 * <p>Contract compliance (PlaceholderContributor): resolution is a pure
 * in-memory read — the viewer's discovery keys are loaded once per join
 * into {@link #cache} (and refreshed by every granting path), never per
 * placeholder request; unknown parameters, a missing catalog template or a
 * not-yet-loaded viewer return null (PlaceholderAPI and the menu engine
 * both leave the text untouched in that case); only the viewer's own
 * knowledge is ever exposed.</p>
 */
public final class RpgPlaceholders implements PlaceholderContributor {

    /** Viewer uuid -> the discovery keys they know (loaded on join). */
    private final Map<UUID, CompletableFuture<Set<String>>> cache =
            new ConcurrentHashMap<>();
    private final RpgService service;
    private final MessageCatalog messages;

    public RpgPlaceholders(@NotNull RpgService service,
            @NotNull MessageCatalog messages) {
        this.service = service;
        this.messages = messages;
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
        String key = parseKey(params);
        if (key == null) {
            return null;
        }
        CompletableFuture<Set<String>> known = cache.get(player.getUniqueId());
        if (known == null || !known.isDone() || known.isCompletedExceptionally()) {
            return null; // not loaded yet: PlaceholderAPI leaves the text untouched
        }
        return stateWord(known.join().contains(key), messages);
    }

    /**
     * Parameter shape: {@code ritual_<key>_discovered} with the knowledge key
     * in {@code [a-z0-9_]{1,32}}. Pure; anything else is null.
     */
    static @Nullable String parseKey(@NotNull String params) {
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
        return key;
    }

    /**
     * The localized state wording a discovered/undiscovered key renders as.
     * Pure; a missing catalog template yields null (the token stays raw
     * text instead of lying about the viewer's state).
     */
    static @Nullable String stateWord(boolean discovered,
            @NotNull MessageCatalog messages) {
        return messages.template(discovered
                ? "ritual.list.state.known" : "ritual.list.state.unknown");
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
