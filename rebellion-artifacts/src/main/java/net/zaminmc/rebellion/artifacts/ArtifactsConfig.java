package net.zaminmc.rebellion.artifacts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Validated snapshot of RebellionArtifacts' {@code config.yml}. Immutable; a
 * reload swaps the whole record atomically. Every field is checked at load
 * and every issue is collected, following the established DragonsConfig /
 * TeamsConfig / WardsConfig pattern (ADR-0004).
 *
 * <h2>What is here — and deliberately not here (ADR-0013)</h2>
 * <ul>
 *   <li>Per-dimension <strong>origin anchors</strong> — the protected,
 *       discoverable realm locations. These are config-owned world-setup
 *       data (the operator calibrates them when the map is imported), not
 *       gameplay state; the service re-syncs rows to them at every load.
 *       Defaults use the conventional world names and spawn-ish anchors —
 *       honest placeholders, documented as "calibrate on import".</li>
 *   <li><strong>Return delay</strong> — how long a LOST/SPENT artifact must
 *       sit before the return sweep may route it home (master prompt §5.5:
 *       "after a configurable return delay and event check").</li>
 *   <li><strong>Rebellion-event toggle and trigger scope</strong> — the
 *       default trigger scope is one player controlling all three (HOLDER).
 *       A team-wide scope is rejected loudly as unimplemented: §5.5 demands
 *       it be a separate explicit configuration option, never a silent
 *       stretch.</li>
 *   <li>Power cooldowns, catastrophe effects, the optional world-wide purge
 *       action and encounter tuning are <em>controller-phase</em> values and
 *       are deliberately absent until the live-server proofs exist (the same
 *       discipline DragonsConfig held before ADR-0008 landed).</li>
 * </ul>
 */
