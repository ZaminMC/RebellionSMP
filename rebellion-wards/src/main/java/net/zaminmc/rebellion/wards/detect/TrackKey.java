package net.zaminmc.rebellion.wards.detect;

import java.util.UUID;

/**
 * One (player, ward) detection pair. Cooldowns are per-pair by design: the
 * same outsider re-entering the same ward is spam; the same outsider
 * entering a different ward is new intel and must never be throttled by an
 * unrelated pair.
 */
record TrackKey(UUID player, UUID ward) {
    static TrackKey of(UUID player, UUID ward) {
        return new TrackKey(player, ward);
    }
}
