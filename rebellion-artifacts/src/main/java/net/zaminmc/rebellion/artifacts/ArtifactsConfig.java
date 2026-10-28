package net.zaminmc.rebellion.artifacts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Validated snapshot of RebellionArtifacts' {@code config.yml}. Immutable; a
 * reload swaps the whole record atomically. Every field is checked at load
 * and every issue is collected, following the established DragonsConfig /
 * TeamsConfig / WardsConfig pattern (ADR-0004).
 *
 * <h2>Sections</h2>
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
 *   <li><strong>Controller values (ADR-0015)</strong> — the physical item
 *       material per dimension, the sweep cadence, the occurrence duration
 *       and reminder cadence, and the fully gated purge power: default-off,
 *       ONLINE target set only (STORED is a loud unimplemented error until
 *       a profiles module can enumerate stored records), holder spared by
 *       default, a minimum telegraph after trigger, and a warning lead
 *       before the kill loop runs.</li>
 * </ul>
 */
public record ArtifactsConfig(
        @NotNull Map<ArtifactKey, OriginAnchor> origins,
        long returnDelaySeconds,
        boolean rebellionEventEnabled,
        @NotNull Map<ArtifactKey, Material> itemMaterials,
        int sweepIntervalSeconds,
        int eventDurationMinutes,
        int eventReminderMinutes,
        @NotNull PurgeSettings purge) {

    /** Config-owned protected-origin anchor of one dimension artifact. */
    public record OriginAnchor(@NotNull String world, int x, int y, int z) {
    }

    /**
     * The gated world-wide purge power (ADR-0015 §4, master prompt §5.5).
     * Ships disabled; every knob is bounded and validated.
     */
    public record PurgeSettings(
            boolean enabled,
            @NotNull String targetSet,
            boolean spareHolder,
            int minDelayMinutes,
            int warningSeconds) {

        public static final String TARGET_ONLINE = "ONLINE";
        public static final String TARGET_STORED = "STORED";

        public boolean targetsOnline() {
            return TARGET_ONLINE.equals(targetSet);
        }
    }

    /** Structural bounds (portable validation; mirrors the INT column ranges). */
    public static final int MAX_WORLD_NAME = 64;
    public static final int MAX_XZ = 30_000_000;
    public static final int Y_MIN = -64;
    public static final int Y_MAX = 320;
    public static final long MAX_RETURN_DELAY_SECONDS = 2_592_000L; // 30 days
    public static final int MIN_SWEEP_SECONDS = 5;
    public static final int MAX_SWEEP_SECONDS = 600;
    public static final int MIN_DURATION_MINUTES = 5;
    public static final int MAX_DURATION_MINUTES = 1440;
    public static final int MAX_REMINDER_MINUTES = 1440;
    public static final int MAX_PURGE_DELAY_MINUTES = 1440;
    public static final int MIN_PURGE_WARNING_SECONDS = 5;
    public static final int MAX_PURGE_WARNING_SECONDS = 600;
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
        itemMaterials = Map.copyOf(itemMaterials);
        if (origins.size() != ArtifactsPolicy.ARTIFACT_COUNT) {
            throw new IllegalArgumentException(
                    "exactly " + ArtifactsPolicy.ARTIFACT_COUNT
                            + " origin anchors required (got " + origins.size() + ")");
        }
        if (itemMaterials.size() != ArtifactsPolicy.ARTIFACT_COUNT) {
            throw new IllegalArgumentException(
                    "exactly " + ArtifactsPolicy.ARTIFACT_COUNT
                            + " item materials required (got " + itemMaterials.size()
                            + ")");
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

    /** @return the configured physical item material for one artifact. */
    public @NotNull Material itemMaterial(@NotNull ArtifactKey key) {
        Material material = itemMaterials.get(key);
        if (material == null) {
            throw new IllegalArgumentException("no item material for " + key);
        }
        return material;
    }

    /** @return the return delay in milliseconds (0 = eligible immediately). */
    public long returnDelayMs() {
        return returnDelaySeconds * 1000L;
    }

    /** Copy with a different return delay (test/operator convenience). */
    public @NotNull ArtifactsConfig withReturnDelay(long newDelaySeconds) {
        return new ArtifactsConfig(origins, newDelaySeconds, rebellionEventEnabled,
                itemMaterials, sweepIntervalSeconds, eventDurationMinutes,
                eventReminderMinutes, purge);
    }

    /** Copy with the rebellion-event toggle flipped. */
    public @NotNull ArtifactsConfig withEventEnabled(boolean enabled) {
        return new ArtifactsConfig(origins, returnDelaySeconds, enabled,
                itemMaterials, sweepIntervalSeconds, eventDurationMinutes,
                eventReminderMinutes, purge);
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
                3600L, true,
                new EnumMap<>(Map.of(
                        ArtifactKey.OVERWORLD, Material.HEART_OF_THE_SEA,
                        ArtifactKey.NETHER, Material.NETHER_STAR,
                        ArtifactKey.END, Material.ENDER_EYE)),
                30, 60, 10,
                new PurgeSettings(false, PurgeSettings.TARGET_ONLINE, true, 10, 30));
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

        Map<ArtifactKey, Material> materials = loadMaterials(yaml, issues);

        int sweep = yaml.getInt("artifacts.sweep.interval-seconds", 30);
        if (sweep < MIN_SWEEP_SECONDS || sweep > MAX_SWEEP_SECONDS) {
            issues.add("artifacts.sweep.interval-seconds: must be between "
                    + MIN_SWEEP_SECONDS + " and " + MAX_SWEEP_SECONDS
                    + " (got " + sweep + ")");
        }

        int duration = yaml.getInt("artifacts.event.duration-minutes", 60);
        if (duration < MIN_DURATION_MINUTES || duration > MAX_DURATION_MINUTES) {
            issues.add("artifacts.event.duration-minutes: must be between "
                    + MIN_DURATION_MINUTES + " and " + MAX_DURATION_MINUTES
                    + " (got " + duration + ")");
        }

        int reminder = yaml.getInt("artifacts.event.reminder-minutes", 10);
        if (reminder < 0 || reminder > MAX_REMINDER_MINUTES) {
            issues.add("artifacts.event.reminder-minutes: must be between 0 (off) and "
                    + MAX_REMINDER_MINUTES + " (got " + reminder + ")");
        }

        PurgeSettings purge = loadPurge(yaml, issues);

        ArtifactsConfig config = issues.isEmpty()
                ? new ArtifactsConfig(anchors, delay, enabled, materials, sweep,
                        duration, reminder, purge) : null;
        return new LoadResult(config, List.copyOf(issues));
    }

    private static Map<ArtifactKey, Material> loadMaterials(YamlConfiguration yaml,
            List<String> issues) {
        Map<ArtifactKey, Material> materials = new EnumMap<>(ArtifactKey.class);
        for (ArtifactKey key : ArtifactKey.values()) {
            String segment = "artifacts.items." + key.configSegment() + ".material";
            String raw = yaml.getString(segment, defaultMaterial(key).name());
            Material material = Material.matchMaterial(raw == null ? "" : raw.trim());
            // NOTE: Material.isAir()/isItem() are deliberately NOT used - they
            // route through RegistryAccess, which does not exist serverless
            // (Paper 26.3 API limit, ADR-0015 testing note). Enum-constant
            // comparison is registry-free; item-ness is enforced where the
            // ItemStack is actually built (server-side, boot-smoke verified).
            if (material == null || isAirConstant(material)) {
                issues.add(segment + ": must be a valid item material (got '" + raw
                        + "')");
            } else {
                materials.put(key, material);
            }
        }
        return materials;
    }

    private static boolean isAirConstant(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }

    private static PurgeSettings loadPurge(YamlConfiguration yaml, List<String> issues) {
        boolean enabled = yaml.getBoolean("artifacts.purge.enabled", false);
        String target = yaml.getString("artifacts.purge.target-set",
                PurgeSettings.TARGET_ONLINE);
        String trimmedTarget = target == null ? "" : target.trim().toUpperCase(
                java.util.Locale.ROOT);
        if (!PurgeSettings.TARGET_ONLINE.equals(trimmedTarget)) {
            issues.add("artifacts.purge.target-set: only '"
                    + PurgeSettings.TARGET_ONLINE + "' is implemented (got '" + target
                    + "') - the STORED target set needs a profiles module to "
                    + "enumerate stored player records (master prompt 5.5)");
        }
        boolean spareHolder = yaml.getBoolean("artifacts.purge.spare-holder", true);
        int minDelay = yaml.getInt("artifacts.purge.min-delay-minutes", 10);
        if (minDelay < 0 || minDelay > MAX_PURGE_DELAY_MINUTES) {
            issues.add("artifacts.purge.min-delay-minutes: must be between 0 and "
                    + MAX_PURGE_DELAY_MINUTES + " (got " + minDelay + ")");
        }
        int warning = yaml.getInt("artifacts.purge.warning-seconds", 30);
        if (warning < MIN_PURGE_WARNING_SECONDS || warning > MAX_PURGE_WARNING_SECONDS) {
            issues.add("artifacts.purge.warning-seconds: must be between "
                    + MIN_PURGE_WARNING_SECONDS + " and " + MAX_PURGE_WARNING_SECONDS
                    + " (got " + warning + ")");
        }
        return new PurgeSettings(enabled, trimmedTarget, spareHolder, minDelay, warning);
    }

    private static String defaultWorld(ArtifactKey key) {
        return switch (key) {
            case OVERWORLD -> DEFAULT_OVERWORLD_WORLD;
            case NETHER -> DEFAULT_NETHER_WORLD;
            case END -> DEFAULT_END_WORLD;
        };
    }

    private static Material defaultMaterial(ArtifactKey key) {
        return switch (key) {
            case OVERWORLD -> Material.HEART_OF_THE_SEA;
            case NETHER -> Material.NETHER_STAR;
            case END -> Material.ENDER_EYE;
        };
    }
}
