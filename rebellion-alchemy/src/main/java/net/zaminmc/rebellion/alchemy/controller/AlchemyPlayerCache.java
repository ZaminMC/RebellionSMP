package net.zaminmc.rebellion.alchemy.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.zaminmc.rebellion.alchemy.AlchemyService;
import net.zaminmc.rebellion.alchemy.AutoRule;
import net.zaminmc.rebellion.alchemy.BrewProgress;
import net.zaminmc.rebellion.alchemy.UnlockGrant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The read-only player mirror (ADR-0020): progress, the owned-unlock set
 * and the live auto-rule list loaded once per join and refreshed by this
 * module's own mutation paths — the RpgPlaceholders cache discipline
 * generalized to three slices. Nothing here is an authority: the
 * ADR-0019 service remains the only writer, and a cold or in-flight entry
 * resolves to null so callers miss honestly rather than block on an event
 * handler.
 *
 * <p>Refresh rules: every mutation path refreshes exactly its slice —
 * {@code awardBrewXp} → progress, unlock commits/grants → unlocks, rule
 * mutations and committed executions → rules. Only this module writes
 * these tables, so the mirror can never be stale-wrong, only
 * refresh-pending.</p>
 */
public final class AlchemyPlayerCache {

    /** One viewer's three mirror slices. */
    public record Entry(CompletableFuture<BrewProgress> progress,
            CompletableFuture<Set<String>> unlocks,
            CompletableFuture<List<AutoRule>> rules) {
    }

    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final AlchemyService service;

    public AlchemyPlayerCache(@NotNull AlchemyService service) {
        this.service = service;
    }

    /** Loads (or reloads) all three slices for one viewer. */
    public void load(@NotNull UUID player) {
        cache.put(player, new Entry(
                service.progress(player),
                service.unlocks(player).thenApply(AlchemyPlayerCache::unlockKeys),
                service.rules(player)));
    }

    /** Refreshes the progress slice after an XP award. */
    public void refreshProgress(@NotNull UUID player) {
        mutate(player,
                entry -> new Entry(service.progress(player), entry.unlocks(),
                        entry.rules()));
    }

    /** Refreshes the unlock-set slice after a purchase/grant. */
    public void refreshUnlocks(@NotNull UUID player) {
        mutate(player,
                entry -> new Entry(entry.progress(),
                        service.unlocks(player).thenApply(AlchemyPlayerCache::unlockKeys),
                        entry.rules()));
    }

    /** Refreshes the rule-list slice after a mutation or committed execution. */
    public void refreshRules(@NotNull UUID player) {
        mutate(player,
                entry -> new Entry(entry.progress(), entry.unlocks(),
                        service.rules(player)));
    }

    /** Drops the viewer's entry (quit). */
    public void evict(@NotNull UUID player) {
        cache.remove(player);
    }

    /** Loads every online viewer (module enable after a reload). */
    public void loadAllOnline(@NotNull List<org.bukkit.entity.Player> online) {
        online.forEach(player -> load(player.getUniqueId()));
    }

    /** @return the cached progress, or null when cold/in-flight. */
    public @Nullable BrewProgress progressNow(@NotNull UUID player) {
        return doneOrNull(entry(player).progress());
    }

    /** @return the cached owned-unlock keys, or null when cold/in-flight. */
    public @Nullable Set<String> unlocksNow(@NotNull UUID player) {
        return doneOrNull(entry(player).unlocks());
    }

    /** @return the cached live rules in conflict order, or null when
     *  cold/in-flight. */
    public @Nullable List<AutoRule> rulesNow(@NotNull UUID player) {
        return doneOrNull(entry(player).rules());
    }

    // ------------------------------------------------------------------

    private @NotNull Entry entry(@NotNull UUID player) {
        return cache.getOrDefault(player, EMPTY);
    }

    private static final Entry EMPTY = new Entry(
            CompletableFuture.<BrewProgress>completedFuture(null),
            CompletableFuture.<Set<String>>completedFuture(null),
            CompletableFuture.<List<AutoRule>>completedFuture(null));

    private static <T> @Nullable T doneOrNull(CompletableFuture<T> future) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            return future.join();
        }
        return null;
    }

    private void mutate(@NotNull UUID player, Function<Entry, Entry> rebuild) {
        Entry current = cache.get(player);
        if (current == null) {
            return; // viewer not cached (offline): nothing stale to fix
        }
        cache.put(player, rebuild.apply(current));
    }

    private static Set<String> unlockKeys(List<UnlockGrant> grants) {
        return Set.copyOf(grants.stream().map(UnlockGrant::unlockKey).toList());
    }
}
