package net.zaminmc.rebellion.api.teams;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cached, non-blocking team affiliation contract for cross-module consumers
 * (ward outsider checks, future proximity features).
 *
 * <p>RebellionTeams registers the implementation with Bukkit's
 * {@code ServicesManager} over its hot-path membership cache; consumers must
 * look it up with {@code RebellionApi.find(TeamAffiliationService.class)} and
 * degrade gracefully when the teams module is absent. The contract is
 * explicitly <em>cache-only</em>:</p>
 *
 * <ul>
 *   <li>Every method is a pure in-memory read — implementations must never
 *       query the database, block, or allocate unbounded work.</li>
 *   <li>Empty means "teamless, not yet loaded, or module absent" — consumers
 *       treat unknown players as teamless and must not retry on a hot path.</li>
 *   <li>Snapshots reflect the last successful cache refresh, not necessarily
 *       the very latest committed transaction.</li>
 * </ul>
 *
 * <p>Consumers that need transactional authority (membership changes) must
 * still go through the teams module's own commands/service — this interface
 * only answers "who is on this player's team right now, as far as the live
 * cache knows" for event-path decisions.</p>
 */
public interface TeamAffiliationService {

    /**
     * Team id of the player, or empty when teamless, not yet loaded, or the
     * teams module is absent.
     *
     * @param player the player UUID (never null)
     */
    @NotNull Optional<UUID> teamOf(@NotNull UUID player);

    /**
     * Cached rank key of the player (teams define the vocabulary, e.g.
     * {@code leader}/{@code officer}/{@code member}), or empty when teamless
     * or not loaded. Never a database read.
     *
     * @param player the player UUID (never null)
     */
    @NotNull Optional<String> rankOf(@NotNull UUID player);

    /**
     * Online members of a team (possibly empty), as far as the live cache
     * knows. Returns an empty set when the team has no online members or the
     * module cannot resolve the team at all — callers fall back to their own
     * recipient (e.g. the ward owner).
     *
     * @param teamId the team UUID (never null)
     */
    @NotNull Set<UUID> onlineMembersOf(@NotNull UUID teamId);

    /**
     * Convenience view: online teammates of {@code player} including the
     * player themself when teamed, otherwise just the player.
     *
     * @param player the player UUID (never null)
     */
    default @NotNull Set<UUID> onlineTeammatesOf(@NotNull UUID player) {
        var team = teamOf(player);
        if (team.isEmpty()) {
            return Set.of(player);
        }
        Set<UUID> members = onlineMembersOf(team.get());
        return members.isEmpty() ? Set.of(player) : members;
    }

    /**
     * Whether both players are loaded and cached in the same team. False
     * whenever either side is unknown — the safe default for "is this player
     * an outsider".
     *
     * @param a first player (never null)
     * @param b second player (never null)
     */
    default boolean sameTeam(@NotNull UUID a, @NotNull UUID b) {
        var ta = teamOf(a);
        var tb = teamOf(b);
        return ta.isPresent() && ta.equals(tb);
    }

    /** Nullable-safe accessor used by consumers that already hold the value. */
    static boolean sameTeam(@Nullable TeamAffiliationService service,
            @NotNull UUID a, @NotNull UUID b) {
        return service != null && service.sameTeam(a, b);
    }
}
