package net.zaminmc.rebellion.teams;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import org.jetbrains.annotations.NotNull;

/**
 * Cache-backed {@link TeamAffiliationService} adapter (ADR-0007 consumer
 * contract). Every method delegates to {@link TeamCache} — a pure in-memory
 * read with no database access — and maps "not loaded" to empty so ward and
 * future consumers safely treat unknown players as teamless.
 */
public final class TeamAffiliationAdapter implements TeamAffiliationService {

    private final TeamCache cache;

    public TeamAffiliationAdapter(TeamCache cache) {
        this.cache = cache;
    }

    @Override
    public @NotNull Optional<UUID> teamOf(@NotNull UUID player) {
        return cache.lookup(player).flatMap(o -> o).map(TeamCache.Snapshot::teamId);
    }

    @Override
    public @NotNull Optional<String> rankOf(@NotNull UUID player) {
        return cache.lookup(player).flatMap(o -> o).map(TeamCache.Snapshot::rank);
    }

    @Override
    public @NotNull Set<UUID> onlineMembersOf(@NotNull UUID teamId) {
        return cache.onlineMembers(teamId);
    }
}
