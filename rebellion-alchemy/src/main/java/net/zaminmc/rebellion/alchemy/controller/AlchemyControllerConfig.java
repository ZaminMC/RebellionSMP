package net.zaminmc.rebellion.alchemy.controller;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Validated controller settings for RebellionAlchemy (ADR-0020), loaded
 * from the {@code alchemy.controller} section of the module's config.yml —
 * the same file the ADR-0019 definition families load from, so operators
 * edit one document. The section is <em>optional</em>: an absent section
 * yields the shipped defaults without a single issue, keeping the
 * persistence-only contract of ADR-0019-era config files intact.
 *
 * <p>Immutable; a reload swaps the whole record atomically (the
 * ADR-0004 whole-record pattern). Restart-only: the controller tasks read
 * these values at enable time.</p>
 *
 * @param autoEvalIntervalSeconds how often the auto-consumption evaluator
 *        runs its rule pass over online players (1..60, default 2)
 * @param sweepIntervalSeconds how often the maintenance sweep releases
 *        stale claims (5..600, default 60)
 * @param reservationStaleSeconds a RESERVED claim older than this is crash
 *        debris and is released by the sweep with its refund digest
 *        (10..86400, default 120 — the consume flow is synchronous, so
 *        anything left over is a dead server's residue)
 * @param fallThresholdBlocks the FALLING trigger's fall-distance bound
 *        (3..100, default 8; the trigger itself still ships
 *        policy-disabled — ADR-0019)
 * @param autoConsumeNotify whether executed auto-consumptions tell the
 *        player (the §5.7 audit/debug-reason surface rendered as a
 *        message; the event row always exists regardless)
 */
public record AlchemyControllerConfig(int autoEvalIntervalSeconds,
        int sweepIntervalSeconds, long reservationStaleSeconds,
        int fallThresholdBlocks, boolean autoConsumeNotify) {

    public static final int MIN_EVAL_INTERVAL = 1;
    public static final int MAX_EVAL_INTERVAL = 60;
    public static final int MIN_SWEEP_INTERVAL = 5;
    public static final int MAX_SWEEP_INTERVAL = 600;
    public static final long MIN_STALE_SECONDS = 10;
    public static final long MAX_STALE_SECONDS = 86_400L;
    public static final int MIN_FALL_THRESHOLD = 3;
    public static final int MAX_FALL_THRESHOLD = 100;
    public static final int DEFAULT_FALL_THRESHOLD = 8;

    public static AlchemyControllerConfig defaults() {
        return new AlchemyControllerConfig(2, 60, 120L,
                DEFAULT_FALL_THRESHOLD, true);
    }

    public record LoadResult(AlchemyControllerConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    /** Loads the optional {@code alchemy.controller} section. */
    public static LoadResult load(YamlConfiguration yaml) {
        List<String> issues = new ArrayList<>();
        ConfigurationSection section =
                yaml.getConfigurationSection("alchemy.controller");
        if (section == null) {
            return new LoadResult(defaults(), List.of());
        }

        int eval = bounded(section, "auto-eval-interval-seconds", 2,
                MIN_EVAL_INTERVAL, MAX_EVAL_INTERVAL, issues);
        int sweep = bounded(section, "sweep-interval-seconds", 60,
                MIN_SWEEP_INTERVAL, MAX_SWEEP_INTERVAL, issues);

        long stale = section.getLong("reservation-stale-seconds", 120L);
        if (stale < MIN_STALE_SECONDS || stale > MAX_STALE_SECONDS) {
            issues.add("alchemy.controller.reservation-stale-seconds: must be "
                    + "between " + MIN_STALE_SECONDS + " and " + MAX_STALE_SECONDS
                    + " (got " + stale + ")");
            stale = 120L;
        }

        int fall = bounded(section, "fall-threshold-blocks",
                DEFAULT_FALL_THRESHOLD, MIN_FALL_THRESHOLD, MAX_FALL_THRESHOLD,
                issues);

        boolean notify = section.getBoolean("auto-consume-notify", true);

        AlchemyControllerConfig config = issues.isEmpty()
                ? new AlchemyControllerConfig(eval, sweep, stale, fall, notify)
                : null;
        return new LoadResult(config, List.copyOf(issues));
    }

    private static int bounded(ConfigurationSection section, String key,
            int fallback, int min, int max, List<String> issues) {
        int value = section.getInt(key, fallback);
        if (value < min || value > max) {
            issues.add("alchemy.controller." + key + ": must be between "
                    + min + " and " + max + " (got " + value + ")");
            return fallback;
        }
        return value;
    }
}
