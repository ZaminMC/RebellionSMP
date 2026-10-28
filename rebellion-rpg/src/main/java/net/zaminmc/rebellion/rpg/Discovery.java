package net.zaminmc.rebellion.rpg;

import java.util.UUID;

/**
 * One discovery ledger row (ADR-0016 §1): a player knows a ritual recipe
 * or knowledge flag. Immutable fact — rows are inserted (idempotently, by
 * the {@code (player_uuid, discovery_key)} PK) and never updated.
 *
 * @param playerUuid the discovering player
 * @param key the config-defined discovery key (recipe or knowledge flag)
 * @param source how it was earned: {@code ENCOUNTER}, {@code RITUAL},
 *        {@code ADMIN}
 * @param discoveredUtc discovery time (service clock)
 */
public record Discovery(UUID playerUuid, String key, String source,
        long discoveredUtc) {

    public static final String SOURCE_ENCOUNTER = "ENCOUNTER";
    public static final String SOURCE_RITUAL = "RITUAL";
    public static final String SOURCE_ADMIN = "ADMIN";
}
