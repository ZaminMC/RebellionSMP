package net.zaminmc.rebellion.wards.command;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardService;
import net.zaminmc.rebellion.wards.WardType;
import net.zaminmc.rebellion.wards.distance.DistanceService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionWards' contribution to the shared {@code rebellion} expansion
 * (master prompt §13):
 *
 * <ul>
 *   <li>{@code %rebellion_nearest_ward_distance%} — calibrated distance from
 *       the viewer to the nearest <em>own</em> (own or own-team) active,
 *       non-decoy ward in the viewer's world, e.g. {@code 2.4 km} when the
 *       world is calibrated or {@code 2400 blocks} otherwise. Null when no
 *       such ward shares the viewer's world.</li>
 *   <li>{@code %rebellion_ward_count%} — number of own/own-team wards.</li>
 * </ul>
 *
 * <p>Privacy is structural: only the viewer's own ward network is visible.
 * Nearest ENEMY ward distance is tracking intel that belongs to the Phase 7
 * ritual system, never to a free placeholder. Pure in-memory reads (index +
 * team cache); no database access.</p>
 */
public final class WardsPlaceholders implements PlaceholderContributor {

    private final WardService service;
    private final DistanceService distances;
    private final TeamAffiliationService teams;

    public WardsPlaceholders(WardService service, DistanceService distances,
            TeamAffiliationService teams) {
        this.service = service;
        this.distances = distances;
        this.teams = teams;
    }

    @Override
    public @Nullable String resolve(@NotNull String params) {
        return null; // both parameters are player-dependent
    }

    @Override
    public @Nullable String resolveFor(@Nullable Player player, @NotNull String params) {
        if (player == null) {
            return null;
        }
        UUID viewer = player.getUniqueId();
        String world = player.getWorld().getName();
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "nearest_ward_distance" -> {
                double px = player.getLocation().getX();
                double pz = player.getLocation().getZ();
                Optional<Double> nearest =
                        nearestOwnWard(service.ownedBy(viewer), world, px, pz);
                yield nearest.map(blocks -> distances.format(blocks, world)).orElse(null);
            }
            case "ward_count" -> String.valueOf(service.ownedBy(viewer).size());
            default -> null;
        };
    }

    /**
     * Pure nearest-own-ward computation (unit-tested directly): active, real
     * (non-decoy) wards of the viewer's network in the viewer's world only.
     */
    static Optional<Double> nearestOwnWard(List<Ward> ownedWards, String world,
            double px, double pz) {
        return ownedWards.stream()
                .filter(Ward::active)
                .filter(ward -> ward.type() == WardType.WARD)
                .filter(ward -> ward.world().equals(world))
                .map(ward -> Math.sqrt(ward.horizontalDistanceSquared(px, pz)))
                .min(Comparator.naturalOrder());
    }
}
