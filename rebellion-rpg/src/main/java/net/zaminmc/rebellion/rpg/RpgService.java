package net.zaminmc.rebellion.rpg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.rpg.db.RpgStore;
import net.zaminmc.rebellion.rpg.db.RpgStore.EventRow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RPG authority (ADR-0016): the transactional orchestration over the six
 * persistence tables. Every mutation is one database transaction whose
 * rows commit all-or-none, exposed as a future completing on the
 * persistence executor (never the main thread — callers marshal back).
 * Typed refusals are thrown inside the transaction (rolling it back) and
 * surface as {@link RpgResult} failures; the one deliberate mutated
 * refusal is {@code SESSION_CAP_REACHED}, which auto-releases the claim
 * and returns normally so the release commits.
 *
 * <h2>The claim protocol (master prompt §5.3: "ritual state changes and
 * consumed ingredients must be committed as one logical operation")</h2>
 * <ol>
 *   <li>{@link #reserveRitual} — one transaction validates the definition,
 *       the rate limit (attempts of <em>any</em> state — anti-abuse stays
 *       conservative), the cooldown (COMMITTED attempts only — a released
 *       reservation never bought a cooldown) and the single-reservation
 *       guard, then inserts the RESERVED claim carrying the controller's
 *       ingredient digest. Only with a live reservation in hand may the
 *       controller remove physical ingredients.</li>
 *   <li>The controller removes ingredients; failure →
 *       {@link #releaseReservation} (typed refund path).</li>
 *   <li>{@code commitTracking / commitCounterMask / commitKnowledge} — one
 *       transaction: CAS the claim RESERVED→COMMITTED together with the
 *       ritual's effect rows (session / effect / discovery) and the audit
 *       event. The failure roll is drawn from the injected source ahead of
 *       the CAS and only parameterizes the commit: a losing roll still
 *       spends the claim (cooldown bought — failure is a cost) but writes
 *       no session; the durable event outcome makes re-delivery resolve to
 *       the original result.</li>
 * </ol>
 *
 * <h2>Tracking composition (never over-reveal)</h2>
 * {@link #resolveTracking} is pure over persistence: the granted precision
 * tier is stamped on the session at cast time and only ever degrades — an
 * active {@code masking} effect on the target degrades the effective tier
 * by exactly one step (a masked DIMENSION track resolves to nothing).
 * Session expiry is derived ({@code expiresUtc}); no sweep can be
 * forgotten.
 */
public final class RpgService {

    private final RebellionDatabase db;
    private final RpgConfig config;
    private final LongSupplier clock;
    private final DoubleSupplier rng;

    public RpgService(@NotNull RebellionDatabase db, @NotNull RpgConfig config,
            @NotNull LongSupplier clock, @NotNull DoubleSupplier rng) {
        this.db = db;
        this.config = config;
        this.clock = clock;
        this.rng = rng;
    }

    /** Internal typed refusal; thrown inside a transaction rolls it back. */
    private static final class Refusal extends RuntimeException {
        final RpgOutcome outcome;
        final String detail;

        Refusal(RpgOutcome outcome, String detail) {
            super(detail, null, false, false);
            this.outcome = outcome;
            this.detail = detail;
        }
    }

    /** Unwraps a failed transaction into a typed failure result. */
    private static <T> RpgResult<T> toFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException c && c.getCause() != null
                ? c.getCause() : error;
        if (cause instanceof Refusal refusal) {
            return RpgResult.failure(refusal.outcome, refusal.detail);
        }
        return RpgResult.failure(RpgOutcome.DB_ERROR, String.valueOf(
                cause.getMessage() == null ? cause.getClass().getSimpleName()
                        : cause.getMessage()));
    }

    // ------------------------------------------------------------------
    // Discoveries
    // ------------------------------------------------------------------

    /**
     * Records a discovery (idempotent by the
     * {@code (player_uuid, discovery_key)} primary key).
     *
     * @return {@code DISCOVERED} when this call created the row,
     *         {@code ALREADY_KNOWN} when the player already knows it.
     */
    public CompletableFuture<RpgResult<Discovery>> discover(@NotNull UUID player,
            @NotNull String key, @NotNull String source) {
        if (!RpgConfig.KEY.matcher(key).matches()) {
            return CompletableFuture.completedFuture(RpgResult.failure(
                    RpgOutcome.ARGUMENT_INVALID,
                    "discovery key must match [a-z0-9_]{1,32} (got '" + key + "')"));
        }
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            int rows = RpgStore.insertDiscovery(tx, player, key, source, now);
            if (rows == 0) {
                Discovery existing = RpgStore.discovery(tx, player, key).orElseThrow();
                return RpgResult.<Discovery>unchanged(RpgOutcome.ALREADY_KNOWN,
                        "known since " + existing.discoveredUtc(), existing);
            }
            RpgStore.insertEvent(tx, "discovery:" + player + ":" + key, player,
                    "DISCOVERY", "source=" + source, "NEW", now);
            return RpgResult.changed(RpgOutcome.DISCOVERED,
                    new Discovery(player, key, source, now));
        }).exceptionally(RpgService::<Discovery>toFailure);
    }

    /** @return every discovery the player holds, earliest first. */
    public CompletableFuture<List<Discovery>> discoveries(@NotNull UUID player) {
        return db.transaction(tx -> RpgStore.discoveriesFor(tx, player));
    }

    /** @return whether the player holds the discovery. */
    public CompletableFuture<Boolean> hasDiscovery(@NotNull UUID player,
            @NotNull String key) {
        return db.transaction(tx -> RpgStore.discovery(tx, player, key).isPresent());
    }

    // ------------------------------------------------------------------
    // Ritual protocol — reserve / release / commit
    // ------------------------------------------------------------------

    /**
     * Step 1 of the claim protocol: validates the definition, rate limit,
     * cooldown and single-reservation guard, then inserts the RESERVED
     * claim. The controller may remove physical ingredients only while a
     * live reservation exists.
     *
     * @param ingredientsDigest the controller's snapshot of the exact
     *        ingredient set it verified (refund evidence)
     */
    public CompletableFuture<RpgResult<RitualClaim>> reserveRitual(@NotNull UUID player,
            @NotNull String ritualKey, @NotNull String ingredientsDigest) {
        if (ritualKey.isBlank() || ingredientsDigest.isBlank()) {
            return CompletableFuture.completedFuture(RpgResult.failure(
                    RpgOutcome.ARGUMENT_INVALID,
                    "ritual key and ingredient digest are required"));
        }
        RitualDefinition definition = config.rituals().get(ritualKey);
        if (definition == null) {
            return CompletableFuture.completedFuture(RpgResult.failure(
                    RpgOutcome.RITUAL_UNKNOWN,
                    "no ritual definition for '" + ritualKey + "'"));
        }
        if (!definition.enabled()) {
            return CompletableFuture.completedFuture(RpgResult.failure(
                    RpgOutcome.RITUAL_DISABLED,
                    "ritual '" + ritualKey + "' is disabled"));
        }
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            if (RpgStore.attemptsInWindow(tx, player, ritualKey,
                    now - definition.rateLimitWindowMs())
                    >= definition.rateLimitAttempts()) {
                throw new Refusal(RpgOutcome.RATE_LIMITED, "rate limit: "
                        + definition.rateLimitAttempts() + " attempts per "
                        + definition.rateLimitSeconds() + "s (any state counts)");
            }
            long lastCommitted = RpgStore.lastCommitted(tx, player, ritualKey);
            if (lastCommitted > 0 && lastCommitted + definition.cooldownMs() > now) {
                throw new Refusal(RpgOutcome.COOLDOWN_ACTIVE, "cooldown: "
                        + ((lastCommitted + definition.cooldownMs() - now) / 1000)
                        + "s remain");
            }
            if (RpgStore.reservedCount(tx, player, ritualKey) > 0) {
                throw new Refusal(RpgOutcome.RESERVATION_HELD, "a reservation is "
                        + "already open for this ritual - release it first "
                        + "(ingredient race guard)");
            }
            UUID claimId = UUID.randomUUID();
            RpgStore.insertClaim(tx, claimId, player, ritualKey, ingredientsDigest, now);
            return RpgResult.changed(RpgOutcome.RESERVED, new RitualClaim(claimId,
                    player, ritualKey, ClaimState.RESERVED, ingredientsDigest, now,
                    null));
        }).exceptionally(RpgService::<RitualClaim>toFailure);
    }

    /**
     * Refund path: releases a RESERVED claim (inventory refusal, unknown
     * target resolution, operator cancel). Idempotent — a second release
     * resolves to {@code RELEASE_NOOP}.
     */
    public CompletableFuture<RpgResult<RitualClaim>> releaseReservation(
            @NotNull UUID claimId, @NotNull UUID player, @Nullable String reason) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            RitualClaim claim = RpgStore.claim(tx, claimId)
                    .orElseThrow(() -> new Refusal(RpgOutcome.CLAIM_NOT_FOUND,
                            "no claim " + claimId));
            if (!claim.playerUuid().equals(player)) {
                throw new Refusal(RpgOutcome.ARGUMENT_INVALID,
                        "claim belongs to another player");
            }
            if (RpgStore.releaseClaim(tx, claimId, now) == 0) {
                return RpgResult.<RitualClaim>unchanged(RpgOutcome.RELEASE_NOOP,
                        "claim is " + claim.state(), claim);
            }
            RpgStore.insertEvent(tx, eventKeyRelease(claimId), player,
                    "CLAIM_RELEASE", "ritual=" + claim.ritualKey()
                            + " reason=" + (reason == null ? "manual" : reason),
                    "RELEASED", now);
            return RpgResult.changed(RpgOutcome.RELEASED, new RitualClaim(claimId,
                    player, claim.ritualKey(), ClaimState.RELEASED,
                    claim.ingredientsDigest(), claim.createdUtc(), now));
        }).exceptionally(RpgService::<RitualClaim>toFailure);
    }

    /**
     * Crash-recovery sweep: releases RESERVED claims older than the floor.
     * Per-claim event rows preserve the digest as the operator's refund
     * evidence for the window where the server died after inventory
     * removal but before commit.
     *
     * @return how many stale reservations were released.
     */
    public CompletableFuture<Integer> releaseStaleReservations(long olderThanMs) {
        if (olderThanMs < 0) {
            throw new IllegalArgumentException("olderThanMs must be >= 0");
        }
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            long floor = now - olderThanMs;
            List<RitualClaim> stale = RpgStore.staleReservations(tx, floor);
            for (RitualClaim claim : stale) {
                if (RpgStore.releaseClaim(tx, claim.claimId(), now) > 0) {
                    RpgStore.insertEvent(tx, eventKeyRelease(claim.claimId()),
                            claim.playerUuid(), "CLAIM_RELEASE",
                            "ritual=" + claim.ritualKey() + " reason=stale-sweep",
                            "RELEASED", now);
                }
            }
            return stale.size();
        });
    }

    /**
     * Step 3 (TRACKING): commits the claim and — unless the failure roll
     * lost it — creates the tracking session, all-or-none. The roll is
     * drawn from the injected source ahead of the CAS; its outcome is
     * durable in the event row, so re-delivery resolves to the original
     * result.
     *
     * <p>Session-cap refusal auto-releases the claim in the same
     * transaction (the reservation can never succeed — refund path), so it
     * returns normally as a <em>mutated</em> refusal.</p>
     *
     * @param target the resolved target UUID (offline players are legal;
     *        unknown names are released by the controller before this call)
     */
    public CompletableFuture<RpgResult<TrackingSession>> commitTracking(
            @NotNull UUID claimId, @NotNull UUID player, @NotNull UUID target) {
        return db.<RpgResult<TrackingSession>>transaction(tx -> {
            long now = clock.getAsLong();
            RitualClaim claim = readOwnedClaim(tx, claimId, player);
            if (claim.state() != ClaimState.RESERVED) {
                return commitNoop(tx, claimId, claim);
            }
            RitualDefinition definition = requireDefinition(claim,
                    RitualKind.TRACKING);

            // Session cap: a permanent refusal — convert to the refund state.
            // Returns normally (NOT a thrown Refusal) so the release commits.
            if (RpgStore.activeSessionCount(tx, player, now)
                    >= definition.maxSessionsPerCaster()) {
                RpgStore.releaseClaim(tx, claimId, now);
                RpgStore.insertEvent(tx, eventKeyRelease(claimId), player,
                        "CLAIM_RELEASE", "ritual=" + claim.ritualKey()
                                + " reason=session-cap", "RELEASED", now);
                return RpgResult.<TrackingSession>changed(
                        RpgOutcome.SESSION_CAP_REACHED,
                        "caster already holds " + definition.maxSessionsPerCaster()
                                + " active tracking session(s) - claim released, "
                                + "ingredients refundable (digest "
                                + claim.ingredientsDigest() + ")", null);
            }

            boolean failed = rng.getAsDouble() < definition.failureChance();
            if (RpgStore.commitClaim(tx, claimId, now) == 0) {
                return commitNoop(tx, claimId, claim);
            }
            TrackingSession session = null;
            if (!failed) {
                UUID sessionId = UUID.randomUUID();
                RpgStore.insertSession(tx, sessionId, claimId, player, target,
                        definition.precision(), now, now + definition.durationMs());
                session = RpgStore.session(tx, sessionId).orElseThrow();
            }
            RpgStore.insertEvent(tx, eventKeyCommit(claimId), player, "RITUAL_COMMIT",
                    "ritual=" + claim.ritualKey() + " target=" + target,
                    failed ? "FAILED" : "SUCCESS", now);
            return failed
                    ? RpgResult.<TrackingSession>changed(RpgOutcome.RITUAL_FAILED,
                            "the ritual failed - reagents spent, cooldown bought",
                            null)
                    : RpgResult.changed(RpgOutcome.TRACKING_COMMITTED, session);
        }).exceptionally(RpgService::<TrackingSession>toFailure);
    }

    /**
     * Step 3 (COUNTER_MASK): commits the claim and grants the protected
     * player a masking effect that degrades incoming tracking precision by
     * one tier. Masks do not stack — a second grant extends protection via
     * its own later expiry.
     *
     * @param protectedPlayer the player the mask protects (usually the
     *        caster; admin grants may protect others)
     */
    public CompletableFuture<RpgResult<PlayerEffect>> commitCounterMask(
            @NotNull UUID claimId, @NotNull UUID player,
            @NotNull UUID protectedPlayer) {
        return db.<RpgResult<PlayerEffect>>transaction(tx -> {
            long now = clock.getAsLong();
            RitualClaim claim = readOwnedClaim(tx, claimId, player);
            if (claim.state() != ClaimState.RESERVED) {
                return commitNoop(tx, claimId, claim);
            }
            RitualDefinition definition = requireDefinition(claim,
                    RitualKind.COUNTER_MASK);
            if (RpgStore.commitClaim(tx, claimId, now) == 0) {
                return commitNoop(tx, claimId, claim);
            }
            UUID effectId = UUID.randomUUID();
            RpgStore.insertEffect(tx, effectId, protectedPlayer,
                    PlayerEffect.KEY_MASKING, now, now + definition.durationMs(), player);
            RpgStore.insertEvent(tx, eventKeyCommit(claimId), player, "RITUAL_COMMIT",
                    "ritual=" + claim.ritualKey() + " protected=" + protectedPlayer,
                    "SUCCESS", now);
            return RpgResult.changed(RpgOutcome.MASK_GRANTED, new PlayerEffect(effectId,
                    protectedPlayer, PlayerEffect.KEY_MASKING, now,
                    now + definition.durationMs(), player));
        }).exceptionally(RpgService::<PlayerEffect>toFailure);
    }

    /**
     * Step 3 (KNOWLEDGE): commits the claim and unlocks the ritual's
     * discovery key (idempotent — an already-known key still spends the
     * claim; the detail says which).
     */
    public CompletableFuture<RpgResult<Discovery>> commitKnowledge(
            @NotNull UUID claimId, @NotNull UUID player) {
        return db.<RpgResult<Discovery>>transaction(tx -> {
            long now = clock.getAsLong();
            RitualClaim claim = readOwnedClaim(tx, claimId, player);
            if (claim.state() != ClaimState.RESERVED) {
                return commitNoop(tx, claimId, claim);
            }
            RitualDefinition definition = requireDefinition(claim,
                    RitualKind.KNOWLEDGE);
            String key = definition.knowledgeKey().orElseThrow(() -> new Refusal(
                    RpgOutcome.ARGUMENT_INVALID,
                    "knowledge ritual '" + definition.key() + "' defines no key"));
            if (RpgStore.commitClaim(tx, claimId, now) == 0) {
                return commitNoop(tx, claimId, claim);
            }
            int rows = RpgStore.insertDiscovery(tx, player, key,
                    Discovery.SOURCE_RITUAL, now);
            Discovery discovery = RpgStore.discovery(tx, player, key).orElseThrow();
            RpgStore.insertEvent(tx, eventKeyCommit(claimId), player, "RITUAL_COMMIT",
                    "ritual=" + claim.ritualKey() + " knowledge=" + key,
                    rows == 1 ? "SUCCESS" : "ALREADY_KNOWN", now);
            return RpgResult.changed(RpgOutcome.KNOWLEDGE_COMMITTED,
                    rows == 1 ? "new knowledge recorded"
                            : "already known - reagents spent",
                    discovery);
        }).exceptionally(RpgService::<Discovery>toFailure);
    }

    /** Shared claim pre-flight for the three commit paths: existence and
     *  ownership only. A claim that is no longer RESERVED is <em>not</em> a
     *  refusal — callers resolve it through {@link #commitNoop} so the
     *  original committed outcome is carried (re-delivery contract). */
    private RitualClaim readOwnedClaim(TransactionContext tx, UUID claimId,
            UUID player) throws java.sql.SQLException {
        RitualClaim claim = RpgStore.claim(tx, claimId)
                .orElseThrow(() -> new Refusal(RpgOutcome.CLAIM_NOT_FOUND,
                        "no claim " + claimId));
        if (!claim.playerUuid().equals(player)) {
            throw new Refusal(RpgOutcome.ARGUMENT_INVALID,
                    "claim belongs to another player");
        }
        return claim;
    }

    /** Definition pre-flight: exists and matches the commit family. */
    private RitualDefinition requireDefinition(RitualClaim claim,
            RitualKind expectedKind) {
        RitualDefinition definition = config.rituals().get(claim.ritualKey());
        if (definition == null) {
            throw new Refusal(RpgOutcome.RITUAL_UNKNOWN,
                    "definition removed while reserved: '" + claim.ritualKey() + "'");
        }
        if (definition.kind() != expectedKind) {
            throw new Refusal(RpgOutcome.ARGUMENT_INVALID, "ritual '"
                    + claim.ritualKey() + "' is " + definition.kind()
                    + " - use its own commit path");
        }
        return definition;
    }

    /** Re-delivery resolution: carry the original committed outcome. */
    @SuppressWarnings("unchecked")
    private <T> RpgResult<T> commitNoop(TransactionContext tx, UUID claimId,
            RitualClaim claim) throws java.sql.SQLException {
        Optional<EventRow> event = RpgStore.event(tx, eventKeyCommit(claimId));
        String original = event.map(EventRow::outcome).orElse("UNKNOWN");
        return (RpgResult<T>) RpgResult.unchanged(RpgOutcome.COMMIT_NOOP,
                "claim already " + claim.state() + " (original outcome " + original
                        + ")", claim);
    }

    // ------------------------------------------------------------------
    // Tracking reads + composition
    // ------------------------------------------------------------------

    /** One resolved session: granted tier, mask state, effective tier. */
    public record TrackingView(@NotNull TrackingSession session, boolean masked,
            @NotNull Optional<Precision> effectivePrecision) {

        /** @return true when the mask erased the signal entirely. */
        public boolean fullyMasked() {
            return masked && effectivePrecision.isEmpty();
        }
    }

    /**
     * The tracking resolution for a caster: every unrevoked, unexpired
     * session with its effective precision after mask degradation. This is
     * the only sanctioned read for signal rendering — the controller may
     * reveal <em>at most</em> the effective tier.
     */
    public CompletableFuture<List<TrackingView>> resolveTracking(@NotNull UUID caster) {
        long now = clock.getAsLong();
        return db.transaction(tx -> {
            List<TrackingView> views = new ArrayList<>();
            for (TrackingSession session : RpgStore.activeSessionsFor(tx, caster, now)) {
                boolean masked = RpgStore.activeEffectCount(tx, session.targetUuid(),
                        PlayerEffect.KEY_MASKING, now) > 0;
                Optional<Precision> effective = masked
                        ? session.precision().degraded()
                        : Optional.of(session.precision());
                views.add(new TrackingView(session, masked, effective));
            }
            return List.copyOf(views);
        });
    }

    /** @return the caster's unrevoked, unexpired sessions. */
    public CompletableFuture<List<TrackingSession>> activeSessions(
            @NotNull UUID caster) {
        long now = clock.getAsLong();
        return db.transaction(tx -> RpgStore.activeSessionsFor(tx, caster, now));
    }

    /** @return every claim row for a player, earliest first (forensics). */
    public CompletableFuture<List<RitualClaim>> claims(@NotNull UUID player) {
        return db.transaction(tx -> RpgStore.claimsFor(tx, player));
    }

    /** @return every effect row for a player (forensics order). */
    public CompletableFuture<List<PlayerEffect>> effects(@NotNull UUID player) {
        return db.transaction(tx -> RpgStore.effectsFor(tx, player));
    }

    /** Admin/recovery revocation (idempotent; expired rows no-op). */
    public CompletableFuture<RpgResult<TrackingSession>> revokeSession(
            @NotNull UUID sessionId, @Nullable String reason) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            TrackingSession session = RpgStore.session(tx, sessionId)
                    .orElseThrow(() -> new Refusal(RpgOutcome.SESSION_NOT_FOUND,
                            "no session " + sessionId));
            String safeReason = reason == null ? "admin" : reason;
            if (RpgStore.revokeSession(tx, sessionId, safeReason, now) == 0) {
                return RpgResult.<TrackingSession>unchanged(
                        RpgOutcome.SESSION_REVOKE_NOOP,
                        "revoked=" + (session.revokedUtc() != null) + " expired="
                                + (now >= session.expiresUtc()), session);
            }
            RpgStore.insertEvent(tx, "session:" + sessionId + ":revoke",
                    session.casterUuid(), "SESSION_REVOKE", "reason=" + safeReason,
                    "REVOKED", now);
            return RpgResult.changed(RpgOutcome.SESSION_REVOKED,
                    new TrackingSession(session.sessionId(), session.claimId(),
                            session.casterUuid(), session.targetUuid(),
                            session.precision(), session.beganUtc(),
                            session.expiresUtc(), now, safeReason));
        }).exceptionally(RpgService::<TrackingSession>toFailure);
    }

    // ------------------------------------------------------------------
    // Encounters
    // ------------------------------------------------------------------

    /**
     * Begins an encounter row after every ledger rule passes: definition
     * exists + enabled, world allowed, concurrency cap, encounter
     * cooldown. The controller spawns entities only against a begun row.
     */
    public CompletableFuture<RpgResult<Encounter>> beginEncounter(
            @NotNull String encounterKey, @NotNull String world, int x, int y, int z) {
        EncounterDefinition definition = config.encounters().get(encounterKey);
        if (definition == null) {
            return CompletableFuture.completedFuture(RpgResult.failure(
                    RpgOutcome.ENCOUNTER_UNKNOWN,
                    "no encounter definition for '" + encounterKey + "'"));
        }
        if (!definition.enabled()) {
            return CompletableFuture.completedFuture(RpgResult.failure(
                    RpgOutcome.ENCOUNTER_DISABLED,
                    "encounter '" + encounterKey + "' is disabled"));
        }
        if (!definition.allowsWorld(world)) {
            return CompletableFuture.completedFuture(RpgResult.failure(
                    RpgOutcome.WORLD_REFUSED,
                    "world '" + world + "' is not in the allowed list for '"
                            + encounterKey + "'"));
        }
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            if (RpgStore.activeEncounterCount(tx, encounterKey)
                    >= definition.maxConcurrent()) {
                throw new Refusal(RpgOutcome.CONCURRENCY_CAP,
                        definition.maxConcurrent() + " active encounter(s) already "
                                + "exist for '" + encounterKey + "'");
            }
            long lastResolved = RpgStore.lastResolvedEncounter(tx, encounterKey);
            long cooldownMs = definition.cooldownSeconds() * 1000L;
            if (lastResolved > 0 && lastResolved + cooldownMs > now) {
                throw new Refusal(RpgOutcome.ENCOUNTER_COOLDOWN, "cooldown: "
                        + ((lastResolved + cooldownMs - now) / 1000) + "s remain");
            }
            UUID encounterId = UUID.randomUUID();
            RpgStore.insertEncounter(tx, encounterId, encounterKey, world, x, y, z, now);
            return RpgResult.changed(RpgOutcome.ENCOUNTER_BEGUN, RpgStore.encounter(
                    tx, encounterId).orElseThrow());
        }).exceptionally(RpgService::<Encounter>toFailure);
    }

    /** Attaches the spawned soul's entity id to the ACTIVE row (idempotent). */
    public CompletableFuture<RpgResult<Encounter>> attachSoul(
            @NotNull UUID encounterId, @NotNull UUID entityUuid) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            Encounter encounter = RpgStore.encounter(tx, encounterId)
                    .orElseThrow(() -> new Refusal(RpgOutcome.ENCOUNTER_NOT_FOUND,
                            "no encounter " + encounterId));
            if (RpgStore.attachSoul(tx, encounterId, entityUuid) == 0) {
                return RpgResult.<Encounter>unchanged(RpgOutcome.SOUL_ATTACH_NOOP,
                        "state=" + encounter.state() + " soul="
                                + encounter.soulEntity(), encounter);
            }
            RpgStore.insertEvent(tx, "encounter:" + encounterId + ":attach", null,
                    "SOUL_ATTACH", "entity=" + entityUuid, "ATTACHED", now);
            return RpgResult.changed(RpgOutcome.SOUL_ATTACHED, RpgStore.encounter(tx,
                    encounterId).orElseThrow());
        }).exceptionally(RpgService::<Encounter>toFailure);
    }

    /**
     * Completes an encounter: exactly one helper wins the CAS; the winner's
     * transaction grants the configured reward discoveries (each idempotent
     * by PK). Every other helper resolves to
     * {@code ENCOUNTER_ALREADY_RESOLVED} and earns nothing.
     */
    public CompletableFuture<RpgResult<Encounter>> completeEncounter(
            @NotNull UUID encounterId, @NotNull UUID player) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            Encounter encounter = RpgStore.encounter(tx, encounterId)
                    .orElseThrow(() -> new Refusal(RpgOutcome.ENCOUNTER_NOT_FOUND,
                            "no encounter " + encounterId));
            EncounterDefinition definition = config.encounters().get(encounter.key());
            if (RpgStore.completeEncounter(tx, encounterId, player, now) == 0) {
                return RpgResult.<Encounter>unchanged(
                        RpgOutcome.ENCOUNTER_ALREADY_RESOLVED,
                        "state=" + encounter.state() + " completedBy="
                                + encounter.completedBy(), encounter);
            }
            int fresh = 0;
            int total = 0;
            if (definition != null) {
                total = definition.rewards().size();
                for (String reward : definition.rewards()) {
                    fresh += RpgStore.insertDiscovery(tx, player, reward,
                            Discovery.SOURCE_ENCOUNTER, now);
                }
            }
            RpgStore.insertEvent(tx, "encounter:" + encounterId + ":complete", player,
                    "ENCOUNTER_COMPLETE",
                    "helper=" + player + " rewards: " + fresh + " new / "
                            + (total - fresh) + " known", "COMPLETED", now);
            return RpgResult.changed(RpgOutcome.ENCOUNTER_COMPLETED,
                    fresh + " new discovery(ies)", RpgStore.encounter(tx, encounterId)
                            .orElseThrow());
        }).exceptionally(RpgService::<Encounter>toFailure);
    }

    /**
     * TTL cleanup (§5.3 "cleanup"): expires ACTIVE rows whose definition's
     * TTL elapsed. Rows whose key no longer has a definition are skipped —
     * they need operator attention ({@link #despawnEncounter}).
     *
     * @return how many rows this sweep expired.
     */
    public CompletableFuture<Integer> expireEncounters() {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            int expired = 0;
            for (Encounter encounter : RpgStore.encountersByState(tx,
                    EncounterState.ACTIVE)) {
                EncounterDefinition definition = config.encounters().get(
                        encounter.key());
                if (definition == null) {
                    continue;
                }
                long floor = now - definition.ttlSeconds() * 1000L;
                if (RpgStore.expireEncounter(tx, encounter.encounterId(), floor,
                        now) > 0) {
                    RpgStore.insertEvent(tx,
                            "encounter:" + encounter.encounterId() + ":expire", null,
                            "ENCOUNTER_EXPIRE", "key=" + encounter.key(), "EXPIRED",
                            now);
                    expired++;
                }
            }
            return expired;
        });
    }

    /**
     * Admin path: despawns an encounter row (unwedge tool for rows whose
     * definition disappeared; counts as resolved for cooldown purposes).
     */
    public CompletableFuture<RpgResult<Encounter>> despawnEncounter(
            @NotNull UUID encounterId, @Nullable String reason) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            Encounter encounter = RpgStore.encounter(tx, encounterId)
                    .orElseThrow(() -> new Refusal(RpgOutcome.ENCOUNTER_NOT_FOUND,
                            "no encounter " + encounterId));
            if (RpgStore.despawnEncounter(tx, encounterId, now) == 0) {
                return RpgResult.<Encounter>unchanged(RpgOutcome.SOUL_ATTACH_NOOP,
                        "state=" + encounter.state(), encounter);
            }
            RpgStore.insertEvent(tx, "encounter:" + encounterId + ":despawn", null,
                    "ENCOUNTER_DESPAWN",
                    "reason=" + (reason == null ? "admin" : reason), "DESPAWNED", now);
            return RpgResult.changed(RpgOutcome.ENCOUNTER_EXPIRED, RpgStore.encounter(
                    tx, encounterId).orElseThrow());
        }).exceptionally(RpgService::<Encounter>toFailure);
    }

    /** @return encounters in the given state, earliest first. */
    public CompletableFuture<List<Encounter>> encounters(@NotNull EncounterState state) {
        return db.transaction(tx -> RpgStore.encountersByState(tx, state));
    }

    /** @return one encounter, when it exists. */
    public CompletableFuture<Optional<Encounter>> encounter(@NotNull UUID encounterId) {
        return db.transaction(tx -> RpgStore.encounter(tx, encounterId));
    }

    // ------------------------------------------------------------------
    // Events (audit reads)
    // ------------------------------------------------------------------

    /** @return every audit event for a player, earliest first. */
    public CompletableFuture<List<EventRow>> eventsFor(@NotNull UUID player) {
        return db.transaction(tx -> RpgStore.eventsFor(tx, player));
    }

    /** @return every audit event, earliest first. */
    public CompletableFuture<List<EventRow>> eventsAll() {
        return db.transaction(RpgStore::eventsAll);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    static String eventKeyCommit(UUID claimId) {
        return "claim:" + claimId + ":commit";
    }

    static String eventKeyRelease(UUID claimId) {
        return "claim:" + claimId + ":release";
    }
}
