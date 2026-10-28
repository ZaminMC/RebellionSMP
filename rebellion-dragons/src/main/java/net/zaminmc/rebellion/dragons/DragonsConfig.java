package net.zaminmc.rebellion.dragons;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Validated snapshot of RebellionDragons' {@code config.yml}. Immutable; a
 * reload swaps the whole record atomically. Every field is checked at load
 * and every issue is collected, following the established TeamsConfig /
 * WardsConfig pattern (ADR-0004).
 *
 * <h2>Where the controller numbers come from (ADR-0008, measured)</h2>
 * <ul>
 *   <li>{@code hatch-health} defaults to 200.0 — the vanilla dragon maximum
 *       observed live on Paper 26.3 build 226 (E3/E5).</li>
 *   <li>{@code pearl-heal.amount} defaults to 25.0 — E5 measured
 *       {@code heal(25)} as exactly +25 with no overshoot or phase
 *       interference; {@code heal(500)} clamps at max.</li>
 *   <li>The fireball budget honors master prompt §5.6: at most 30 fireballs
 *       per dragon per rolling 60-second window (stricter configurable).</li>
 *   <li>{@code ride.enabled} defaults to <strong>false</strong>: the
 *       client-side ride FEEL is one of the two remaining interactive gates
 *       (ADR-0008). The control loop ships disabled-by-configuration until
 *       that gate passes.</li>
 *   <li>The boss-victory gateway scan bounds are the only values here that
 *       are <em>not</em> measured: the vanilla gateway ring geometry on 26.3
 *       is observed interactively (ADR-0008 E4b). Defaults are generous
 *       (radius 48..160, full height band); a miss fails closed (no mint)
 *       with a loud WARN, never a wrong mint.</li>
 * </ul>
 */
