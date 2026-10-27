package net.zaminmc.rebellion.dragons;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Validated snapshot of RebellionDragons' {@code config.yml}. Immutable; a
 * reload swaps the whole record atomically. Every field is checked at load
 * and every issue is collected, following the established TeamsConfig /
 * WardsConfig pattern (ADR-0004).
 *
 * <p>Deliberately minimal (ADR-0009): only the lineage cap exists at the
 * persistence layer. Ride speed, fireball budgets, pearl-heal tuning and
 * water-weakness values arrive with the controller phase, which is gated on
 * the ADR-0008 live-server proofs — shipping tuned-looking numbers for
 * unproven behavior would be guesswork, and the master prompt forbids
 * guessing.</p>
 *
 * <p>The load-bearing rule is the cap contract (master prompt §5.6): the
 * limit is 20 obtainable lineages <em>server-wide</em> because the End has
 * at most 20 eligible gateways. {@code max-lineages} must sit in
 * {@code 1..}{@link LineagePolicy#HARD_CEILING}; anything larger is a loud
 * load error, never a silent stretch.</p>
 */
public record DragonsConfig(int maxLineages) {

    public record LoadResult(DragonsConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    public static DragonsConfig load(YamlConfiguration yaml) {
        var result = loadResult(yaml);
        if (!result.valid()) {
            throw new IllegalStateException("config invalid: " + result.issues());
        }
        return result.config();
    }

    /** @return candidate snapshot (null when invalid) plus every issue found. */
    public static LoadResult loadResult(YamlConfiguration yaml) {
        List<String> issues = new ArrayList<>();

        int cap = yaml.getInt("dragons.max-lineages", LineagePolicy.HARD_CEILING);
        if (cap < 1 || cap > LineagePolicy.HARD_CEILING) {
            issues.add("dragons.max-lineages: must be between 1 and "
                    + LineagePolicy.HARD_CEILING + " (got " + cap
                    + ") - the End gateway ring is finite; a larger cap can never be honored");
        }

        DragonsConfig config = issues.isEmpty() ? new DragonsConfig(cap) : null;
        return new LoadResult(config, List.copyOf(issues));
    }

    /** @return the effective mint cap, clamped to the hard policy ceiling. */
    public int effectiveMaxLineages() {
        return LineagePolicy.effectiveCap(maxLineages);
    }
}
