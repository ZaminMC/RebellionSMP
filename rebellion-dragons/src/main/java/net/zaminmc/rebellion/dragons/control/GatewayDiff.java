package net.zaminmc.rebellion.dragons.control;

import java.util.ArrayList;
import java.util.List;
import net.zaminmc.rebellion.dragons.Lineage;
import org.jetbrains.annotations.NotNull;

/**
 * Pure mint-decision for the boss-victory gateway observation
 * (ADR-0014). Given the gateway positions present in a baseline scan taken
 * at the boss's death event, the positions present in a later poll scan,
 * and the gateway anchors already backing registered lineages, decides
 * which positions are legitimately mintable for the credited victor.
 *
 * <p>Fail-closed rules, all of them deliberate:</p>
 * <ul>
 *   <li>Candidates = (current \ baseline) \ registered — only gateways that
 *       <em>emerged inside this victory's observation window</em> and do
 *       not already back a lineage. A gateway that predates the window
 *       (or the baseline raced its emergence) is never minted.</li>
 *   <li>{@code countDelta} (from {@code DragonBattle.getGatewayCount()}) is
 *       the cross-check: vanilla announced that many new gateways. When
 *       the candidate count matches, every candidate mints; when candidates
 *       exist but the counts disagree, the attribution is ambiguous and
 *       <strong>nothing mints</strong> (fail-closed, loud warning).</li>
 *   <li>Candidates empty with {@code countDelta > 0} = scan miss (bounds
 *       wrong or the gateway beat the baseline): warning, no mint — never
 *       a guessed position.</li>
 * </ul>
 *
 * <p>The DB {@code UNIQUE (origin_world, gateway_x, gateway_y, gateway_z)}
 * backstop (ADR-0009) still guards against duplicate mints even if two
 * overlapping observation windows somehow produce the same candidate, so
 * the worst case everywhere is a typed {@code ISSUED_ALREADY} no-op.</p>
 */
public final class GatewayDiff {

    private GatewayDiff() {
    }

    /** Mint decision: positions to mint (ordered) plus a fail-closed warning. */
    public record Decision(@NotNull List<Lineage.GatewayKey> mint, String warning) {
        public boolean mintAnything() {
            return !mint.isEmpty();
        }
    }

    /**
     * @param baseline    gateway positions observed by the baseline scan at
     *                    the death event (may be empty when the scan failed)
     * @param current     gateway positions observed by a later poll scan
     * @param registered  gateway anchors already backing lineage rows
     * @param countDelta  vanilla's announced new-gateway count for this
     *                    window ({@code getGatewayCount()} delta), or -1
     *                    when the count could not be read
     */
    public static @NotNull Decision decide(@NotNull List<Lineage.GatewayKey> baseline,
            @NotNull List<Lineage.GatewayKey> current,
            @NotNull List<Lineage.GatewayKey> registered, int countDelta) {
        List<Lineage.GatewayKey> candidates = new ArrayList<>();
        for (Lineage.GatewayKey key : current) {
            if (!baseline.contains(key) && !registered.contains(key)) {
                candidates.add(key);
            }
        }
        if (candidates.isEmpty()) {
            if (countDelta > 0) {
                return new Decision(List.of(),
                        "gateway count rose by " + countDelta + " but no new gateway "
                                + "position was found in the configured scan bounds "
                                + "(scan miss - no mint; widen scan bounds after "
                                + "checking the live ring geometry)");
            }
            return new Decision(List.of(), null);
        }
        if (countDelta >= 0 && candidates.size() != countDelta) {
            return new Decision(List.of(),
                    "ambiguous observation: " + candidates.size() + " candidate "
                            + "position(s) but vanilla announced " + countDelta
                            + " new gateway(s) - refusing to attribute (no mint)");
        }
        return new Decision(List.copyOf(candidates), null);
    }
}
