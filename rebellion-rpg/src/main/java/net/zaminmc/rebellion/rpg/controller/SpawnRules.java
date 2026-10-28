package net.zaminmc.rebellion.rpg.controller;

import java.util.List;
import net.zaminmc.rebellion.rpg.EncounterDefinition;
import org.jetbrains.annotations.NotNull;

/**
 * Pure spawn-rule evaluation for the soul spawner (ADR-0017). Every
 * function takes plain values (ticks, biome keys, player counts) so the
 * decision logic is testable without a live world — the spawner plugin
 * class only extracts the values and applies the verdict. This is what
 * keeps the spawner "deterministic and debuggable" (master prompt §5.3):
 * every decision it will make is answerable from these pure functions
 * plus the encounter ledger rows.
 */
public final class SpawnRules {

    /**
     * Minecraft day-phase tick bands (world time modulo 24000). Vanilla
     * alignment: 0 is sunrise, 6000 noon, 12000 sunset, 18000 midnight.
     * The bands are fixed feel boundaries, deliberately not configurable —
     * an operator who wants a soul to spawn "at night" uses the NIGHT
     * window; the tick bands are the module's definition of night.
     */
    private static final long TICKS_PER_DAY = 24_000L;
    private static final long DAWN_START = 22_500L;
    private static final long DAY_START = 1_000L;
    private static final long DUSK_START = 11_000L;
    private static final long NIGHT_START = 14_000L;

    private SpawnRules() {
    }

    /** @return the day phase a world-time tick belongs to. */
    public static @NotNull EncounterDefinition.TimeWindow dayPhase(long worldTime) {
        long ticks = Math.floorMod(worldTime, TICKS_PER_DAY);
        if (ticks >= DAWN_START) {
            return EncounterDefinition.TimeWindow.DAWN;
        }
        if (ticks < DAY_START) {
            return EncounterDefinition.TimeWindow.DAWN;
        }
        if (ticks < DUSK_START) {
            return EncounterDefinition.TimeWindow.DAY;
        }
        if (ticks < NIGHT_START) {
            return EncounterDefinition.TimeWindow.DUSK;
        }
        return EncounterDefinition.TimeWindow.NIGHT;
    }

    /** @return true when the configured window accepts the current phase. */
    public static boolean timeWindowMatches(@NotNull EncounterDefinition.TimeWindow window,
            long worldTime) {
        if (window == EncounterDefinition.TimeWindow.ANY) {
            return true;
        }
        return dayPhase(worldTime) == window;
    }

    /**
     * @return true when the biome list accepts the current biome. An empty
     *         configured list means "any biome" (ADR-0016 §5).
     */
    public static boolean biomeAllowed(@NotNull List<String> configuredBiomes,
            @NotNull String currentBiome) {
        if (configuredBiomes.isEmpty()) {
            return true;
        }
        for (String biome : configuredBiomes) {
            if (biome.equalsIgnoreCase(currentBiome)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true when the online count is within the configured bounds
     *         (max 0 = uncapped; min is the encounter definition's floor).
     */
    public static boolean playerCountMatches(int online, int minPlayers, int maxPlayers) {
        if (online < minPlayers) {
            return false;
        }
        return maxPlayers == 0 || online <= maxPlayers;
    }
}
