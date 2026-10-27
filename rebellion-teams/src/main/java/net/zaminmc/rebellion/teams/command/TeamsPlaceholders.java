package net.zaminmc.rebellion.teams.command;

import java.util.Locale;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import net.zaminmc.rebellion.teams.TeamService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionTeams' contribution to the shared {@code rebellion} expansion.
 * Pure in-memory reads from the hot-path cache; no database access, no
 * tracking data.
 *
 * <ul>
 *   <li>{@code %rebellion_team_name%} — display name of the viewer's team,
 *       empty when teamless.</li>
 *   <li>{@code %rebellion_team_rank%} — leader/officer/member, empty when
 *       teamless.</li>
 *   <li>{@code %rebellion_team_online%} — number of online teammates.</li>
 *   <li>{@code %rebellion_has_team%} — "true" or "false".</li>
 * </ul>
 */
public final class TeamsPlaceholders implements PlaceholderContributor {

    private final TeamService service;

    public TeamsPlaceholders(TeamService service) {
        this.service = service;
    }

    @Override
    public @Nullable String resolve(@NotNull String params) {
        return null; // every teams parameter is player-dependent
    }

    @Override
    public @Nullable String resolveFor(@Nullable Player player, @NotNull String params) {
        if (player == null) {
            return null;
        }
        var snapshot = service.cache().lookup(player.getUniqueId()).flatMap(o -> o);
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "team_name" -> snapshot.map(s -> s.displayName()).orElse("");
            case "team_rank" -> snapshot.map(s -> s.rank()).orElse("");
            case "team_online" -> String.valueOf(service.cache().onlineTeamSize(
                    player.getUniqueId()));
            case "has_team" -> String.valueOf(snapshot.isPresent());
            default -> null;
        };
    }
}
