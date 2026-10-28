package net.zaminmc.rebellion.alchemy.controller;

import net.zaminmc.rebellion.alchemy.AlchemyConfig;

/**
 * Deterministic XP source keys for completed brews (ADR-0020 §1) — the
 * composition the ADR-0019 idempotency contract rides. Shape:
 * {@code brew:<world>:<x>:<y>:<z>:<bucket>} where the bucket is the epoch
 * second. Brewing cycles take 400 ticks (20 s), so two completions on one
 * stand can never share a one-second bucket, while a re-delivered event
 * inside the same second resolves to the typed no-op.
 *
 * <p>World and coordinate values are sanitized into the ADR-0019 source
 * grammar; a world name with separators outside the grammar is hashed to
 * its unsigned hex so the key stays valid without ever being guessable
 * from player input.</p>
 */
public final class BrewSourceKeys {

    private BrewSourceKeys() {
    }

    /** @return the deterministic source key for one completed brew cycle. */
    public static String forBrew(String world, int x, int y, int z,
            long epochSecondBucket) {
        return "brew:" + safeWorld(world) + ":" + x + ":" + y + ":" + z + ":"
                + epochSecondBucket;
    }

    /** @return the one-second bucket an event timestamp falls into. */
    public static long bucket(long epochMillis) {
        return epochMillis / 1000L;
    }

    /**
     * Worlds are operator-named and may contain characters the source-key
     * grammar excludes; such names are hashed (unsigned hex) so the key
     * always matches {@code [a-z0-9_:\\-]{1,96}}.
     */
    static String safeWorld(String world) {
        if (world != null && world.matches("[a-z0-9_\\-]{1,48}")) {
            return world;
        }
        String hash = Integer.toHexString(
                String.valueOf(world).hashCode() & 0x7fffffff);
        return "w" + hash;
    }

    /** @return true when a composed key matches the ADR-0019 grammar. */
    public static boolean valid(String key) {
        return key != null && AlchemyConfig.SOURCE_KEY.matcher(key).matches()
                && key.length() <= 96;
    }
}
