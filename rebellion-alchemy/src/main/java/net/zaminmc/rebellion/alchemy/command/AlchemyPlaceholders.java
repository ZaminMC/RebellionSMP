package net.zaminmc.rebellion.alchemy.command;

import java.util.List;
import java.util.Locale;
import net.zaminmc.rebellion.alchemy.BrewProgress;
import net.zaminmc.rebellion.alchemy.controller.AlchemyPlayerCache;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionAlchemy's contribution to the shared {@code rebellion}
 * expansion (master prompt §10, ADR-0020 §5):
 *
 * <ul>
 *   <li>{@code %rebellion_brewing_level%} — the derived brewing level
 *       (the §10 minimum placeholder);</li>
 *   <li>{@code %rebellion_brewing_total_xp%} — lifetime accumulated
 *       XP;</li>
 *   <li>{@code %rebellion_brewing_next_level_xp%} — the XP still needed
 *       for the next level (0 at max);</li>
 *   <li>{@code %rebellion_brewing_unlock_<key>%} — the operator-localized
 *       owned/unowned wording, the same
 *       {@code brewing.unlocks.state.owned}/{@code unowned} vocabulary
 *       {@code /brewing unlocks} renders (the session-15 lesson: menus
 *       and PAPI share one MiniMessage-colored vocabulary, never bare
 *       booleans).</li>
 * </ul>
 *
 * <p>Contract compliance (PlaceholderContributor): resolution is a pure
 * in-memory read over the join-loaded cache — never a per-request
 * database hop; unknown parameters, cold caches and missing templates
 * return null (PlaceholderAPI and the menu engine leave the text
 * untouched); only the viewer's own state is ever exposed.</p>
 */
public final class AlchemyPlaceholders implements PlaceholderContributor {

    private final AlchemyPlayerCache cache;
    private final MessageCatalog messages;

    public AlchemyPlaceholders(@NotNull AlchemyPlayerCache cache,
            @NotNull MessageCatalog messages) {
        this.cache = cache;
        this.messages = messages;
    }

    @Override
    public @Nullable String resolve(@NotNull String params) {
        return null; // every alchemy placeholder is viewer-scoped
    }

    @Override
    public @Nullable String resolveFor(@Nullable Player player,
            @NotNull String params) {
        if (player == null) {
            return null;
        }
        String lower = params.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "brewing_level", "brewing_total_xp", "brewing_next_level_xp" -> {
                BrewProgress progress = cache.progressNow(player.getUniqueId());
                if (progress == null) {
                    return null; // cold cache: token stays untouched
                }
                return switch (lower) {
                    case "brewing_level" -> String.valueOf(progress.level());
                    case "brewing_total_xp" -> String.valueOf(progress.totalXp());
                    default -> String.valueOf(progress.nextLevelCost());
                };
            }
            default -> {
            }
        }
        String key = parseUnlockKey(params);
        if (key == null) {
            return null;
        }
        java.util.Set<String> owned = cache.unlocksNow(player.getUniqueId());
        if (owned == null) {
            return null;
        }
        return ownedWord(owned.contains(key), messages);
    }

    /**
     * Parameter shape: {@code brewing_unlock_<key>} with the unlock key in
     * {@code [a-z0-9_]{1,32}}. Pure; anything else is null.
     */
    static @Nullable String parseUnlockKey(@NotNull String params) {
        String lower = params.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("brewing_unlock_")) {
            return null;
        }
        String key = params.substring("brewing_unlock_".length())
                .toLowerCase(Locale.ROOT);
        if (key.isEmpty() || !key.matches("[a-z0-9_]{1,32}")) {
            return null;
        }
        return key;
    }

    /**
     * The localized owned/unowned wording, reusing the
     * {@code /brewing unlocks} vocabulary. Pure; a missing template
     * yields null (the token stays raw rather than lying).
     */
    static @Nullable String ownedWord(boolean owned,
            @NotNull MessageCatalog messages) {
        return messages.template(owned
                ? "brewing.unlocks.state.owned" : "brewing.unlocks.state.unowned");
    }

    /** Loads every online viewer (module enable after a reload). */
    public void loadAllOnline(@NotNull List<Player> online) {
        online.forEach(player -> cache.load(player.getUniqueId()));
    }
}
