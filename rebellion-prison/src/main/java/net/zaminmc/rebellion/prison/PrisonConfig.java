package net.zaminmc.rebellion.prison;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Validated snapshot of RebellionPrison's operator contract (ADR-0021).
 * Immutable; a reload swaps the whole record atomically — the established
 * RpgConfig/AlchemyConfig pattern (ADR-0004).
 *
 * <h2>Families (split per master prompt §11)</h2>
 * <ul>
 *   <li><strong>death</strong> — what one processed death does: the death
 *       score increment, the health-penalty points scarred onto the
 *       profile, the hard health floor (§5.8: "Set a hard floor and
 *       recovery path so it is possible to continue playing"), the
 *       vanilla max-health reference the floor is subtracted from, and the
 *       imprisonment routing policy ({@code ALWAYS} by default — §5.8
 *       routes every valid death into the prison; {@code NEVER} scores
 *       without jailing; {@code SCORE_THRESHOLD} jails once the
 *       accumulated score reaches the threshold).</li>
 *   <li><strong>release</strong> — the §5.8 fee formula inputs:
 *       {@code inflation-factor} (exact decimal, default 1.00),
 *       {@code multiplier} (product constant, default 5),
 *       {@code rounding} (default HALF_UP) and the minor-unit
 *       {@code min}/{@code max} clamps. Validation keeps the max inside
 *       long-safe range so the §5.9 overflow rule holds structurally.</li>
 *   <li><strong>rehab</strong> — the keyed rehabilitation tasks/objectives
 *       (§5.8: "configured rehabilitation tasks"): each carries the
 *       health points restored per completion and how many times it may
 *       be completed. Completion idempotency and scar clamping live in
 *       the service + schema, not the config.</li>
 *   <li><strong>prison</strong> — the configured jail anchor (world name +
 *       block coordinates + facing) stamped onto every PENDING sentence
 *       at creation. Serverless: the world is a validated name string
 *       here; the controller phase resolves it against live worlds and
 *       owns the fail-closed behavior for a missing world (§5.8).</li>
 * </ul>
 */
public record PrisonConfig(@NotNull DeathPolicy death, @NotNull ReleasePolicy release,
        @NotNull Map<String, RehabTask> rehab, @NotNull Anchor anchor) {

    /** Config key grammar (stable operator contract). */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");

    /** Death-key / completion-key grammar (controller-composed identities). */
    public static final Pattern EVENT_KEY = Pattern.compile("[a-z0-9_:\\-]{1,96}");

    /** World-name grammar: letters, digits, underscore, hyphen — no paths. */
    public static final Pattern WORLD = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    /** Minecraft block-coordinate bound (vanilla world border). */
    public static final long COORD_LIMIT = 30_000_000L;

    public PrisonConfig {
        rehab = Map.copyOf(rehab);
    }

    /** @return the rehabilitation task for a key, when configured. */
    public @NotNull RehabTask rehabTask(@NotNull String key) {
        RehabTask task = rehab.get(key);
        if (task == null) {
            throw new IllegalArgumentException("no rehab task for '" + key + "'");
        }
        return task;
    }

    // ------------------------------------------------------------------
    // Family records
    // ------------------------------------------------------------------

    /**
     * What one processed death does. {@code maxHealthPoints} and
     * {@code healthFloorPoints} define the <em>active-penalty ceiling</em>
     * ({@code max - floor}): the most max-health a player can ever lose.
     */
    public record DeathPolicy(long scorePerDeath, int healthPenaltyPoints,
            int healthFloorPoints, int maxHealthPoints, @NotNull Route route,
            long imprisonThreshold) {

        public enum Route { ALWAYS, NEVER, SCORE_THRESHOLD }

        /** @return the ceiling scar points may accumulate to (write clamp). */
        public int maxActivePenalty() {
            return maxHealthPoints - healthFloorPoints;
        }
    }

    /** The §5.8 fee formula inputs, validated for exact-decimal safety. */
    public record ReleasePolicy(@NotNull BigDecimal inflationFactor, int multiplier,
            @NotNull RoundingMode rounding, long minFeeMinor, long maxFeeMinor) {

        /** Keeps {@code max} small enough that clamped math cannot overflow. */
        public static final long MAX_FEE_LIMIT = 1L << 62;
    }

    /** One keyed rehabilitation objective. */
    public record RehabTask(@NotNull String key, boolean enabled, int healPoints,
            int maxOccurrences) {
    }

    /** The configured jail anchor stamped onto PENDING sentences. */
    public record Anchor(@NotNull String world, long x, long y, long z, int yaw,
            int pitch) {
    }

    public record LoadResult(PrisonConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Test/operator convenience: the shipped defaults. Every death scores
     * 1 and scars one heart (2 points) down to a 3-heart floor (6 points
     * of a 20-point vanilla bar); every death jails; the fee formula is
     * {@code score × 1.00 × 5} rounded HALF_UP with generous clamps; two
     * rehabilitation objectives restore the bar.
     */
    public static PrisonConfig defaults() {
        return new PrisonConfig(
                new DeathPolicy(1L, 2, 6, 20, DeathPolicy.Route.ALWAYS, 3L),
                new ReleasePolicy(new BigDecimal("1.00"), 5, RoundingMode.HALF_UP,
                        0L, 1_000_000_000_000L),
                Map.of(
                        "convalescence_rest", new RehabTask("convalescence_rest",
                                true, 2, 7),
                        "field_medicine", new RehabTask("field_medicine", true, 6, 1)),
                new Anchor("world_prison", 0, 64, 0, 0, 0));
    }

    /** @return the config parsed from a YAML document, collecting every issue. */
    public static LoadResult load(@NotNull String yaml) {
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            return new LoadResult(null, List.of("not valid YAML: " + e.getMessage()));
        }
        return loadResult(parsed);
    }

    /** @return the config read from an already-loaded document. */
    public static LoadResult loadResult(@NotNull YamlConfiguration root) {
        List<String> issues = new ArrayList<>();
        DeathPolicy death = loadDeath(root, issues);
        ReleasePolicy release = loadRelease(root, issues);
        Map<String, RehabTask> rehab = loadRehab(root, death, issues);
        Anchor anchor = loadAnchor(root, issues);
        if (!issues.isEmpty()) {
            return new LoadResult(null, List.copyOf(issues));
        }
        return new LoadResult(new PrisonConfig(death, release, rehab, anchor),
                List.of());
    }

    // ------------------------------------------------------------------
    // Family loaders (collect-all)
    // ------------------------------------------------------------------

    private static DeathPolicy loadDeath(ConfigurationSection root,
            List<String> issues) {
        long score = longOf(root, "death.score-per-death", 1L, 0L,
                Long.MAX_VALUE / 4, issues);
        int penalty = intOf(root, "death.health-penalty-points", 2, 0, 1024, issues);
        int floor = intOf(root, "death.health-floor-points", 6, 0, 1024, issues);
        int maxHealth = intOf(root, "death.max-health-points", 20, 1, 1024, issues);
        if (floor >= maxHealth) {
            issues.add("death.health-floor-points (" + floor + ") must be below"
                    + " death.max-health-points (" + maxHealth + ") — a floor at or"
                    + " above the bar would leave zero health");
        }
        String routeRaw = stringOf(root, "death.imprison-route", "ALWAYS", issues);
        DeathPolicy.Route route;
        try {
            route = DeathPolicy.Route.valueOf(
                    routeRaw.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            issues.add("death.imprison-route must be ALWAYS, NEVER or"
                    + " SCORE_THRESHOLD (got '" + routeRaw + "')");
            route = DeathPolicy.Route.ALWAYS;
        }
        long threshold = longOf(root, "death.imprison-threshold", 3L, 1L,
                Long.MAX_VALUE / 4, issues);
        if (route == DeathPolicy.Route.SCORE_THRESHOLD && threshold < 1) {
            issues.add("death.imprison-threshold must be >= 1 for the"
                    + " SCORE_THRESHOLD route");
        }
        return new DeathPolicy(score, penalty, floor, maxHealth, route, threshold);
    }

    private static ReleasePolicy loadRelease(ConfigurationSection root,
            List<String> issues) {
        String factorRaw = stringOf(root, "release.inflation-factor", "1.00", issues);
        BigDecimal factor;
        try {
            factor = new BigDecimal(factorRaw);
        } catch (NumberFormatException e) {
            issues.add("release.inflation-factor must be an exact decimal (got '"
                    + factorRaw + "')");
            factor = BigDecimal.ONE;
        }
        if (factor.scale() > 6 || factor.compareTo(BigDecimal.ZERO) < 0
                || factor.compareTo(new BigDecimal(1_000_000)) > 0) {
            issues.add("release.inflation-factor must be 0..1000000 with at most"
                    + " 6 decimal places (got '" + factorRaw + "')");
            factor = factor.max(BigDecimal.ZERO);
        }
        int multiplier = intOf(root, "release.multiplier", 5, 1, 100, issues);
        String roundingRaw = stringOf(root, "release.rounding", "HALF_UP", issues);
        RoundingMode rounding;
        try {
            rounding = RoundingMode.valueOf(roundingRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            issues.add("release.rounding must be a java.math.RoundingMode name"
                    + " (got '" + roundingRaw + "')");
            rounding = RoundingMode.HALF_UP;
        }
        long min = longOf(root, "release.min-fee", 0L, 0L,
                ReleasePolicy.MAX_FEE_LIMIT, issues);
        long max = longOf(root, "release.max-fee", 1_000_000_000_000L, 0L,
                ReleasePolicy.MAX_FEE_LIMIT, issues);
        if (max < min) {
            issues.add("release.max-fee (" + max + ") must be >= release.min-fee ("
                    + min + ")");
        }
        return new ReleasePolicy(factor, multiplier, rounding, min, max);
    }

    private static Map<String, RehabTask> loadRehab(ConfigurationSection root,
            DeathPolicy death, List<String> issues) {
        Map<String, RehabTask> tasks = new LinkedHashMap<>();
        ConfigurationSection section = root.getConfigurationSection("rehab.tasks");
        int ceiling = death.maxHealthPoints() - death.healthFloorPoints();
        if (section == null) {
            tasks.put("convalescence_rest", new RehabTask("convalescence_rest",
                    true, 2, 7));
            tasks.put("field_medicine", new RehabTask("field_medicine", true, 6, 1));
            return tasks;
        }
        for (String key : section.getKeys(false)) {
            if (!KEY.matcher(key).matches()) {
                issues.add("rehab.tasks key '" + key + "' must match"
                        + " [a-z0-9_]{1,32}");
                continue;
            }
            ConfigurationSection task = section.getConfigurationSection(key);
            if (task == null) {
                issues.add("rehab.tasks." + key + " must be a section");
                continue;
            }
            boolean enabled = task.getBoolean("enabled", true);
            int heal = task.getInt("heal-points", 1);
            int occurrences = task.getInt("max-occurrences", 1);
            if (heal < 1 || heal > ceiling) {
                issues.add("rehab.tasks." + key + ".heal-points must be 1.." + ceiling
                        + " (the active-penalty ceiling; got " + heal + ")");
                heal = Math.max(1, Math.min(ceiling, heal));
            }
            if (occurrences < 1 || occurrences > 1000) {
                issues.add("rehab.tasks." + key + ".max-occurrences must be"
                        + " 1..1000 (got " + occurrences + ")");
                occurrences = Math.max(1, Math.min(1000, occurrences));
            }
            tasks.put(key, new RehabTask(key, enabled, heal, occurrences));
        }
        return tasks;
    }

    private static Anchor loadAnchor(ConfigurationSection root,
            List<String> issues) {
        String world = stringOf(root, "prison.world", "world_prison", issues);
        if (!WORLD.matcher(world).matches()) {
            issues.add("prison.world must match [A-Za-z0-9_\\-]{1,64} (got '"
                    + world + "')");
        }
        long x = longOf(root, "prison.x", 0L, -COORD_LIMIT, COORD_LIMIT, issues);
        long y = longOf(root, "prison.y", 64L, -2048L, 4096L, issues);
        long z = longOf(root, "prison.z", 0L, -COORD_LIMIT, COORD_LIMIT, issues);
        int yaw = intOf(root, "prison.yaw", 0, -180, 180, issues);
        int pitch = intOf(root, "prison.pitch", 0, -90, 90, issues);
        return new Anchor(world, x, y, z, yaw, pitch);
    }

    // ------------------------------------------------------------------
    // Bounded readers (defaults on missing, collected issues on invalid)
    // ------------------------------------------------------------------

    private static long longOf(ConfigurationSection root, String path, long fallback,
            long min, long max, List<String> issues) {
        Object raw = root.get(path);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number n) {
            long value = n.longValue();
            if (value < min || value > max) {
                issues.add(path + " must be " + min + ".." + max + " (got " + value
                        + ")");
                return Math.max(min, Math.min(max, value));
            }
            return value;
        }
        issues.add(path + ": expected an integer, got '" + raw + "'");
        return fallback;
    }

    private static int intOf(ConfigurationSection root, String path, int fallback,
            int min, int max, List<String> issues) {
        return (int) longOf(root, path, fallback, min, max, issues);
    }

    private static String stringOf(ConfigurationSection root, String path,
            String fallback, List<String> issues) {
        Object raw = root.get(path);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof String s) {
            return s;
        }
        issues.add(path + ": expected text, got '" + raw + "'");
        return fallback;
    }
}
