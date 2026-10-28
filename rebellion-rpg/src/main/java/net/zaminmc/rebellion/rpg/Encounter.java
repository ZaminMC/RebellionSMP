package net.zaminmc.rebellion.rpg;

import java.util.UUID;

/**
 * One encounter-ledger row (ADR-0016 §4): the bookkeeping half of the
 * rare-soul encounter state machine. The controller spawns entities; this
 * row is the authority for every rule that is state — concurrency caps,
 * cooldowns, TTL cleanup and one-winner reward claims are all answered
 * from the ledger.
 *
 * @param encounterId durable encounter id
 * @param key the config-defined encounter key
 * @param world spawn world
 * @param x spawn x
 * @param y spawn y
 * @param z spawn z
 * @param state the row state
 * @param soulEntity the spawned soul's entity UUID (attached by the
 *        controller; null until then)
 * @param beganUtc begin time
 * @param resolvedUtc completion/expiry time (null while ACTIVE)
 * @param completedBy the player whose help completed the encounter
 *        (null unless COMPLETED)
 */
public record Encounter(UUID encounterId, String key, String world, int x,
        int y, int z, EncounterState state, UUID soulEntity, long beganUtc,
        Long resolvedUtc, UUID completedBy) {
}
