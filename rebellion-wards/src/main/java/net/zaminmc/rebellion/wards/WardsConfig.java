package net.zaminmc.rebellion.wards;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Validated snapshot of RebellionWards' {@code config.yml}. Immutable; a
 * reload swaps the whole record atomically. Every field is checked at load
 * and every issue is collected, following the established TeamsConfig
 * pattern (ADR-0004).
 *
 * <p>The kilometer gate is the load-bearing part of the calibration contract
 * (master prompt §5.2): {@code distance.world-units-per-kilometer} ships
 * EMPTY. An empty map means kilometers are disabled for every world and
 * {@link DistanceService} reports in the configured uncalibrated unit only.
 * Non-empty entries must be finite and strictly positive — a guessed or
 * zero scale can never reach the formatter.</p>
 */
public record WardsConfig(
        String anchorMaterial,
        int createReachBlocks,
        double minRadius,
        double maxRadius,
        int maxWardsPerPlayer,
        int maxWardsPerTeam,
        int maxWardsTotal,
        Set<String> manageRanks,
        boolean decoysEnabled,
        int sweepIntervalTicks,
        long dwellSeconds,
        boolean maskingEnabled,
        String maskingHeldMaterial,
        AlertChannel alertChannel,
        long realertCooldownSeconds,
        boolean notifyLeave,
        Map<String, Double> worldUnitsPerKilometer,
        String uncalibratedUnit,
        boolean publishWardMarkers) {

    /** Where alerts are routed. */
    public enum AlertChannel { TEAM, OWNER, BOTH }

    public record LoadResult(WardsConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    public static WardsConfig load(YamlConfiguration yaml) {
        var result = loadResult(yaml);
        if (!result.valid()) {
            throw new IllegalStateException("config invalid: " + result.issues());
        }
        return result.config();
    }

    /** @return candidate snapshot (null when invalid) plus every issue found. */
    public static LoadResult loadResult(YamlConfiguration yaml) {
        List<String> issues = new ArrayList<>();

        String anchor = yaml.getString("wards.anchor-material", "LODESTONE");
        if (anchor == null || Material.matchMaterial(anchor) == null) {
            issues.add("wards.anchor-material: unknown Bukkit material '" + anchor + "'");
        }

        int reach = yaml.getInt("wards.create-reach-blocks", 6);
        if (reach < 3 || reach > 16) {
            issues.add("wards.create-reach-blocks: must be between 3 and 16 (got " + reach + ")");
        }

        double minRadius = yaml.getDouble("wards.min-radius", 16.0);
        double maxRadius = yaml.getDouble("wards.max-radius", 256.0);
        if (minRadius < 4.0 || minRadius > 512.0) {
            issues.add("wards.min-radius: must be between 4.0 and 512.0 (got " + minRadius + ")");
        }
        if (maxRadius < minRadius || maxRadius > 1024.0) {
            issues.add("wards.max-radius: must be between min-radius (" + minRadius
                    + ") and 1024.0 (got " + maxRadius + ")");
        }

        int perPlayer = yaml.getInt("wards.max-wards-per-player", 3);
        if (perPlayer < 1 || perPlayer > 50) {
            issues.add("wards.max-wards-per-player: must be between 1 and 50 (got " + perPlayer + ")");
        }
        int perTeam = yaml.getInt("wards.max-wards-per-team", 12);
        if (perTeam < 1 || perTeam > 200) {
            issues.add("wards.max-wards-per-team: must be between 1 and 200 (got " + perTeam + ")");
        }
        int total = yaml.getInt("wards.max-wards-total", 2000);
        if (total < 10 || total > 100_000) {
            issues.add("wards.max-wards-total: must be between 10 and 100000 (got " + total + ")");
        }
        if (perTeam > total || perPlayer > total) {
            issues.add("wards.max-wards-total: smaller than the per-player/per-team caps");
        }

        List<String> ranks = yaml.getStringList("wards.manage-ranks");
        if (ranks.isEmpty()) {
            ranks = List.of("leader", "officer");
        }
        Set<String> rankSet = ranks.stream()
                .map(r -> r == null ? "" : r.trim().toLowerCase(Locale.ROOT))
                .filter(r -> r.matches("[a-z0-9_]{1,16}"))
                .collect(Collectors.toUnmodifiableSet());
        if (rankSet.isEmpty()) {
            issues.add("wards.manage-ranks: at least one rank key of [a-z0-9_]{1,16} required");
        }

        boolean decoys = yaml.getBoolean("wards.decoys.enabled", true);

        int sweep = yaml.getInt("detection.sweep-interval-ticks", 20);
        if (sweep < 2 || sweep > 1200) {
            issues.add("detection.sweep-interval-ticks: must be between 2 and 1200 (got " + sweep + ")");
        }

        int dwell = yaml.getInt("detection.dwell-seconds", 0);
        if (dwell < 0 || dwell > 3600) {
            issues.add("detection.dwell-seconds: must be between 0 and 3600 (got " + dwell + ")");
        }

        boolean maskingEnabled = yaml.getBoolean("detection.masking.enabled", true);
        String maskingMaterial = yaml.getString("detection.masking.held-material", "AMETHYST_SHARD");
        if (maskingEnabled && (maskingMaterial == null
                || Material.matchMaterial(maskingMaterial) == null)) {
            issues.add("detection.masking.held-material: unknown Bukkit material '"
                    + maskingMaterial + "'");
        }

        String channelRaw = yaml.getString("alerts.channel", "team");
        AlertChannel channel;
        try {
            channel = AlertChannel.valueOf(
                    (channelRaw == null ? "team" : channelRaw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            issues.add("alerts.channel: expected team, owner or both (got '" + channelRaw + "')");
            channel = AlertChannel.TEAM;
        }

        long cooldown = yaml.getLong("alerts.realert-cooldown-seconds", 300);
        if (cooldown < 0 || cooldown > 86_400) {
            issues.add("alerts.realert-cooldown-seconds: must be between 0 and 86400 (got " + cooldown + ")");
        }

        boolean notifyLeave = yaml.getBoolean("alerts.notify-leave", true);

        Map<String, Double> scales = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection(
                "distance.world-units-per-kilometer");
        if (section != null) {
            for (String world : section.getKeys(false)) {
                double scale = section.getDouble(world);
                if (!Double.isFinite(scale) || scale <= 0.0) {
                    issues.add("distance.world-units-per-kilometer." + world
                            + ": must be a finite value > 0 (got " + scale + ")");
                } else if (scale < 1.0 || scale > 100_000.0) {
                    issues.add("distance.world-units-per-kilometer." + world
                            + ": out of any plausible range 1.0..100000.0 (got " + scale
                            + ") - calibrate against the map, do not guess");
                } else {
                    scales.put(world, scale);
                }
            }
        }

        String unitRaw = yaml.getString("distance.uncalibrated-unit", "blocks");
        String unit = unitRaw == null ? "blocks" : unitRaw.trim().toLowerCase(Locale.ROOT);
        if (!unit.equals("blocks") && !unit.equals("meters")) {
            issues.add("distance.uncalibrated-unit: expected blocks or meters (got '" + unitRaw + "')");
            unit = "blocks";
        }

        boolean publish = yaml.getBoolean("map-markers.publish-wards", false);

        WardsConfig config = issues.isEmpty() ? new WardsConfig(anchor, reach, minRadius,
                maxRadius, perPlayer, perTeam, total, rankSet, decoys, sweep, dwell,
                maskingEnabled, maskingMaterial, channel, cooldown, notifyLeave,
                Map.copyOf(scales), unit, publish) : null;
        return new LoadResult(config, List.copyOf(issues));
    }

    /** @return true when kilometers are calibrated (and therefore enabled) for a world. */
    public boolean kilometersCalibrated(String world) {
        return worldUnitsPerKilometer.containsKey(world);
    }
}
