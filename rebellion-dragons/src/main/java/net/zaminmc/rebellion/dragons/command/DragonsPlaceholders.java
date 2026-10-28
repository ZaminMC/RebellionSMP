package net.zaminmc.rebellion.dragons.command;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import net.zaminmc.rebellion.dragons.Lineage;
import net.zaminmc.rebellion.dragons.LineageService;
import net.zaminmc.rebellion.dragons.LineageState;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionDragons' contribution to the shared {@code rebellion} expansion
 * (master prompt §13):
 *
 * <ul>
 *   <li>{@code %rebellion_dragon_owned%} — {@code true}/{@code false}:
 *       does the viewer own a lineage (egg or living pet)?</li>
 *   <li>{@code %rebellion_dragon_id%} — the viewer's lineage UUID, or null.</li>
 *   <li>{@code %rebellion_dragon_health%} — the living pet's last persisted
 *       health, or null when the viewer owns no living pet.</li>
 *   <li>{@code %rebellion_dragon_count%} — lineages ever minted
 *       (registry size; retirement never shrinks it).</li>
 *   <li>{@code %rebellion_dragon_cap%} — the effective server-wide cap.</li>
 * </ul>
 *
 * <p>Pure in-memory mirror reads only — no database access per placeholder
 * request (§13: never query SQL per request). Privacy is structural: a
 * viewer sees only their own lineage; other players' dragons are not
 * exposed through placeholders.</p>
 */
public final class DragonsPlaceholders implements PlaceholderContributor {

    private final LineageService lineages;

    public DragonsPlaceholders(@NotNull LineageService lineages) {
        this.lineages = lineages;
    }

    @Override
    public @Nullable String resolve(@NotNull String params) {
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "dragon_count" -> String.valueOf(lineages.count());
            case "dragon_cap" -> String.valueOf(lineages.cap());
            default -> null;
        };
    }

    @Override
    public @Nullable String resolveFor(@Nullable Player player, @NotNull String params) {
        if (player == null) {
            return null;
        }
        UUID viewer = player.getUniqueId();
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "dragon_owned" -> String.valueOf(ownedLineage(viewer).isPresent());
            case "dragon_id" -> ownedLineage(viewer)
                    .map(l -> l.lineageId().toString())
                    .orElse(null);
            case "dragon_health" -> ownedLineage(viewer)
                    .filter(l -> l.state() == LineageState.HATCHED)
                    .map(l -> l.health() == null ? null
                            : String.format(Locale.ROOT, "%.1f", l.health()))
                    .orElse(null);
            default -> null;
        };
    }

    private Optional<Lineage> ownedLineage(UUID viewer) {
        return lineages.all().stream()
                .filter(l -> viewer.equals(l.owner()))
                .findFirst();
    }
}
