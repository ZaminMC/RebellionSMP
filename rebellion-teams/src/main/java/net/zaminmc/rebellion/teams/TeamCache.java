package net.zaminmc.rebellion.teams;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Hot-path team membership cache for online players (ADR-0006).
 *
 * <p>The database is the authority; this cache exists so damage and chat
 * events never touch it. A player's entry is loaded on join, refreshed after
 * every mutation that affects them and evicted on quit. Lookup semantics:</p>
 * <ul>
 *   <li>{@link #lookup} empty → player not loaded yet (join still in
 *       flight): listeners fall back to allow-damage/allow-chat with a
 *       bounded diagnostic, never a DB call.</li>
 *   <li>{@code Optional.empty()} inside → loaded, teamless.</li>
 * </ul>
 */
public final class TeamCache {

    /** Immutable per-player team view. {@code allies} covers the ally teams. */
    public record Snapshot(UUID teamId, String nameNorm, String displayName,
            String rank, boolean friendlyFire, Set<UUID> allies) {
    }

    private final ConcurrentHashMap<UUID, Optional<Snapshot>> byPlayer =
            new ConcurrentHashMap<>();
    /** teamId → online players in that team (online view only). */
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<UUID>> byTeam =
            new ConcurrentHashMap<>();

    /** Outer empty = not loaded; inner empty = loaded and teamless. */
    public Optional<Optional<Snapshot>> lookup(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    /** Loaded and teamless marker. */
    public void putTeamless(UUID player) {
        replace(player, Optional.empty());
    }

    public void put(UUID player, Snapshot snapshot) {
        replace(player, Optional.of(snapshot));
    }

    public void evict(UUID player) {
        Optional<Snapshot> previous = byPlayer.remove(player);
        if (previous != null) {
            previous.map(Snapshot::teamId).ifPresent(teamId -> removeFromTeam(teamId, player));
        }
    }

    /** Online teammates of {@code player} (including the player) when in a team. */
    public Set<UUID> onlineTeammates(UUID player) {
        return lookup(player).flatMap(o -> o)
                .map(s -> Set.copyOf(teammatesOf(s.teamId())))
                .orElse(Set.of(player));
    }

    /** Iterates every online player of a team (used after team-wide changes). */
    public void forEachOnlineMember(UUID teamId, Consumer<UUID> action) {
        teammatesOf(teamId).forEach(action);
    }

    /**
     * Snapshot of every online member of a team; empty when the team has no
     * online members. Backs the {@code TeamAffiliationService} adapter used
     * by cross-module consumers (e.g. ward alert routing).
     */
    public Set<UUID> onlineMembers(UUID teamId) {
        return teammatesOf(teamId);
    }

    /** @return true when both players are loaded and in the same team. */
    public boolean sameTeam(UUID a, UUID b) {
        Optional<Snapshot> sa = lookup(a).flatMap(o -> o);
        Optional<Snapshot> sb = lookup(b).flatMap(o -> o);
        return sa.isPresent() && sb.isPresent()
                && sa.get().teamId().equals(sb.get().teamId());
    }

    /** @return true when both players are loaded, in teams, and allies. */
    public boolean allied(UUID a, UUID b) {
        Optional<Snapshot> sa = lookup(a).flatMap(o -> o);
        Optional<Snapshot> sb = lookup(b).flatMap(o -> o);
        return sa.isPresent() && sb.isPresent()
                && sa.get().allies().contains(sb.get().teamId());
    }

    /** @return count of online members of the player's team (0 when teamless). */
    public int onlineTeamSize(UUID player) {
        return lookup(player).flatMap(o -> o)
                .map(s -> teammatesOf(s.teamId()).size())
                .orElse(0);
    }

    /** Replaces the ally set on every online member snapshot of the team. */
    public void updateAllies(UUID teamId, Set<UUID> allies) {
        for (UUID member : teammatesOf(teamId)) {
            lookup(member).flatMap(o -> o)
                    .filter(s -> s.teamId().equals(teamId))
                    .ifPresent(s -> put(member,
                            new Snapshot(s.teamId(), s.nameNorm(), s.displayName(),
                                    s.rank(), s.friendlyFire(), Set.copyOf(allies))));
        }
    }

    /** Updates the snapshot fields of every online member of a team. */
    public void updateTeam(UUID teamId, String nameNorm, String displayName,
            boolean friendlyFire) {
        for (UUID member : teammatesOf(teamId)) {
            lookup(member).flatMap(o -> o)
                    .filter(s -> s.teamId().equals(teamId))
                    .ifPresent(s -> put(member, new Snapshot(teamId, nameNorm,
                            displayName, s.rank(), friendlyFire, s.allies())));
        }
    }

    /** Updates only the rank of one member snapshot. */
    public void updateRank(UUID player, String rankKey) {
        lookup(player).flatMap(o -> o).ifPresent(s ->
                put(player, new Snapshot(s.teamId(), s.nameNorm(), s.displayName(),
                        rankKey, s.friendlyFire(), s.allies())));
    }

    /** Evicts every online member of a team (used on disband). */
    public void evictTeam(UUID teamId, Collection<UUID> knownMembers) {
        for (UUID member : knownMembers) {
            evict(member);
        }
        byTeam.remove(teamId);
    }

    private void replace(UUID player, Optional<Snapshot> snapshot) {
        Optional<Snapshot> previous = byPlayer.put(player, snapshot);
        if (previous != null) {
            previous.map(Snapshot::teamId).ifPresent(oldTeam -> removeFromTeam(oldTeam, player));
        }
        snapshot.ifPresent(s -> byTeam
                .computeIfAbsent(s.teamId(), k -> new CopyOnWriteArraySet<>())
                .add(player));
    }

    private void removeFromTeam(UUID teamId, UUID player) {
        CopyOnWriteArraySet<UUID> members = byTeam.get(teamId);
        if (members != null) {
            members.remove(player);
            if (members.isEmpty()) {
                byTeam.remove(teamId, members);
            }
        }
    }

    private Set<UUID> teammatesOf(UUID teamId) {
        CopyOnWriteArraySet<UUID> members = byTeam.get(teamId);
        return members == null ? Set.of() : Set.copyOf(members);
    }

    /** @return live snapshot of players cached as teamless. */
    public List<UUID> cachedPlayers() {
        return List.copyOf(byPlayer.keySet());
    }
}
