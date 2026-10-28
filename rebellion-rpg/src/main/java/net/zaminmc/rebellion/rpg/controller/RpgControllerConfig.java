package net.zaminmc.rebellion.rpg.controller;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Validated controller settings for RebellionRPG (ADR-0017), loaded from
 * the {@code rpg.controller} section of the module's config.yml — the
 * same file the ADR-0016 definition families load from, so operators edit
 * one document. The section is <em>optional</em>: an absent section yields
 * the shipped defaults without a single issue, keeping the
 * persistence-only contract of ADR-0016-era config files intact.
 *
 * <p>Immutable; a reload swaps the whole record atomically (the
 * ADR-0004 whole-record pattern). Restart-only: the controller tasks read
 * these values at enable time.</p>
 *
 * @param spawnIntervalSeconds how often the soul spawner evaluates
 *        enabled encounter definitions (10..3600, default 300)
 * @param spawnRadiusBlocks horizontal offset range for a soul's spawn
 *        site around its chosen anchor player (8..256, default 64 — the
 *        site lands in the player's general area, recorded in the
 *        ledger row)
 * @param signalIntervalSeconds how often active tracking sessions pulse
 *        their signal to the caster (0 = pulse disabled — on-demand
 *        {@code /ritual track} still renders; otherwise 10..600,
 *        default 60)
 * @param sweepIntervalSeconds how often the maintenance sweep runs
 *        (stale-reservation release, encounter TTL expiry, soul-entity
 *        reconcile; 5..600, default 60)
 * @param reservationStaleSeconds a RESERVED claim older than this is crash
 *        debris and is released by the sweep with its refund digest
 *        (10..86400, default 120 — the cast flow is synchronous, so
 *        anything left over is a dead server's residue)
 * @param distanceBuckets ascending horizontal-distance edges in blocks
 *        for REGION-tier tracking signals (1..8 entries, each 1..100000,
 *        strictly ascending, default [256, 1024, 4096]). Block units
 *        only — never kilometers: the wards calibrated-scale precedent
 *        means no uncalibrated unit label may masquerade as real-world
 *        distance.
 */
public record RpgControllerConfig(
        int spawnIntervalSeconds,
        int spawnRadiusBlocks,
        int signalIntervalSeconds,
        int sweepIntervalSeconds,
        long reservationStaleSeconds,
        @NotNull List<Integer> distanceBuckets) {

    public static final int MIN_SPAWN_INTERVAL = 10;
    public static final int MAX_SPAWN_INTERVAL = 3600;
    public static final int MIN_SPAWN_RADIUS = 8;
    public static final int MAX_SPAWN_RADIUS = 256;
    public static final int MIN_SIGNAL_INTERVAL = 10;
    public static final int MAX_SIGNAL_INTERVAL = 600;
    public static final int MIN_SWEEP_INTERVAL = 5;
    public static final int MAX_SWEEP_INTERVAL = 600;
    public static final long MIN_STALE_SECONDS = 10;
    public static final long MAX_STALE_SECONDS = 86_400L;
    public static final int MIN_BUCKETS = 1;
    public static final int MAX_BUCKETS = 8;
    public static final int MIN_BUCKET_EDGE = 1;
    public static final int MAX_BUCKET_EDGE = 100_000;
    public static final List<Integer> DEFAULT_BUCKETS = List.of(256, 1024, 4096);

    public RpgControllerConfig {
        distanceBuckets = List.copyOf(distanceBuckets);
    }

    public static RpgControllerConfig defaults() {
        return new RpgControllerConfig(300, 64, 60, 60, 120L, DEFAULT_BUCKETS);
    }

    public record LoadResult(RpgControllerConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    /** Loads the optional {@code rpg.controller} section. */
    public static LoadResult load(YamlConfiguration yaml) {
        List<String> issues = new ArrayList<>();
        ConfigurationSection section =
                yaml.getConfigurationSection("rpg.controller");
        if (section == null) {
            return new LoadResult(defaults(), List.of());
        }

        int spawn = bounded(section, "spawn-interval-seconds", 300,
                MIN_SPAWN_INTERVAL, MAX_SPAWN_INTERVAL, issues);
        int radius = bounded(section, "spawn-radius-blocks", 64,
                MIN_SPAWN_RADIUS, MAX_SPAWN_RADIUS, issues);
        int signal = bounded(section, "signal-interval-seconds", 60,
                0, MAX_SIGNAL_INTERVAL, issues);
        // 0 disables the pulse, but 10..600 must hold whenever it runs.
        if (signal != 0 && (signal < MIN_SIGNAL_INTERVAL
                || signal > MAX_SIGNAL_INTERVAL)) {
            issues.add("rpg.controller.signal-interval-seconds: 0 (disabled) or "
                    + MIN_SIGNAL_INTERVAL + ".." + MAX_SIGNAL_INTERVAL
                    + " (got " + signal + ")");
        }
        int sweep = bounded(section, "sweep-interval-seconds", 60,
                MIN_SWEEP_INTERVAL, MAX_SWEEP_INTERVAL, issues);

        long stale = section.getLong("reservation-stale-seconds", 120L);
        if (stale < MIN_STALE_SECONDS || stale > MAX_STALE_SECONDS) {
            issues.add("rpg.controller.reservation-stale-seconds: must be between "
                    + MIN_STALE_SECONDS + " and " + MAX_STALE_SECONDS
                    + " (got " + stale + ")");
            stale = 120L;
        }

        List<Integer> buckets = loadBuckets(section, issues);

        RpgControllerConfig config = issues.isEmpty()
                ? new RpgControllerConfig(spawn, radius, signal, sweep, stale, buckets)
                : null;
        return new LoadResult(config, List.copyOf(issues));
    }

    private static int bounded(ConfigurationSection section, String key,
            int fallback, int min, int max, List<String> issues) {
        int value = section.getInt(key, fallback);
        if (value < min || value > max) {
            issues.add("rpg.controller." + key + ": must be between "
                    + min + " and " + max + " (got " + value + ")");
            return fallback;
        }
        return value;
    }

    private static List<Integer> loadBuckets(ConfigurationSection section,
            List<String> issues) {
        List<Integer> raw = section.getIntegerList("distance-buckets");
        if (raw.isEmpty()) {
            return DEFAULT_BUCKETS;
        }
        if (raw.size() < MIN_BUCKETS || raw.size() > MAX_BUCKETS) {
            issues.add("rpg.controller.distance-buckets: " + MIN_BUCKETS + ".."
                    + MAX_BUCKETS + " ascending edges (got " + raw.size() + ")");
            return DEFAULT_BUCKETS;
        }
        int previous = 0;
        for (int i = 0; i < raw.size(); i++) {
            int edge = raw.get(i);
            if (edge < MIN_BUCKET_EDGE || edge > MAX_BUCKET_EDGE) {
                issues.add("rpg.controller.distance-buckets[" + i + "]: must be "
                        + MIN_BUCKET_EDGE + ".." + MAX_BUCKET_EDGE
                        + " (got " + edge + ")");
                return DEFAULT_BUCKETS;
            }
            if (edge <= previous) {
                issues.add("rpg.controller.distance-buckets: must be strictly "
                        + "ascending (got " + raw + ")");
                return DEFAULT_BUCKETS;
            }
            previous = edge;
        }
        return List.copyOf(raw);
    }
}
