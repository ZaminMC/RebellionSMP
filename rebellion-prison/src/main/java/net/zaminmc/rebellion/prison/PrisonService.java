package net.zaminmc.rebellion.prison;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import net.zaminmc.rebellion.api.database.MigrationSpec;
import net.zaminmc.rebellion.api.database.RebellionDatabase;
import net.zaminmc.rebellion.api.database.TransactionContext;
import net.zaminmc.rebellion.api.economy.EconomyOutcome;
import net.zaminmc.rebellion.api.economy.EconomyProvider;
import net.zaminmc.rebellion.api.economy.EconomyResult;
import net.zaminmc.rebellion.prison.PrisonConfig.DeathPolicy;
import net.zaminmc.rebellion.prison.PrisonConfig.RehabTask;
import net.zaminmc.rebellion.prison.db.PrisonSchema;
import net.zaminmc.rebellion.prison.db.PrisonStore;
import net.zaminmc.rebellion.prison.db.PrisonStore.DeathRow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Prison authority (ADR-0021, master prompt §5.8): the transactional
 * orchestration over the six persistence tables. Every single-table
 * mutation is one database transaction whose rows commit all-or-none,
 * exposed as a future completing on the persistence executor; typed
 * refusals are thrown inside the transaction (rolling it back) and
 * surface as {@link PrisonResult} failures.
 *
 * <h2>Death processing (§5.8: "apply a configurable maximum-health
 * penalty and death-score change once")</h2>
 * {@link #applyDeath} is idempotent per (player, deathKey): the audit
 * event insert is the gate — a replayed death (death and respawn both
 * firing, a relog re-delivery) resolves to the typed no-op carrying the
 * live profile. A fresh death inserts the forensic death row, applies the
 * score accumulate and the write-clamped scar in one statement, then
 * runs the configured routing: {@code NEVER} scores without jailing;
 * {@code SCORE_THRESHOLD} jails once the accumulated score reaches the
 * threshold; {@code ALWAYS} (the shipped default) creates the durable
 * PENDING sentence — the §5.8 {@code pending_imprisonment} state the
 * controller retries safely on respawn/join. The structural live guard
 * means a death <em>inside</em> prison still scores and scars but can
 * never book a second sentence.
 *
 * <h2>The release-fee claim protocol (§5.8: "a very expensive paid
 * release route"; §5.9 transparency)</h2>
 * {@link #payRelease} is the one deliberately <em>blocking</em>
 * orchestration — it spans three stores in time (prison rows → the
 * provider's ledger → prison rows), so it joins two bounded persistence
 * round-trips on the CALLER's thread. Callers must invoke it off the main
 * thread (§5.9) and never from a persistence-executor callback. The
 * protocol: (1) the sentence must be JAILED; (2) find-or-freeze the QUOTED
 * fee row — the frozen §5.9 audit (score, factor, multiplier, rounding,
 * clamp, amount) that cannot drift across retries; (3) the provider debit
 * under the idempotency key {@code prison-fee:<feeId>} — a replay resolves
 * to the typed no-op carrying the original entry id; (4) one all-or-none
 * transaction CASes the fee QUOTED→PAID (stamping the provider id and
 * ledger entry — the transaction id) together with the sentence JAILED→
 * RELEASED(PAID). Every crash window reconciles: before the debit the
 * QUOTED row is simply re-used; after the debit the retry's no-op debit
 * lets the commit finish with the original entry id.
 *
 * <h2>Non-paid release routes (§5.8 "auditable staff overrides")</h2>
 * {@link #markReleased} transitions a live sentence out by ESCAPE, RESCUE
 * or ADMIN_OVERRIDE, voiding any frozen quote, recording the acting staff
 * member (mandatory for overrides — never anonymous) and leaving the
 * death score untouched: an override never silently clears debt, it
 * ends the sentence on the record.
 *
 * <h2>Rehabilitation (§5.8 "idempotent … no inventory/database race that
 * grants double healing")</h2>
 * {@link #completeRehab} is idempotent per (player, task, completionKey)
 * by structure; occurrences are capped per task and the healing is
 * clamped to the scar ledger in one guarded statement — double healing is
 * impossible, over-healing is impossible, and refusals (unknown/disabled/
 * exhausted/fully-restored) roll back traceless.
 */
public final class PrisonService {

    /** Read-only view of this service's module key for boot wiring. */
    public static final String MODULE_KEY = "prison";

    private final RebellionDatabase db;
    private final PrisonConfig config;
    private final LongSupplier clock;

    public PrisonService(@NotNull RebellionDatabase db, @NotNull PrisonConfig config,
            @NotNull LongSupplier clock) {
        this.db = db;
        this.config = config;
        this.clock = clock;
    }

    /** @return the module schema registry for boot-time migration. */
    public static List<MigrationSpec> migrations() {
        return PrisonSchema.migrations();
    }

    /** Payload of {@link #applyDeath}: the ledgers plus the routing verdict. */
    public record DeathCase(@NotNull PlayerProfile profile, @Nullable Sentence sentence,
            @NotNull PrisonOutcome sentenceOutcome) {
    }

    // ------------------------------------------------------------------
    // Death processing
    // ------------------------------------------------------------------

    /**
     * Processes one player death exactly once.
     *
     * @param player the dead player's UUID (never null)
     * @param deathKey the controller's deterministic identity of the
     *        physical death (grammar-checked; stored namespaced by player,
     *        so keys never collide across players)
     */
    public CompletableFuture<PrisonResult<DeathCase>> applyDeath(
            @NotNull UUID player, @NotNull String deathKey) {
        if (deathKey == null || !PrisonConfig.EVENT_KEY.matcher(deathKey).matches()) {
            return CompletableFuture.completedFuture(PrisonResult.failure(
                    PrisonOutcome.ARGUMENT_INVALID,
                    "death key must match [a-z0-9_:\\-]{1,96} (got '" + deathKey + "')"));
        }
        return db.<PrisonResult<DeathCase>>transaction(tx -> {
            long now = clock.getAsLong();
            String storedKey = player + ":" + deathKey;
            String eventKey = "death:" + storedKey;

            // Idempotency gate BEFORE any mutation (§5.8: never deduct twice).
            if (PrisonStore.insertEvent(tx, eventKey, player, "DEATH",
                    "key=" + deathKey, null, now) == 0) {
                PlayerProfile live = PrisonStore.profile(tx, player)
                        .orElseGet(() -> PlayerProfile.zero(player, now));
                return PrisonResult.unchanged(PrisonOutcome.DEATH_NOOP,
                        "death '" + deathKey + "' already processed",
                        new DeathCase(live, null, PrisonOutcome.DEATH_NOOP));
            }

            DeathPolicy death = config.death();
            long scoreDelta = death.scorePerDeath();
            int penalty = death.healthPenaltyPoints();
            int ceiling = death.maxActivePenalty();

            // Forensic row + score accumulate + write-clamped scar.
            PrisonStore.insertDeath(tx, storedKey, player, scoreDelta, penalty, now);
            int firstPenalty = Math.min(penalty, ceiling);
            if (PrisonStore.insertProfile(tx, player, scoreDelta, firstPenalty, now)
                    == 0) {
                PrisonStore.applyDeathToProfile(tx, player, scoreDelta, penalty,
                        ceiling, now);
            }
            PlayerProfile after = PrisonStore.profile(tx, player).orElseThrow();

            // Routing (§5.8: "routed into the global prison system
            // according to configuration").
            PrisonOutcome verdict;
            Sentence created = null;
            switch (death.route()) {
                case NEVER -> verdict = PrisonOutcome.ROUTE_DISABLED;
                case SCORE_THRESHOLD -> {
                    if (after.deathScore() < death.imprisonThreshold()) {
                        verdict = PrisonOutcome.SCORE_BELOW_THRESHOLD;
                    } else {
                        created = createPendingSentence(tx, player, now);
                        verdict = created != null
                                ? PrisonOutcome.SENTENCE_PENDING
                                : PrisonOutcome.SENTENCE_EXISTS;
                    }
                }
                default -> {
                    created = createPendingSentence(tx, player, now);
                    verdict = created != null
                            ? PrisonOutcome.SENTENCE_PENDING
                            : PrisonOutcome.SENTENCE_EXISTS;
                }
            }

            PrisonStore.updateEventOutcome(tx, eventKey, "APPLIED",
                    "key=" + deathKey + " score=" + after.deathScore()
                            + " scars=" + after.scarPoints() + " route=" + verdict);
            return PrisonResult.changed(PrisonOutcome.DEATH_APPLIED,
                    "route=" + verdict,
                    new DeathCase(after, created, verdict));
        }).exceptionally(e -> toFailure(e));
    }

    /** Inserts the PENDING sentence under the structural live guard. */
    private @Nullable Sentence createPendingSentence(TransactionContext tx,
            UUID player, long now) throws java.sql.SQLException {
        PrisonConfig.Anchor anchor = config.anchor();
        UUID sentenceId = UUID.randomUUID();
        if (PrisonStore.insertPendingSentence(tx, sentenceId, player,
                anchor.world(), anchor.x(), anchor.y(), anchor.z(), anchor.yaw(),
                anchor.pitch(), now) == 0) {
            return null; // a live sentence already holds the guard slot
        }
        PrisonStore.insertEvent(tx, "sentence:" + sentenceId + ":created", player,
                "SENTENCE", "anchor=" + anchor.world() + " " + anchor.x() + ","
                        + anchor.y() + "," + anchor.z(), "PENDING", now);
        return PrisonStore.sentenceById(tx, sentenceId).orElseThrow();
    }

    // ------------------------------------------------------------------
    // Sentence lifecycle
    // ------------------------------------------------------------------

    /** Confirms the teleport landed: CAS PENDING → JAILED (idempotent). */
    public CompletableFuture<PrisonResult<Sentence>> confirmJailed(
            @NotNull UUID player) {
        return db.<PrisonResult<Sentence>>transaction(tx -> {
            long now = clock.getAsLong();
            Optional<Sentence> pending = PrisonStore.pendingSentence(tx, player);
            if (pending.isEmpty()) {
                Optional<Sentence> live = PrisonStore.liveSentence(tx, player);
                if (live.isPresent() && live.get().state() == SentenceState.JAILED) {
                    return PrisonResult.unchanged(PrisonOutcome.JAILED_NOOP,
                            "already jailed", live.get());
                }
                throw new Refusal(PrisonOutcome.NO_SENTENCE,
                        "no live sentence for " + player);
            }
            Sentence row = pending.get();
            if (PrisonStore.confirmJailed(tx, row.sentenceId()) == 0) {
                return PrisonResult.unchanged(PrisonOutcome.JAILED_NOOP,
                        "concurrently confirmed",
                        PrisonStore.sentenceById(tx, row.sentenceId()).orElse(row));
            }
            PrisonStore.insertEvent(tx, "sentence:" + row.sentenceId() + ":jailed",
                    player, "SENTENCE", "teleport confirmed", "JAILED", now);
            return PrisonResult.changed(PrisonOutcome.JAILED,
                    PrisonStore.sentenceById(tx, row.sentenceId()).orElseThrow());
        }).exceptionally(e -> toFailure(e));
    }

    /**
     * Fail-closed administrative recovery for a stuck PENDING sentence
     * (§5.8): releases it as a recorded staff override. The acting staff
     * member is mandatory — overrides are never anonymous.
     */
    public CompletableFuture<PrisonResult<Sentence>> revertPending(
            @NotNull UUID player, @Nullable UUID actor, @Nullable String reason) {
        if (actor == null) {
            return CompletableFuture.completedFuture(PrisonResult.failure(
                    PrisonOutcome.ADMIN_ACTOR_REQUIRED,
                    "a revert must record the acting staff member"));
        }
        String note = reason == null || reason.isBlank()
                ? "pending reverted by staff"
                : reason.trim();
        return db.<PrisonResult<Sentence>>transaction(tx -> {
            long now = clock.getAsLong();
            Optional<Sentence> pending = PrisonStore.pendingSentence(tx, player);
            if (pending.isEmpty()) {
                throw new Refusal(PrisonOutcome.NO_SENTENCE,
                        "no PENDING sentence for " + player);
            }
            Sentence row = pending.get();
            if (PrisonStore.releaseLive(tx, row.sentenceId(),
                    ReleaseReason.ADMIN_OVERRIDE, now) == 0) {
                throw new Refusal(PrisonOutcome.NOT_IN_STATE,
                        "sentence changed state concurrently");
            }
            voidAnyQuotedFee(tx, row.sentenceId(), player, now);
            PrisonStore.insertEvent(tx, "sentence:" + row.sentenceId() + ":released",
                    player, "SENTENCE",
                    "revert actor=" + actor + " note=" + note, "ADMIN_OVERRIDE", now);
            return PrisonResult.changed(PrisonOutcome.RELEASED,
                    "reverted by " + actor + ": " + note,
                    PrisonStore.sentenceById(tx, row.sentenceId()).orElseThrow());
        }).exceptionally(e -> toFailure(e));
    }

    /**
     * Escape / rescue / recorded staff override release (§5.8). Never
     * clears the death score — debt is a ledger fact, not a sentence
     * property; an override ends the sentence on the record instead.
     */
    public CompletableFuture<PrisonResult<Sentence>> markReleased(
            @NotNull UUID player, @NotNull ReleaseReason reason,
            @Nullable UUID actor, @Nullable String note) {
        if (reason == ReleaseReason.PAID) {
            return CompletableFuture.completedFuture(PrisonResult.failure(
                    PrisonOutcome.ARGUMENT_INVALID,
                    "PAID releases go through payRelease — the fee protocol"));
        }
        if (reason == ReleaseReason.ADMIN_OVERRIDE && actor == null) {
            return CompletableFuture.completedFuture(PrisonResult.failure(
                    PrisonOutcome.ADMIN_ACTOR_REQUIRED,
                    "an admin override must record the acting staff member"));
        }
        String detail = note == null || note.isBlank()
                ? "released via " + reason
                : note.trim();
        return db.<PrisonResult<Sentence>>transaction(tx -> {
            long now = clock.getAsLong();
            Optional<Sentence> live = PrisonStore.liveSentence(tx, player);
            if (live.isEmpty()) {
                throw new Refusal(PrisonOutcome.NO_SENTENCE,
                        "no live sentence for " + player);
            }
            Sentence row = live.get();
            if (PrisonStore.releaseLive(tx, row.sentenceId(), reason, now) == 0) {
                return PrisonResult.unchanged(PrisonOutcome.RELEASE_NOOP,
                        "sentence changed state concurrently",
                        PrisonStore.sentenceById(tx, row.sentenceId()).orElse(row));
            }
            voidAnyQuotedFee(tx, row.sentenceId(), player, now);
            PrisonStore.insertEvent(tx, "sentence:" + row.sentenceId() + ":released",
                    player, "SENTENCE",
                    "reason=" + reason + " actor=" + actor + " note=" + detail,
                    reason.name(), now);
            return PrisonResult.changed(PrisonOutcome.RELEASED, detail,
                    PrisonStore.sentenceById(tx, row.sentenceId()).orElseThrow());
        }).exceptionally(e -> toFailure(e));
    }

    /** Voids the frozen quote of a sentence released by another route. */
    private void voidAnyQuotedFee(TransactionContext tx, UUID sentenceId,
            UUID player, long now) throws java.sql.SQLException {
        Optional<FeeRecord> fee = PrisonStore.feeBySentence(tx, sentenceId);
        if (fee.isPresent() && fee.get().state() == FeeState.QUOTED) {
            PrisonStore.markFeeVoid(tx, fee.get().feeId(), now);
            PrisonStore.insertEvent(tx, "fee:" + fee.get().feeId() + ":void",
                    player, "FEE", "sentence released by another route", "VOID", now);
        }
    }

    // ------------------------------------------------------------------
    // Release fee
    // ------------------------------------------------------------------

    /**
     * Pure display quote from the player's current death score — the
     * frozen row written by {@link #payRelease} is the authoritative one.
     */
    public CompletableFuture<FeeQuote> quoteFor(@NotNull UUID player) {
        return db.transaction(tx -> FeeMath.quote(
                PrisonStore.profile(tx, player).orElseGet(
                        () -> PlayerProfile.zero(player, clock.getAsLong())).deathScore(),
                config.release()));
    }

    /**
     * The fee claim protocol. Blocking orchestration over three stores in
     * time — see the class javadoc for the crash-window reconciliation
     * story; the caller owns thread discipline (off the main thread, not a
     * persistence-executor callback).
     *
     * @param provider the shared economy provider (never null)
     */
    public PrisonResult<FeeRecord> payRelease(@NotNull UUID player,
            @NotNull EconomyProvider provider) {
        if (provider == null) {
            return PrisonResult.failure(PrisonOutcome.ARGUMENT_INVALID,
                    "an economy provider is required");
        }

        // (1) The sentence must be a confirmed imprisonment.
        Sentence live;
        try {
            live = liveSentence(player).join();
        } catch (RuntimeException e) {
            return toFailure(e);
        }
        if (live == null) {
            return PrisonResult.failure(PrisonOutcome.NO_SENTENCE,
                    "no live sentence for " + player);
        }
        if (live.state() != SentenceState.JAILED) {
            return PrisonResult.refused(PrisonOutcome.NOT_JAILED,
                    "the pending sentence must be confirmed jailed first", null);
        }

        // (2) Find-or-freeze the QUOTED fee row (the immutable §5.9 audit).
        FeeRecord fee;
        try {
            fee = db.<FeeRecord>transaction(tx -> {
                Optional<FeeRecord> existing = PrisonStore.feeBySentence(tx,
                        live.sentenceId());
                if (existing.isPresent()) {
                    return existing.get();
                }
                long now = clock.getAsLong();
                long score = PrisonStore.profile(tx, player)
                        .orElseThrow().deathScore();
                FeeQuote quote = FeeMath.quote(score, config.release());
                UUID feeId = UUID.randomUUID();
                PrisonStore.insertFee(tx, feeId, live.sentenceId(), player, quote, now);
                PrisonStore.insertEvent(tx, "fee:" + feeId + ":quoted", player,
                        "FEE", "score=" + score + " factor=" + quote.inflationFactor()
                                + " x" + quote.multiplier() + " round=" + quote.rounding()
                                + " amount=" + quote.amountMinor() + " clamp="
                                + quote.clamp(), "QUOTED", now);
                return PrisonStore.feeBySentence(tx, live.sentenceId()).orElseThrow();
            }).join();
        } catch (RuntimeException e) {
            return toFailure(e);
        }
        if (fee.state() == FeeState.PAID) {
            return PrisonResult.unchanged(PrisonOutcome.PAID_NOOP,
                    "this sentence was already paid out", fee);
        }
        // A VOID fee coexisting with a live sentence is impossible (void
        // happens inside the same release transaction), but stay defensive:
        // refuse rather than resurrect a dead quote.
        if (fee.state() == FeeState.VOID) {
            return PrisonResult.failure(PrisonOutcome.NOT_IN_STATE,
                    "the sentence's fee was voided");
        }

        // (3) The audited provider debit under the fee's idempotency key.
        EconomyResult charge = provider.debit(player, fee.amountMinor(),
                "prison-fee:" + fee.feeId(), "prison.release-fee");
        if (!charge.success()) {
            PrisonOutcome outcome = charge.outcome() == EconomyOutcome.INSUFFICIENT_FUNDS
                    ? PrisonOutcome.INSUFFICIENT_FUNDS
                    : PrisonOutcome.PROVIDER_REFUSED;
            return PrisonResult.refused(outcome,
                    "provider " + provider.providerId() + " refused: "
                            + charge.outcome(), fee);
        }

        // (4) All-or-none commit: fee PAID + sentence RELEASED(PAID).
        try {
            return db.<PrisonResult<FeeRecord>>transaction(tx -> {
                long now = clock.getAsLong();
                if (PrisonStore.markFeePaid(tx, fee.feeId(), provider.providerId(),
                        charge.entryId(), now) == 0) {
                    FeeRecord current = PrisonStore.feeBySentence(tx,
                            live.sentenceId()).orElse(fee);
                    if (current.state() == FeeState.PAID) {
                        return PrisonResult.unchanged(PrisonOutcome.PAID_NOOP,
                                "concurrently paid out", current);
                    }
                    throw new Refusal(PrisonOutcome.NOT_IN_STATE,
                            "the fee row changed state concurrently");
                }
                if (PrisonStore.releaseLive(tx, live.sentenceId(),
                        ReleaseReason.PAID, now) == 0) {
                    throw new Refusal(PrisonOutcome.RELEASE_NOOP,
                            "the sentence changed state concurrently");
                }
                PrisonStore.insertEvent(tx, "fee:" + fee.feeId() + ":paid", player,
                        "FEE", "provider=" + provider.providerId() + " entry="
                                + charge.entryId() + " amount=" + fee.amountMinor(),
                        "PAID", now);
                PrisonStore.insertEvent(tx,
                        "sentence:" + live.sentenceId() + ":released", player,
                        "SENTENCE", "paid release, entry=" + charge.entryId(),
                        "PAID", now);
                return PrisonResult.changed(PrisonOutcome.PAID_RELEASED,
                        PrisonStore.feeBySentence(tx, live.sentenceId()).orElseThrow());
            }).join();
        } catch (RuntimeException e) {
            return toFailure(e);
        }
    }

    // ------------------------------------------------------------------
    // Rehabilitation
    // ------------------------------------------------------------------

    /**
     * Records one idempotent rehabilitation completion and applies its
     * healing, clamped to the scar ledger (§5.8: "no inventory/database
     * race that grants double healing").
     *
     * @param taskKey a configured rehab task key
     * @param completionKey the controller's deterministic identity of the
     *        completed objective (grammar-checked, namespaced by player)
     */
    public CompletableFuture<PrisonResult<PlayerProfile>> completeRehab(
            @NotNull UUID player, @NotNull String taskKey,
            @NotNull String completionKey) {
        RehabTask task = config.rehab().get(taskKey);
        if (task == null) {
            return CompletableFuture.completedFuture(PrisonResult.failure(
                    PrisonOutcome.REHAB_UNKNOWN_TASK,
                    "no rehab task '" + taskKey + "'"));
        }
        if (!task.enabled()) {
            return CompletableFuture.completedFuture(PrisonResult.failure(
                    PrisonOutcome.REHAB_DISABLED,
                    "rehab task '" + taskKey + "' is disabled"));
        }
        if (completionKey == null
                || !PrisonConfig.EVENT_KEY.matcher(completionKey).matches()) {
            return CompletableFuture.completedFuture(PrisonResult.failure(
                    PrisonOutcome.ARGUMENT_INVALID,
                    "completion key must match [a-z0-9_:\\-]{1,96} (got '"
                            + completionKey + "')"));
        }
        return db.<PrisonResult<PlayerProfile>>transaction(tx -> {
            long now = clock.getAsLong();
            String eventKey = "rehab:" + player + ":" + taskKey + ":"
                    + completionKey;

            // Idempotency gate before any mutation.
            if (PrisonStore.insertEvent(tx, eventKey, player, "REHAB",
                    "task=" + taskKey + " completion=" + completionKey, null, now)
                    == 0) {
                PlayerProfile live = PrisonStore.profile(tx, player)
                        .orElseGet(() -> PlayerProfile.zero(player, now));
                return PrisonResult.unchanged(PrisonOutcome.REHAB_NOOP,
                        "completion '" + completionKey + "' already recorded", live);
            }

            int done = PrisonStore.occurrencesOf(tx, player, taskKey);
            if (done >= task.maxOccurrences()) {
                throw new Refusal(PrisonOutcome.REHAB_EXHAUSTED,
                        "task '" + taskKey + "' has used all " + task.maxOccurrences()
                                + " occurrences");
            }
            PlayerProfile profile = PrisonStore.profile(tx, player)
                    .orElseGet(() -> PlayerProfile.zero(player, now));
            int remaining = profile.remainingScars();
            if (remaining <= 0) {
                throw new Refusal(PrisonOutcome.SCARS_FULLY_RESTORED,
                        "no scar points left to restore");
            }
            int healed = Math.min(task.healPoints(), remaining);
            PrisonStore.insertRehab(tx, player, taskKey, completionKey, done + 1,
                    healed, now);
            PrisonStore.restorePoints(tx, player, healed, now);
            PlayerProfile after = PrisonStore.profile(tx, player).orElseThrow();
            PrisonStore.updateEventOutcome(tx, eventKey, "COMPLETED",
                    "task=" + taskKey + " healed=" + healed + " restored="
                            + after.restoredPoints() + "/" + after.scarPoints());
            return PrisonResult.changed(PrisonOutcome.REHAB_COMPLETED,
                    "healed " + healed + " point(s)", after);
        }).exceptionally(e -> toFailure(e));
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** @return the player's profile (a zero profile for unknown players). */
    public CompletableFuture<PlayerProfile> profile(@NotNull UUID player) {
        return db.transaction(tx -> PrisonStore.profile(tx, player)
                .orElseGet(() -> PlayerProfile.zero(player, clock.getAsLong())));
    }

    /** @return the player's live sentence, when one exists. */
    public CompletableFuture<@Nullable Sentence> liveSentence(@NotNull UUID player) {
        return db.transaction(tx -> PrisonStore.liveSentence(tx, player).orElse(null));
    }

    /** @return every live PENDING sentence (the respawn/join retry sweep). */
    public CompletableFuture<List<Sentence>> pendingSentences() {
        return db.transaction(PrisonStore::allPending);
    }

    /** @return the recent processed deaths for a player, newest first. */
    public CompletableFuture<List<DeathRow>> deaths(@NotNull UUID player, int limit) {
        return db.transaction(tx -> tx.list(
                "SELECT death_key, player_uuid, score_delta, health_penalty,"
                + " created_utc FROM rb_prison_deaths WHERE player_uuid = ?"
                + " ORDER BY created_utc DESC LIMIT ?",
                rs -> new DeathRow(rs.getString(1), UUID.fromString(rs.getString(2)),
                        rs.getLong(3), rs.getInt(4), rs.getLong(5)),
                player, Math.max(1, Math.min(limit, 100))));
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Typed refusal thrown inside a transaction: rolls it back. */
    private static final class Refusal extends RuntimeException {
        final PrisonOutcome outcome;
        final String detail;

        Refusal(PrisonOutcome outcome, String detail) {
            super(detail, null, false, false);
            this.outcome = outcome;
            this.detail = detail;
        }
    }

    /** Unwraps a failed transaction into a typed failure result. */
    private static <T> PrisonResult<T> toFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException c && c.getCause() != null
                ? c.getCause() : error;
        if (cause instanceof Refusal refusal) {
            return PrisonResult.failure(refusal.outcome, refusal.detail);
        }
        return PrisonResult.failure(PrisonOutcome.DB_ERROR, String.valueOf(
                cause.getMessage() == null ? cause.getClass().getSimpleName()
                        : cause.getMessage()));
    }
}
