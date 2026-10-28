package net.zaminmc.rebellion.rpg;

import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * One validated encounter definition from the {@code encounters} config
 * family (ADR-0016 §5). Spawn-condition <em>evaluation</em> (biome, time,
 * online-player count) needs a live world and is controller-phase work;
 * this record carries the operator's rules, and the ledger enforces every
 * rule that is state (concurrency cap, cooldown, TTL, rewards).
 *
 * <p>Biome names are free-text keys matched case-insensitively by the
 * future controller against {@code Biome} names — the persistence layer
 * never touches the biome registry (serverless discipline).</p>
 *
 * @param key the encounter key ({@code [a-z0-9_]{1,32}})
 * @param enabled whether begins are allowed (the shipped default is
 *        disabled — spawn rules are world data the operator calibrates)
 * @param worlds allowed worlds (non-empty; case-insensitive match)
 * @param biomes allowed biome keys (empty = any)
 * @param timeWindow when the encounter may spawn
 * @param minPlayers minimum online players
 * @param maxPlayers maximum online players (0 = uncapped)
 * @param maxConcurrent cap on simultaneously ACTIVE rows for this key
 * @param cooldownSeconds min seconds after the latest resolved row before
 *        the next begin
 * @param ttlSeconds ACTIVE rows older than this are expired by the sweep
 * @param rewards discovery keys granted to the completing helper
 */
public record EncounterDefinition(
        @NotNull String key,
        boolean enabled,
        @NotNull List<String> worlds,
        @NotNull List<String> biomes,
        @NotNull TimeWindow timeWindow,
        int minPlayers,
        int maxPlayers,
        int maxConcurrent,
        long cooldownSeconds,
        long ttlSeconds,
        @NotNull List<String> rewards) {

    /** Structural bounds (portable validation; mirrored by RpgConfig). */
    public static final int MAX_KEY = 32;
    public static final int MAX_WORLD_NAME = 64;
    public static final int MAX_BIOME_NAME = 64;
    public static final int MAX_PLAYERS = 1000;
    public static final int MAX_CONCURRENT = 10;
    public static final long MAX_SECONDS = 2_592_000L; // 30 days
    public static final long MIN_TTL_SECONDS = 60;
    public static final long MAX_TTL_SECONDS = 86_400;
    public static final int MAX_REWARDS = 16;

    public EncounterDefinition {
        worlds = List.copyOf(worlds);
        biomes = List.copyOf(biomes);
        rewards = List.copyOf(rewards);
    }

    /** @return true when the given world is in the allowed list
     *          (case-insensitive). */
    public boolean allowsWorld(@NotNull String world) {
        for (String allowed : worlds) {
            if (allowed.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    /** When the encounter may spawn (controller evaluates the live clock). */
    public enum TimeWindow {
        DAWN,
        DAY,
        DUSK,
        NIGHT,
        ANY;

        /** Parses config input case-insensitively; null on unknown text. */
        public static TimeWindow parse(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim().toUpperCase(Locale.ROOT);
            for (TimeWindow window : values()) {
                if (window.name().equals(trimmed)) {
                    return window;
                }
            }
            return null;
        }
    }
}