public record ArtifactsConfig(
        @NotNull Map<ArtifactKey, OriginAnchor> origins,
        long returnDelaySeconds,
        boolean rebellionEventEnabled) {

    /** Config-owned protected-origin anchor of one dimension artifact. */
    public record OriginAnchor(@NotNull String world, int x, int y, int z) {
    }

    /** Structural bounds (portable validation; mirrors the INT column ranges). */
    public static final int MAX_WORLD_NAME = 64;
    public static final int MAX_XZ = 30_000_000;
    public static final int Y_MIN = -64;
    public static final int Y_MAX = 320;
    public static final long MAX_RETURN_DELAY_SECONDS = 2_592_000L; // 30 days
    public static final String TRIGGER_SCOPE_HOLDER = "HOLDER";

    /** Conventional default world names per dimension (calibrate on import). */
    static final String DEFAULT_OVERWORLD_WORLD = "world";
    static final String DEFAULT_NETHER_WORLD = "world_nether";
    static final String DEFAULT_END_WORLD = "world_the_end";

    public record LoadResult(ArtifactsConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    public ArtifactsConfig {
        origins = Map.copyOf(origins);
        if (origins.size() != ArtifactsPolicy.ARTIFACT_COUNT) {
            throw new IllegalArgumentException(
                    "exactly " + ArtifactsPolicy.ARTIFACT_COUNT
                            + " origin anchors required (got " + origins.size() + ")");
        }
    }

    /** @return the configured origin anchor for one artifact. */
    public @NotNull OriginAnchor origin(@NotNull ArtifactKey key) {
        OriginAnchor anchor = origins.get(key);
        if (anchor == null) {
            throw new IllegalArgumentException("no origin anchor for " + key);
        }
        return anchor;
    }

    /** @return the return delay in milliseconds (0 = eligible immediately). */
    public long returnDelayMs() {
        return returnDelaySeconds * 1000L;
    }

    /** Copy with a different return delay (test/operator convenience). */
    public @NotNull ArtifactsConfig withReturnDelay(long newDelaySeconds) {
        return new ArtifactsConfig(origins, newDelaySeconds, rebellionEventEnabled);
    }

    /** Copy with the rebellion-event toggle flipped. */
    public @NotNull ArtifactsConfig withEventEnabled(boolean enabled) {
        return new ArtifactsConfig(origins, returnDelaySeconds, enabled);
    }

    /**
     * Test/operator convenience: the shipped defaults. Mirrors the bundled
     * config.yml values exactly (kept in sync by ArtifactsConfigTest).
     */
    public static ArtifactsConfig defaults() {
        return new ArtifactsConfig(new EnumMap<>(Map.of(
                ArtifactKey.OVERWORLD, new OriginAnchor(DEFAULT_OVERWORLD_WORLD, 0, 64, 0),
                ArtifactKey.NETHER, new OriginAnchor(DEFAULT_NETHER_WORLD, 0, 64, 0),
                ArtifactKey.END, new OriginAnchor(DEFAULT_END_WORLD, 0, 64, 0))),
                3600L, true);
    }

    public static ArtifactsConfig load(YamlConfiguration yaml) {
        var result = loadResult(yaml);
        if (!result.valid()) {
            throw new IllegalStateException("config invalid: " + result.issues());
        }
        return result.config();
    }

    /** @return candidate snapshot (null when invalid) plus every issue found. */
    public static LoadResult loadResult(YamlConfiguration yaml) {
        List<String> issues = new ArrayList<>();

        Map<ArtifactKey, OriginAnchor> anchors = new EnumMap<>(ArtifactKey.class);
        for (ArtifactKey key : ArtifactKey.values()) {
            String segment = "artifacts.origins." + key.configSegment();
            String world = yaml.getString(segment + ".world", defaultWorld(key));
            if (world == null || world.isBlank() || world.length() > MAX_WORLD_NAME) {
                issues.add(segment + ".world: must be 1.." + MAX_WORLD_NAME
                        + " characters (got '" + world + "')");
                continue;
            }
            int x = yaml.getInt(segment + ".x", 0);
            int y = yaml.getInt(segment + ".y", 64);
            int z = yaml.getInt(segment + ".z", 0);
            if (Math.abs(x) > MAX_XZ || Math.abs(z) > MAX_XZ) {
                issues.add(segment + ".x/.z: must be within the world border (|coord| <= "
                        + MAX_XZ + "; got x=" + x + ", z=" + z + ")");
            }
            if (y < Y_MIN || y > Y_MAX) {
                issues.add(segment + ".y: must be between " + Y_MIN + " and " + Y_MAX
                        + " (got " + y + ")");
            }
            if (issues.stream().noneMatch(i -> i.startsWith(segment + "."))) {
                anchors.put(key, new OriginAnchor(world.trim(), x, y, z));
            }
        }
        if (anchors.size() != ArtifactsPolicy.ARTIFACT_COUNT) {
            issues.add("artifacts.origins: every dimension needs a valid origin anchor");
        }

        long delay = yaml.getLong("artifacts.return.delay-seconds", 3600L);
        if (delay < 0 || delay > MAX_RETURN_DELAY_SECONDS) {
            issues.add("artifacts.return.delay-seconds: must be between 0 and "
                    + MAX_RETURN_DELAY_SECONDS + " (got " + delay + ")");
        }

        boolean enabled = yaml.getBoolean("artifacts.rebellion-event.enabled", true);
        String scope = yaml.getString("artifacts.rebellion-event.trigger-scope",
                TRIGGER_SCOPE_HOLDER);
        if (!TRIGGER_SCOPE_HOLDER.equalsIgnoreCase(scope == null ? "" : scope.trim())) {
            issues.add("artifacts.rebellion-event.trigger-scope: only '"
                    + TRIGGER_SCOPE_HOLDER + "' is implemented (got '" + scope
                    + "') - a team-wide trigger scope must be a separate explicit "
                    + "configuration option (master prompt 5.5)");
        }

        ArtifactsConfig config = issues.isEmpty()
                ? new ArtifactsConfig(anchors, delay, enabled) : null;
        return new LoadResult(config, List.copyOf(issues));
    }

    private static String defaultWorld(ArtifactKey key) {
        return switch (key) {
            case OVERWORLD -> DEFAULT_OVERWORLD_WORLD;
            case NETHER -> DEFAULT_NETHER_WORLD;
            case END -> DEFAULT_END_WORLD;
        };
    }
}
