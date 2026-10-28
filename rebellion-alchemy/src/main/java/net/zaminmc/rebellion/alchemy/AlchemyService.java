package net.zaminmc.rebellion.alchemy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.alchemy.db.AlchemyStore;
import net.zaminmc.rebellion.alchemy.db.AlchemyStore.EventRow;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Alchemy authority (ADR-0019): the transactional orchestration over the
 * five persistence tables. Every mutation is one database transaction whose
 * rows commit all-or-none, exposed as a future completing on the
 * persistence executor (never the main thread — callers marshal back).
 * Typed refusals are thrown inside the transaction (rolling it back) and
 * surface as {@link AlchemyResult} failures.
 *
 * <h2>Brewing XP (master prompt §5.7: "persisted brewing level/XP")</h2>
 * {@link #awardBrewXp} is idempotent per (player, source): the audit event
 * insert is the gate — it happens <em>before</em> any mutation, so a
 * duplicate source resolves to the typed no-op carrying the player's
 * current progress with nothing touched. A fresh award either inserts the
 * first progress row or uses the atomic accumulate
 * {@code total_xp = total_xp + ?} (single-statement read-modify-write — no
 * check-then-set race; the concurrent-first-award PK violation is
 * classified into the accumulate path). The pre/post reads bracket the
 * accumulate so the derived level walk reports LEVEL_UP honestly; the level
 * itself is never stored — {@link Leveling} derives it from the row on
 * every read.
 *
 * <h2>The claim protocol (§5.7: "atomic potion/cost consumption and safe
 * fallback if a player inventory changes between rule evaluation and
 * execution")</h2>
 * Both inventory-spanning operations (unlock purchases, auto-consume
 * executions) ride the two-phase ADR-0016 discipline:
 * <ol>
 *   <li>{@link #reserveUnlock} / {@link #reserveAutoConsume} — one
 *       transaction validates every rule (level gate, prerequisites,
 *       ownership guards, cooldown authority, the single-reservation race
 *       guard) and inserts the RESERVED claim carrying the controller's
 *       digest.</li>
 *   <li><em>Controller phase:</em> remove the physical items (cost items /
 *       the potion). Failure → {@link #releaseReservation} — the typed
 *       refund path. The controller never removes items without a live
 *       reservation.</li>
 *   <li>{@link #commitUnlock} / {@link #commitAutoConsume} — one
 *       transaction: CAS the claim RESERVED→COMMITTED together with the
 *       effect row (the unlock grant, or the rule's cooldown stamp) and
 *       the audit event. All-or-none. Re-delivery resolves through the CAS
 *       to the typed no-op carrying the original outcome.</li>
 * </ol>
 */
public final class AlchemyService {

    /** Single-award bound (config-independent safety ceiling). */
    public static final long MAX_AWARD = 1_000_000L;

    private final RebellionDatabase db;
    private final AlchemyConfig config;
    private final LongSupplier clock;

    public AlchemyService(@NotNull RebellionDatabase db, @NotNull AlchemyConfig config,
            @NotNull LongSupplier clock) {
        this.db = db;
        this.config = config;
        this.clock = clock;
    }

    /** Internal typed refusal; thrown inside a transaction rolls it back. */
    private static final class Refusal extends RuntimeException {
        final AlchemyOutcome outcome;
        final String detail;

        Refusal(AlchemyOutcome outcome, String detail) {
            super(detail, null, false, false);
            this.outcome = outcome;
            this.detail = detail;
        }
    }

    /** Unwraps a failed transaction into a typed failure result. */
    private static <T> AlchemyResult<T> toFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException c && c.getCause() != null
                ? c.getCause() : error;
        if (cause instanceof Refusal refusal) {
            return AlchemyResult.failure(refusal.outcome, refusal.detail);
        }
        return AlchemyResult.failure(AlchemyOutcome.DB_ERROR, String.valueOf(
                cause.getMessage() == null ? cause.getClass().getSimpleName()
                        : cause.getMessage()));
    }

    // ------------------------------------------------------------------
    // Brewing XP
    // ------------------------------------------------------------------

    /**
     * Idempotent XP award: the {@code sourceKey} event PK is the gate. A
     * replayed source resolves to {@code XP_NOOP} carrying the current
     * progress; a fresh award inserts-or-accumulates atomically and
     * reports {@code XP_GAINED} or {@code LEVEL_UP}.
     *
     * @param amount 1..{@link #MAX_AWARD}
     * @param sourceKey the controller's deterministic brew identity (the
     *        idempotency key — grammar-checked, meaning opaque here)
     */
    public CompletableFuture<AlchemyResult<BrewProgress>> awardBrewXp(
            @NotNull UUID player, long amount, @NotNull String sourceKey) {
        if (amount < 1 || amount > MAX_AWARD) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.ARGUMENT_INVALID,
                    "award amount must be 1.." + MAX_AWARD + " (got " + amount + ")"));
        }
        if (sourceKey == null || !AlchemyConfig.SOURCE_KEY.matcher(sourceKey).matches()) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.ARGUMENT_INVALID,
                    "source key must match [a-z0-9_:\\-]{1,96} (got '" + sourceKey + "')"));
        }
        return db.<AlchemyResult<BrewProgress>>transaction(tx -> {
            long now = clock.getAsLong();
            String eventKey = xpKey(player, sourceKey);

            // Idempotency gate BEFORE any mutation: the duplicate source is a
            // clean no-op carrying the live progress.
            if (AlchemyStore.insertEvent(tx, eventKey, player, "XP_AWARD",
                    "source=" + sourceKey + " amount=" + amount, null, now) == 0) {
                long total = AlchemyStore.progress(tx, player).orElse(new long[] {0, now})[0];
                return AlchemyResult.<BrewProgress>unchanged(AlchemyOutcome.XP_NOOP,
                        "source '" + sourceKey + "' already paid",
                        Leveling.project(player, config.brewing(), total));
            }

            // Fresh award: first-row insert or the atomic accumulate. The
            // concurrent-first-award PK violation is classified inside the
            // store into the 0-return, and the accumulate takes over.
            long pre = AlchemyStore.progress(tx, player).orElse(new long[] {0, now})[0];
            if (AlchemyStore.insertProgress(tx, player, amount, now) == 0) {
                AlchemyStore.addXp(tx, player, amount, now);
            }
            long post = AlchemyStore.progress(tx, player).orElseThrow()[0];

            int fromLevel = Leveling.levelFor(config.brewing(), pre);
            int toLevel = Leveling.levelFor(config.brewing(), post);
            boolean levelUp = toLevel > fromLevel;
            AlchemyStore.updateEventOutcome(tx, eventKey,
                    levelUp ? "LEVEL_UP" : "XP",
                    "source=" + sourceKey + " amount=" + amount
                            + " level " + fromLevel + "->" + toLevel);

            return AlchemyResult.changed(
                    levelUp ? AlchemyOutcome.LEVEL_UP : AlchemyOutcome.XP_GAINED,
                    levelUp ? "level " + fromLevel + "->" + toLevel : null,
                    Leveling.project(player, config.brewing(), post));
        }).exceptionally(AlchemyService::<BrewProgress>toFailure);
    }

    /** @return the player's derived progress (zero progress for new players). */
    public CompletableFuture<BrewProgress> progress(@NotNull UUID player) {
        long now = clock.getAsLong();
        return db.transaction(tx -> Leveling.project(player, config.brewing(),
                AlchemyStore.progress(tx, player).orElse(new long[] {0, now})[0]));
    }

    // ------------------------------------------------------------------
    // Unlock reads
    // ------------------------------------------------------------------

    /** @return every unlock the player owns, earliest first. */
    public CompletableFuture<List<UnlockGrant>> unlocks(@NotNull UUID player) {
        return db.transaction(tx -> AlchemyStore.unlocksFor(tx, player));
    }

    /** @return whether the player owns the unlock. */
    public CompletableFuture<Boolean> hasUnlock(@NotNull UUID player,
            @NotNull String key) {
        return db.transaction(tx -> AlchemyStore.unlock(tx, player, key).isPresent());
    }

    // ------------------------------------------------------------------
    // Unlock purchase protocol — reserve / release / commit
    // ------------------------------------------------------------------

    /**
     * Step 1 of the purchase protocol: validates the definition, the
     * derived level gate, every prerequisite (answered from the unlock
     * ledger rows), non-ownership and the single-reservation race guard,
     * then inserts the RESERVED claim. The controller may remove cost
     * items only while a live reservation exists.
     *
     * @param costDigest the controller's snapshot of the exact cost set it
     *        verified (refund evidence)
     */
    public CompletableFuture<AlchemyResult<AlchemyClaim>> reserveUnlock(
            @NotNull UUID player, @NotNull String unlockKey,
            @NotNull String costDigest) {
        if (unlockKey == null || unlockKey.isBlank() || costDigest == null
                || costDigest.isBlank()) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.ARGUMENT_INVALID,
                    "unlock key and cost digest are required"));
        }
        UnlockDefinition definition = config.unlocks().get(unlockKey);
        if (definition == null) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.UNLOCK_UNKNOWN,
                    "no unlock definition for '" + unlockKey + "'"));
        }
        if (!definition.enabled()) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.UNLOCK_DISABLED,
                    "unlock '" + unlockKey + "' is disabled"));
        }
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            long total = AlchemyStore.progress(tx, player)
                    .orElse(new long[] {0, now})[0];
            int level = Leveling.levelFor(config.brewing(), total);
            if (level < definition.requiredLevel()) {
                throw new Refusal(AlchemyOutcome.LEVEL_TOO_LOW, "level " + level
                        + " < required " + definition.requiredLevel());
            }
            for (String prerequisite : definition.prerequisites()) {
                if (AlchemyStore.unlock(tx, player, prerequisite).isEmpty()) {
                    throw new Refusal(AlchemyOutcome.PREREQUISITE_MISSING,
                            "prerequisite '" + prerequisite + "' not owned");
                }
            }
            if (AlchemyStore.unlock(tx, player, unlockKey).isPresent()) {
                throw new Refusal(AlchemyOutcome.ALREADY_OWNED,
                        "unlock '" + unlockKey + "' is already owned");
            }
            if (AlchemyStore.reservedCount(tx, player, ClaimPurpose.UNLOCK_PURCHASE,
                    unlockKey) > 0) {
                throw new Refusal(AlchemyOutcome.RESERVATION_HELD, "a reservation is "
                        + "already open for this unlock - release it first "
                        + "(cost race guard)");
            }
            UUID claimId = UUID.randomUUID();
            AlchemyStore.insertClaim(tx, claimId, player, ClaimPurpose.UNLOCK_PURCHASE,
                    unlockKey, costDigest, now);
            return AlchemyResult.changed(AlchemyOutcome.RESERVED, new AlchemyClaim(
                    claimId, player, ClaimPurpose.UNLOCK_PURCHASE, unlockKey,
                    ClaimState.RESERVED, costDigest, now, null));
        }).exceptionally(AlchemyService::<AlchemyClaim>toFailure);
    }

    /**
     * Refund path: releases a RESERVED claim (inventory refusal, operator
     * cancel). Idempotent — a second release resolves to
     * {@code RELEASE_NOOP}.
     */
    public CompletableFuture<AlchemyResult<AlchemyClaim>> releaseReservation(
            @NotNull UUID claimId, @NotNull UUID player, @Nullable String reason) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            AlchemyClaim claim = readOwnedClaim(tx, claimId, player);
            if (AlchemyStore.releaseClaim(tx, claimId, now) == 0) {
                return AlchemyResult.<AlchemyClaim>unchanged(
                        AlchemyOutcome.RELEASE_NOOP,
                        "claim is " + claim.state(), claim);
            }
            AlchemyStore.insertEvent(tx, eventKeyRelease(claimId), player,
                    "CLAIM_RELEASE", "purpose=" + claim.purpose() + " subject="
                            + claim.subjectKey() + " reason="
                            + (reason == null ? "manual" : reason),
                    "RELEASED", now);
            return AlchemyResult.changed(AlchemyOutcome.RELEASED, new AlchemyClaim(
                    claimId, player, claim.purpose(), claim.subjectKey(),
                    ClaimState.RELEASED, claim.digest(), claim.createdUtc(), now));
        }).exceptionally(AlchemyService::<AlchemyClaim>toFailure);
    }

    /**
     * Crash-recovery sweep: releases RESERVED claims older than the floor.
     * Per-claim event rows preserve the digest as the operator's refund
     * evidence for the window where the server died after item removal but
     * before commit.
     *
     * @return how many stale reservations were released.
     */
    public CompletableFuture<Integer> releaseStaleClaims(long olderThanMs) {
        if (olderThanMs < 0) {
            throw new IllegalArgumentException("olderThanMs must be >= 0");
        }
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            long floor = now - olderThanMs;
            List<AlchemyClaim> stale = AlchemyStore.staleClaims(tx, floor);
            for (AlchemyClaim claim : stale) {
                if (AlchemyStore.releaseClaim(tx, claim.claimId(), now) > 0) {
                    AlchemyStore.insertEvent(tx, eventKeyRelease(claim.claimId()),
                            claim.playerUuid(), "CLAIM_RELEASE",
                            "purpose=" + claim.purpose() + " subject="
                                    + claim.subjectKey() + " reason=stale-sweep",
                            "RELEASED", now);
                }
            }
            return stale.size();
        });
    }

    /**
     * Step 3 of the purchase protocol: commits the claim and inserts the
     * unlock row, all-or-none. An unlock granted by an admin between
     * reserve and commit still spends the claim (detail says so) —
     * resources were physically consumed under the reservation.
     */
    public CompletableFuture<AlchemyResult<UnlockGrant>> commitUnlock(
            @NotNull UUID claimId, @NotNull UUID player) {
        return db.<AlchemyResult<UnlockGrant>>transaction(tx -> {
            long now = clock.getAsLong();
            AlchemyClaim claim = readOwnedClaim(tx, claimId, player);
            if (claim.purpose() != ClaimPurpose.UNLOCK_PURCHASE) {
                throw new Refusal(AlchemyOutcome.ARGUMENT_INVALID, "claim "
                        + claimId + " is " + claim.purpose() + " - use its own commit path");
            }
            if (claim.state() != ClaimState.RESERVED) {
                return commitNoop(tx, claimId, claim);
            }
            UnlockDefinition definition = config.unlocks().get(claim.subjectKey());
            if (definition == null) {
                throw new Refusal(AlchemyOutcome.UNLOCK_UNKNOWN,
                        "definition removed while reserved: '"
                                + claim.subjectKey() + "'");
            }
            if (AlchemyStore.commitClaim(tx, claimId, now) == 0) {
                return commitNoop(tx, claimId, claim);
            }
            int rows = AlchemyStore.insertUnlock(tx, player, claim.subjectKey(), now,
                    claimId);
            UnlockGrant grant = AlchemyStore.unlock(tx, player, claim.subjectKey())
                    .orElseThrow();
            AlchemyStore.insertEvent(tx, eventKeyCommit(claimId), player,
                    "UNLOCK_COMMIT", "unlock=" + claim.subjectKey(),
                    rows == 1 ? "GRANTED" : "ALREADY_OWNED", now);
            return AlchemyResult.changed(AlchemyOutcome.UNLOCK_GRANTED,
                    rows == 1 ? null : "already owned - cost spent", grant);
        }).exceptionally(AlchemyService::<UnlockGrant>toFailure);
    }

    /**
     * Admin/recovery grant path: inserts the unlock row directly
     * (idempotent — an owned unlock resolves to {@code ALREADY_OWNED}
     * carrying the original grant). Deliberately ignores the definition's
     * {@code enabled} flag — a staff override is a recovery tool — but the
     * key must reference a real definition, and every grant is audited.
     */
    public CompletableFuture<AlchemyResult<UnlockGrant>> grantUnlock(
            @NotNull UUID player, @NotNull String unlockKey,
            @Nullable String reason) {
        if (unlockKey == null || unlockKey.isBlank()) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.ARGUMENT_INVALID, "unlock key is required"));
        }
        UnlockDefinition definition = config.unlocks().get(unlockKey);
        if (definition == null) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.UNLOCK_UNKNOWN,
                    "no unlock definition for '" + unlockKey + "'"));
        }
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            int rows = AlchemyStore.insertUnlock(tx, player, unlockKey, now, null);
            UnlockGrant grant = AlchemyStore.unlock(tx, player, unlockKey)
                    .orElseThrow();
            if (rows == 0) {
                return AlchemyResult.<UnlockGrant>unchanged(
                        AlchemyOutcome.UNLOCK_GRANT_NOOP,
                        "granted " + (grant.claimId() == null ? "by admin"
                                : "via purchase") + " at " + grant.grantedUtc(),
                        grant);
            }
            AlchemyStore.insertEvent(tx, unlockKey(player, unlockKey), player,
                    "ADMIN_GRANT", "unlock=" + unlockKey + " reason="
                            + (reason == null ? "admin" : reason), "GRANTED", now);
            return AlchemyResult.changed(AlchemyOutcome.UNLOCK_GRANTED, grant);
        }).exceptionally(AlchemyService::<UnlockGrant>toFailure);
    }

    // ------------------------------------------------------------------
    // Auto-consumption rules
    // ------------------------------------------------------------------

    /**
     * Defines a player rule after every operator-policy gate passes
     * (trigger whitelisted, potion whitelisted, threshold inside the
     * trigger's bounds, cooldown at or above the floor, priority in range,
     * per-player cap). Idempotent by the {@code rule_id} primary key.
     *
     * @param reason audited caller context (command, menu, admin)
     */
    public CompletableFuture<AlchemyResult<AutoRule>> defineAutoRule(
            @NotNull UUID ruleId, @NotNull UUID player, @NotNull AutoTrigger trigger,
            @NotNull PotionType potion, @Nullable Integer threshold,
            int cooldownSeconds, int priority, @NotNull StorageScope storage,
            @Nullable String reason) {
        if (ruleId == null || player == null || trigger == null || potion == null) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.ARGUMENT_INVALID,
                    "rule id, player, trigger and potion are required"));
        }
        if (storage == null) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.STORAGE_INVALID,
                    "storage must be one of BAG, INVENTORY, ANY"));
        }
        AutoPolicy policy = config.autoPolicy();
        if (!policy.triggerEnabled(trigger)) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.TRIGGER_DISABLED, "trigger " + trigger
                            + " is disabled by the operator policy"));
        }
        if (!policy.potionAllowed(potion)) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.POTION_NOT_ALLOWED, "potion " + potion
                            + " is not in the policy whitelist"));
        }
        if (trigger.usesThreshold()) {
            if (threshold == null || !policy.healthThresholdLegal(threshold)) {
                return CompletableFuture.completedFuture(AlchemyResult.failure(
                        AlchemyOutcome.THRESHOLD_OUT_OF_BOUNDS,
                        trigger + " threshold must be " + policy.healthMin() + ".."
                                + policy.healthMax() + " (got " + threshold + ")"));
            }
        } else if (threshold != null) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.ARGUMENT_INVALID,
                    trigger + " carries no threshold"));
        }
        if (!policy.cooldownLegal(cooldownSeconds)) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.COOLDOWN_BELOW_FLOOR, "cooldown must be at least "
                            + policy.minCooldownSeconds() + "s (got " + cooldownSeconds
                            + "s) - the anti-bot floor"));
        }
        if (!policy.priorityLegal(priority)) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.PRIORITY_OUT_OF_RANGE, "priority must be "
                            + policy.minPriority() + ".." + policy.maxPriority()
                            + " (got " + priority + ")"));
        }
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            Optional<AutoRule> existing = AlchemyStore.rule(tx, ruleId);
            if (existing.isPresent()) {
                return AlchemyResult.<AutoRule>unchanged(AlchemyOutcome.RULE_EXISTS,
                        "rule " + ruleId + " already exists", existing.get());
            }
            if (AlchemyStore.liveRuleCount(tx, player) >= policy.maxRulesPerPlayer()) {
                throw new Refusal(AlchemyOutcome.RULE_CAP_REACHED, "player already "
                        + "holds " + policy.maxRulesPerPlayer() + " live rule(s)");
            }
            AutoRule rule = new AutoRule(ruleId, player, trigger, potion.name(),
                    threshold, cooldownSeconds, priority, storage, true, now, now,
                    null, null);
            if (AlchemyStore.insertRuleIfAbsent(tx, rule) == 0) {
                return AlchemyResult.<AutoRule>unchanged(AlchemyOutcome.RULE_EXISTS,
                        "rule " + ruleId + " already exists",
                        AlchemyStore.rule(tx, ruleId).orElseThrow());
            }
            AlchemyStore.insertEvent(tx, ruleKey(ruleId, "define"), player,
                    "RULE_DEFINE", "trigger=" + trigger + " potion=" + potion
                            + " threshold=" + threshold + " cooldown=" + cooldownSeconds
                            + " priority=" + priority + " storage=" + storage
                            + " reason=" + (reason == null ? "player" : reason),
                    "DEFINED", now);
            return AlchemyResult.changed(AlchemyOutcome.RULE_DEFINED, rule);
        }).exceptionally(AlchemyService::<AutoRule>toFailure);
    }

    /**
     * Ownership-guarded toggle (the master prompt's per-rule "toggle"):
     * flips {@code enabled} on a live rule and audits the reason.
     * Idempotent — the CAS pins the prior state.
     */
    public CompletableFuture<AlchemyResult<AutoRule>> toggleAutoRule(
            @NotNull UUID ruleId, @NotNull UUID player, boolean enabled,
            @Nullable String reason) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            AutoRule rule = readOwnedLiveRule(tx, ruleId, player);
            if (AlchemyStore.setRuleEnabled(tx, ruleId, player, enabled, now) == 0) {
                return AlchemyResult.<AutoRule>unchanged(AlchemyOutcome.TOGGLE_NOOP,
                        "rule already enabled=" + enabled, rule);
            }
            AlchemyStore.insertEvent(tx, ruleKey(ruleId, "toggle"), player,
                    "RULE_TOGGLE", "reason=" + (reason == null ? "player" : reason),
                    enabled ? "ENABLED" : "DISABLED", now);
            return AlchemyResult.changed(enabled
                    ? AlchemyOutcome.RULE_ENABLED : AlchemyOutcome.RULE_DISABLED,
                    new AutoRule(ruleId, player, rule.trigger(), rule.potionType(),
                            rule.threshold(), rule.cooldownSeconds(), rule.priority(),
                            rule.storage(), enabled, rule.createdUtc(), now,
                            rule.removedUtc(), rule.lastFiredUtc()));
        }).exceptionally(AlchemyService::<AutoRule>toFailure);
    }

    /**
     * Ownership-guarded removal: stamps {@code removed_utc} (rows are kept
     * for forensics — a removed rule stops resolving everywhere).
     * Genuinely idempotent: removing an already-removed rule resolves to
     * {@code RULE_REMOVE_NOOP} (nothing owed), unlike toggle/reserve which
     * treat a dead rule as not found.
     */
    public CompletableFuture<AlchemyResult<AutoRule>> removeAutoRule(
            @NotNull UUID ruleId, @NotNull UUID player, @Nullable String reason) {
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            AutoRule rule = AlchemyStore.rule(tx, ruleId)
                    .orElseThrow(() -> new Refusal(AlchemyOutcome.RULE_NOT_FOUND,
                            "no rule " + ruleId));
            if (!rule.playerUuid().equals(player)) {
                throw new Refusal(AlchemyOutcome.ARGUMENT_INVALID,
                        "rule belongs to another player");
            }
            if (AlchemyStore.removeRule(tx, ruleId, player, now) == 0) {
                return AlchemyResult.<AutoRule>unchanged(
                        AlchemyOutcome.RULE_REMOVE_NOOP, "rule already removed", rule);
            }
            AlchemyStore.insertEvent(tx, ruleKey(ruleId, "remove"), player,
                    "RULE_REMOVE", "reason=" + (reason == null ? "player" : reason),
                    "REMOVED", now);
            return AlchemyResult.changed(AlchemyOutcome.RULE_REMOVED, new AutoRule(
                    ruleId, player, rule.trigger(), rule.potionType(),
                    rule.threshold(), rule.cooldownSeconds(), rule.priority(),
                    rule.storage(), rule.enabled(), rule.createdUtc(), now, now,
                    rule.lastFiredUtc()));
        }).exceptionally(AlchemyService::<AutoRule>toFailure);
    }

    /**
     * The resolution set: the player's enabled, live rules in the
     * deterministic conflict order (priority, then created, then rule id).
     * The controller evaluates at most one rule per trigger per cycle from
     * this list.
     */
    public CompletableFuture<List<AutoRule>> rules(@NotNull UUID player) {
        return db.transaction(tx -> AlchemyStore.liveRulesFor(tx, player));
    }

    /** Forensics read: every rule row, removed included. */
    public CompletableFuture<List<AutoRule>> allRules(@NotNull UUID player) {
        return db.transaction(tx -> AlchemyStore.allRulesFor(tx, player));
    }

    /** @return one rule, when it exists. */
    public CompletableFuture<Optional<AutoRule>> rule(@NotNull UUID ruleId) {
        return db.transaction(tx -> AlchemyStore.rule(tx, ruleId));
    }

    // ------------------------------------------------------------------
    // Auto-consumption execution protocol — reserve / commit
    // ------------------------------------------------------------------

    /**
     * Step 1 of the execution protocol: validates the rule (owned, live,
     * enabled, cooldown authority passed — answered from the row) and the
     * single-reservation guard, then inserts the RESERVED claim. The
     * controller may drink/remove the potion only while this reservation
     * exists.
     *
     * @param potionDigest the controller's snapshot of the exact potion it
     *        located and intends to consume (refund evidence)
     */
    public CompletableFuture<AlchemyResult<AlchemyClaim>> reserveAutoConsume(
            @NotNull UUID player, @NotNull UUID ruleId, @NotNull String potionDigest) {
        if (player == null || ruleId == null || potionDigest == null
                || potionDigest.isBlank()) {
            return CompletableFuture.completedFuture(AlchemyResult.failure(
                    AlchemyOutcome.ARGUMENT_INVALID,
                    "player, rule id and potion digest are required"));
        }
        return db.transaction(tx -> {
            long now = clock.getAsLong();
            AutoRule rule = AlchemyStore.rule(tx, ruleId)
                    .orElseThrow(() -> new Refusal(AlchemyOutcome.RULE_NOT_FOUND,
                            "no rule " + ruleId));
            if (!rule.playerUuid().equals(player)) {
                throw new Refusal(AlchemyOutcome.ARGUMENT_INVALID,
                        "rule belongs to another player");
            }
            if (!rule.live()) {
                throw new Refusal(AlchemyOutcome.RULE_NOT_FOUND,
                        "rule " + ruleId + " is removed");
            }
            if (!rule.enabled()) {
                throw new Refusal(AlchemyOutcome.RULE_NOT_ACTIVE,
                        "rule " + ruleId + " is toggled off");
            }
            if (!rule.offCooldown(now)) {
                throw new Refusal(AlchemyOutcome.COOLDOWN_ACTIVE, "cooldown: "
                        + ((rule.lastFiredUtc() + rule.cooldownSeconds() * 1000L - now)
                                / 1000) + "s remain");
            }
            if (AlchemyStore.reservedCount(tx, player, ClaimPurpose.AUTO_CONSUME,
                    ruleId.toString()) > 0) {
                throw new Refusal(AlchemyOutcome.RESERVATION_HELD, "an execution "
                        + "reservation is already open for this rule (drink race guard)");
            }
            UUID claimId = UUID.randomUUID();
            AlchemyStore.insertClaim(tx, claimId, player, ClaimPurpose.AUTO_CONSUME,
                    ruleId.toString(), potionDigest, now);
            return AlchemyResult.changed(AlchemyOutcome.AUTO_RESERVED,
                    new AlchemyClaim(claimId, player, ClaimPurpose.AUTO_CONSUME,
                            ruleId.toString(), ClaimState.RESERVED, potionDigest,
                            now, null));
        }).exceptionally(AlchemyService::<AlchemyClaim>toFailure);
    }

    /**
     * Step 3 of the execution protocol: commits the claim and stamps the
     * rule's cooldown authority ({@code last_fired_utc}) in one
     * all-or-none transaction. A rule disabled or removed mid-flight loses
     * its stamp but not its audit — the potion was already consumed under
     * the reservation, and the outcome records the unstamped state.
     */
    public CompletableFuture<AlchemyResult<AutoRule>> commitAutoConsume(
            @NotNull UUID claimId, @NotNull UUID player) {
        return db.<AlchemyResult<AutoRule>>transaction(tx -> {
            long now = clock.getAsLong();
            AlchemyClaim claim = readOwnedClaim(tx, claimId, player);
            if (claim.purpose() != ClaimPurpose.AUTO_CONSUME) {
                throw new Refusal(AlchemyOutcome.ARGUMENT_INVALID, "claim "
                        + claimId + " is " + claim.purpose() + " - use its own commit path");
            }
            if (claim.state() != ClaimState.RESERVED) {
                return commitNoop(tx, claimId, claim);
            }
            AutoRule rule = AlchemyStore.rule(tx, UUID.fromString(claim.subjectKey()))
                    .orElseThrow(() -> new Refusal(AlchemyOutcome.RULE_NOT_FOUND,
                            "rule vanished mid-flight: " + claim.subjectKey()));
            if (AlchemyStore.commitClaim(tx, claimId, now) == 0) {
                return commitNoop(tx, claimId, claim);
            }
            int stamped = AlchemyStore.stampLastFired(tx, rule.ruleId(), now, now);
            AlchemyStore.insertEvent(tx, eventKeyCommit(claimId), player,
                    "AUTO_CONSUME", "rule=" + rule.ruleId() + " potion="
                            + rule.potionType() + " digest=" + claim.digest(),
                    stamped == 1 ? "FIRED" : "FIRED_UNSTAMPED", now);
            AutoRule updated = AlchemyStore.rule(tx, rule.ruleId()).orElseThrow();
            return AlchemyResult.changed(AlchemyOutcome.AUTO_CONSUMED,
                    stamped == 1 ? null : "rule disabled/removed mid-flight - "
                            + "cooldown stamp not applied",
                    updated);
        }).exceptionally(AlchemyService::<AutoRule>toFailure);
    }

    // ------------------------------------------------------------------
    // Claims + events (audit reads)
    // ------------------------------------------------------------------

    /** @return every claim row for a player, earliest first (forensics). */
    public CompletableFuture<List<AlchemyClaim>> claims(@NotNull UUID player) {
        return db.transaction(tx -> AlchemyStore.claimsFor(tx, player));
    }

    /** @return every audit event for a player, earliest first. */
    public CompletableFuture<List<EventRow>> eventsFor(@NotNull UUID player) {
        return db.transaction(tx -> AlchemyStore.eventsFor(tx, player));
    }

    /** @return every audit event, earliest first. */
    public CompletableFuture<List<EventRow>> eventsAll() {
        return db.transaction(AlchemyStore::eventsAll);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Claim pre-flight: existence and ownership only. A claim that is no
     *  longer RESERVED is <em>not</em> a refusal — callers resolve it
     *  through {@link #commitNoop} so the original committed outcome is
     *  carried (re-delivery contract). */
    private AlchemyClaim readOwnedClaim(TransactionContext tx, UUID claimId,
            UUID player) throws java.sql.SQLException {
        AlchemyClaim claim = AlchemyStore.claim(tx, claimId)
                .orElseThrow(() -> new Refusal(AlchemyOutcome.CLAIM_NOT_FOUND,
                        "no claim " + claimId));
        if (!claim.playerUuid().equals(player)) {
            throw new Refusal(AlchemyOutcome.ARGUMENT_INVALID,
                    "claim belongs to another player");
        }
        return claim;
    }

    /** Rule pre-flight: existence, liveness and ownership. */
    private AutoRule readOwnedLiveRule(TransactionContext tx, UUID ruleId, UUID player)
            throws java.sql.SQLException {
        AutoRule rule = AlchemyStore.rule(tx, ruleId)
                .orElseThrow(() -> new Refusal(AlchemyOutcome.RULE_NOT_FOUND,
                        "no rule " + ruleId));
        if (!rule.playerUuid().equals(player)) {
            throw new Refusal(AlchemyOutcome.ARGUMENT_INVALID,
                    "rule belongs to another player");
        }
        if (!rule.live()) {
            throw new Refusal(AlchemyOutcome.RULE_NOT_FOUND,
                    "rule " + ruleId + " is removed");
        }
        return rule;
    }

    /** Re-delivery resolution: carry the original committed outcome. */
    @SuppressWarnings("unchecked")
    private <T> AlchemyResult<T> commitNoop(TransactionContext tx, UUID claimId,
            AlchemyClaim claim) throws java.sql.SQLException {
        Optional<EventRow> event = AlchemyStore.event(tx, eventKeyCommit(claimId));
        String original = event.map(EventRow::outcome).orElse("UNKNOWN");
        return (AlchemyResult<T>) AlchemyResult.unchanged(AlchemyOutcome.COMMIT_NOOP,
                "claim already " + claim.state() + " (original outcome " + original
                        + ")", claim);
    }

    static String xpKey(UUID player, String sourceKey) {
        return "xp:" + player + ":" + sourceKey;
    }

    static String eventKeyCommit(UUID claimId) {
        return "claim:" + claimId + ":commit";
    }

    static String eventKeyRelease(UUID claimId) {
        return "claim:" + claimId + ":release";
    }

    static String ruleKey(UUID ruleId, String action) {
        return "rule:" + ruleId + ":" + action;
    }

    static String unlockKey(UUID player, String unlockKey) {
        return "unlock:" + player + ":" + unlockKey;
    }
}