public record DragonsConfig(
        int maxLineages,
        String eggMaterial,
        double hatchHealth,
        int hatchOffsetForward,
        int hatchOffsetUp,
        int callMaxRangeBlocks,
        int callCooldownSeconds,
        int callHoverOffsetBlocks,
        boolean rideEnabled,
        int steerIntervalTicks,
        int steerLookAheadBlocks,
        double attackRangeBlocks,
        int fireballMaxPerWindow,
        int fireballWindowSeconds,
        long fireballMinIntervalMs,
        double pearlHealAmount,
        int pearlHealCooldownSeconds,
        double waterWeaknessMultiplier,
        boolean victoryObservationEnabled,
        int scanRadiusMinBlocks,
        int scanRadiusMaxBlocks,
        int scanYMin,
        int scanYMax,
        int scanPollIntervalTicks,
        int scanWindowSeconds) {

    /** Structural bounds (LineagePolicy carries the lineage cap). */
    public static final double MAX_HATCH_OFFSET = 64.0;
    public static final int MAX_INTERVAL_TICKS = 200;
    public static final int MAX_COOLDOWN_SECONDS = 86_400;
    public static final double MAX_ATTACK_RANGE = 256.0;
    public static final double MAX_WATER_MULTIPLIER = 4.0;
    public static final int MAX_FIREBALLS_PER_WINDOW = 120;
    public static final int MAX_SCAN_RADIUS = 512;
    public static final int MAX_SCAN_WINDOW_SECONDS = 600;

    public record LoadResult(DragonsConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    /**
     * Test/operator convenience: the shipped defaults with only the lineage
     * cap parameterized. Mirrors the bundled config.yml values exactly
     * (kept in sync by DragonsConfigTest).
     */
    public static DragonsConfig defaults(int maxLineages) {
        return new DragonsConfig(maxLineages, "DRAGON_EGG", 200.0, 12, 12,
                512, 30, 8, false, 8, 48, 64.0, 30, 60, 2000L, 25.0, 10,
                1.25, true, 48, 160, 0, 256, 40, 90);
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

        String egg = yaml.getString("dragons.egg.material", "DRAGON_EGG");
        if (egg == null || Material.matchMaterial(egg) == null) {
            issues.add("dragons.egg.material: unknown material '" + egg
                    + "' (expected a Material name like DRAGON_EGG)");
        }

        double hatchHealth = yaml.getDouble("dragons.pet.hatch-health", 200.0);
        if (!Double.isFinite(hatchHealth) || hatchHealth <= 0.0
                || hatchHealth > LineagePolicy.MAX_HATCH_HEALTH) {
            issues.add("dragons.pet.hatch-health: must be finite in 0 < health <= "
                    + (int) LineagePolicy.MAX_HATCH_HEALTH + " (got " + hatchHealth + ")");
        }

        int hatchFwd = yaml.getInt("dragons.pet.hatch-offset-forward", 12);
        int hatchUp = yaml.getInt("dragons.pet.hatch-offset-up", 12);
        if (hatchFwd < 0 || hatchFwd > MAX_HATCH_OFFSET) {
            issues.add("dragons.pet.hatch-offset-forward: must be between 0 and "
                    + (int) MAX_HATCH_OFFSET + " blocks (got " + hatchFwd + ")");
        }
        if (hatchUp < 1 || hatchUp > MAX_HATCH_OFFSET) {
            issues.add("dragons.pet.hatch-offset-up: must be between 1 and "
                    + (int) MAX_HATCH_OFFSET + " blocks (got " + hatchUp + ")");
        }

        int callRange = yaml.getInt("dragons.pet.call.max-range-blocks", 512);
        if (callRange < 16 || callRange > 100_000) {
            issues.add("dragons.pet.call.max-range-blocks: must be between 16 and 100000 (got "
                    + callRange + ")");
        }
        int callCooldown = yaml.getInt("dragons.pet.call.cooldown-seconds", 30);
        if (callCooldown < 0 || callCooldown > MAX_COOLDOWN_SECONDS) {
            issues.add("dragons.pet.call.cooldown-seconds: must be between 0 and "
                    + MAX_COOLDOWN_SECONDS + " (got " + callCooldown + ")");
        }
        int callHover = yaml.getInt("dragons.pet.call.hover-offset-blocks", 8);
        if (callHover < 1 || callHover > MAX_HATCH_OFFSET) {
            issues.add("dragons.pet.call.hover-offset-blocks: must be between 1 and "
                    + (int) MAX_HATCH_OFFSET + " (got " + callHover + ")");
        }

        boolean ride = yaml.getBoolean("dragons.pet.ride.enabled", false);
        int steerInterval = yaml.getInt("dragons.pet.ride.steer-interval-ticks", 8);
        if (steerInterval < 1 || steerInterval > MAX_INTERVAL_TICKS) {
            issues.add("dragons.pet.ride.steer-interval-ticks: must be between 1 and "
                    + MAX_INTERVAL_TICKS + " ticks (got " + steerInterval + ")");
        }
        int lookAhead = yaml.getInt("dragons.pet.ride.look-ahead-blocks", 48);
        if (lookAhead < 8 || lookAhead > 256) {
            issues.add("dragons.pet.ride.look-ahead-blocks: must be between 8 and 256 blocks (got "
                    + lookAhead + ")");
        }

        double attackRange = yaml.getDouble("dragons.pet.attack.range-blocks", 64.0);
        if (!Double.isFinite(attackRange) || attackRange <= 0.0
                || attackRange > MAX_ATTACK_RANGE) {
            issues.add("dragons.pet.attack.range-blocks: must be finite in 0 < range <= "
                    + (int) MAX_ATTACK_RANGE + " (got " + attackRange + ")");
        }
        int fireballs = yaml.getInt("dragons.pet.attack.max-per-window", 30);
        if (fireballs < 1 || fireballs > MAX_FIREBALLS_PER_WINDOW) {
            issues.add("dragons.pet.attack.max-per-window: must be between 1 and "
                    + MAX_FIREBALLS_PER_WINDOW + " (got " + fireballs
                    + ") - hard upper bound keeps scheduler and network load bounded");
        }
        int window = yaml.getInt("dragons.pet.attack.window-seconds", 60);
        if (window < 1 || window > 600) {
            issues.add("dragons.pet.attack.window-seconds: must be between 1 and 600 (got "
                    + window + ")");
        }
        long minInterval = yaml.getLong("dragons.pet.attack.min-interval-ms", 2000L);
        if (minInterval < 0 || minInterval > 60_000L) {
            issues.add("dragons.pet.attack.min-interval-ms: must be between 0 and 60000 ms (got "
                    + minInterval + ")");
        }

        double pearlAmount = yaml.getDouble("dragons.pet.pearl-heal.amount", 25.0);
        if (!Double.isFinite(pearlAmount) || pearlAmount <= 0.0 || pearlAmount > 1024.0) {
            issues.add("dragons.pet.pearl-heal.amount: must be finite in 0 < amount <= 1024 (got "
                    + pearlAmount + ")");
        }
        int pearlCooldown = yaml.getInt("dragons.pet.pearl-heal.cooldown-seconds", 10);
        if (pearlCooldown < 0 || pearlCooldown > MAX_COOLDOWN_SECONDS) {
            issues.add("dragons.pet.pearl-heal.cooldown-seconds: must be between 0 and "
                    + MAX_COOLDOWN_SECONDS + " (got " + pearlCooldown + ")");
        }

        double water = yaml.getDouble("dragons.pet.water-weakness.damage-multiplier", 1.25);
        if (!Double.isFinite(water) || water < 1.0 || water > MAX_WATER_MULTIPLIER) {
            issues.add("dragons.pet.water-weakness.damage-multiplier: must be finite in "
                    + "1.0 <= multiplier <= " + (int) MAX_WATER_MULTIPLIER + " (got " + water
                    + ") - a slight configurable penalty, never an instant death");
        }

        boolean victory = yaml.getBoolean("dragons.boss-victory.observe-enabled", true);
        int rMin = yaml.getInt("dragons.boss-victory.scan.radius-min-blocks", 48);
        int rMax = yaml.getInt("dragons.boss-victory.scan.radius-max-blocks", 160);
        if (rMin < 0 || rMin > MAX_SCAN_RADIUS) {
            issues.add("dragons.boss-victory.scan.radius-min-blocks: must be between 0 and "
                    + MAX_SCAN_RADIUS + " (got " + rMin + ")");
        }
        if (rMax < rMin + 16 || rMax > MAX_SCAN_RADIUS) {
            issues.add("dragons.boss-victory.scan.radius-max-blocks: must be >= radius-min + 16 "
                    + "and <= " + MAX_SCAN_RADIUS + " (got " + rMax + ")");
        }
        int yMin = yaml.getInt("dragons.boss-victory.scan.y-min", 0);
        int yMax = yaml.getInt("dragons.boss-victory.scan.y-max", 256);
        if (yMin < -64 || yMin > 320 || yMax <= yMin || yMax > 320) {
            issues.add("dragons.boss-victory.scan.y-min/y-max: y-min must be in -64..320 and "
                    + "y-max in (y-min)..320 (got y-min=" + yMin + ", y-max=" + yMax + ")");
        }
        int poll = yaml.getInt("dragons.boss-victory.scan.poll-interval-ticks", 40);
        if (poll < 1 || poll > MAX_INTERVAL_TICKS) {
            issues.add("dragons.boss-victory.scan.poll-interval-ticks: must be between 1 and "
                    + MAX_INTERVAL_TICKS + " ticks (got " + poll + ")");
        }
        int scanWindow = yaml.getInt("dragons.boss-victory.scan.window-seconds", 90);
        if (scanWindow < 10 || scanWindow > MAX_SCAN_WINDOW_SECONDS) {
            issues.add("dragons.boss-victory.scan.window-seconds: must be between 10 and "
                    + MAX_SCAN_WINDOW_SECONDS + " (got " + scanWindow + ")");
        }

        DragonsConfig config = issues.isEmpty() ? new DragonsConfig(cap, egg, hatchHealth,
                hatchFwd, hatchUp, callRange, callCooldown, callHover, ride, steerInterval,
                lookAhead, attackRange, fireballs, window, minInterval, pearlAmount,
                pearlCooldown, water, victory, rMin, rMax, yMin, yMax, poll, scanWindow)
                : null;
        return new LoadResult(config, List.copyOf(issues));
    }

    /** @return the effective mint cap, clamped to the hard policy ceiling. */
    public int effectiveMaxLineages() {
        return LineagePolicy.effectiveCap(maxLineages);
    }

    /** @return the pearl-heal cooldown in milliseconds. */
    public long pearlHealCooldownMs() {
        return pearlHealCooldownSeconds * 1000L;
    }

    /** @return the rolling fireball window in milliseconds. */
    public long fireballWindowMs() {
        return fireballWindowSeconds * 1000L;
    }

    /** @return the call cooldown in milliseconds. */
    public long callCooldownMs() {
        return callCooldownSeconds * 1000L;
    }

    /** @return the victory observation window in milliseconds. */
    public long scanWindowMs() {
        return scanWindowSeconds * 1000L;
    }
}
