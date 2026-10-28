package net.zaminmc.rebellion.dragons.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import net.zaminmc.rebellion.core.config.CoreConfig;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.db.DatabaseService;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import net.zaminmc.rebellion.dragons.DragonsConfig;
import net.zaminmc.rebellion.dragons.Lineage;
import net.zaminmc.rebellion.dragons.LineageOutcome;
import net.zaminmc.rebellion.dragons.LineageResult;
import net.zaminmc.rebellion.dragons.LineageService;
import net.zaminmc.rebellion.dragons.LineageState;
import net.zaminmc.rebellion.dragons.db.LineageStore.AuditRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LineageService against a real SQLite database through Core's actual
 * DatabaseService — the ADR-0009 evidence set. Every rare-asset invariant
 * from master prompt §5.6 is exercised for real: gateway-issuance
 * idempotency, cap enforcement under concurrency, the hard policy ceiling,
 * hatch races on cloned/stale eggs, idempotent death processing (one egg,
 * never two), recovery claim/route, audited terminal retirement, and the
 * full restart round-trip. No mocks.
 */
final class LineageServiceIT {

    @TempDir
    Path tmp;

    private DatabaseService db;
    private LineageService service;

    /** Mutable service clock: cooldown tests advance it deterministically. */
    private final java.util.concurrent.atomic.AtomicLong clock =
            new java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L);

    private final UUID victor = UUID.randomUUID();
    private final UUID killer = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    /** Small cap for fast cap tests; separate tests build their own config. */
    private DragonsConfig config(int cap) {
        return DragonsConfig.defaults(cap);
    }

    @BeforeEach
    void boot() throws Exception {
        db = newService();
        service = new LineageService(db, config(3), clock::get);
        await(service.loadAll());
    }

    @AfterEach
    void shutdown() {
        db.shutdown();
    }

    private DatabaseService newService() throws Exception {
        DatabaseService fresh = new DatabaseService(sqliteConfig(), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics());
        await(fresh.bootstrap());
        await(fresh.applyModuleMigrations("dragons",
                List.of(DragonsSchema.v1(), DragonsSchema.v2())));
        return fresh;
    }

    private DiagnosticsService diagnostics() {
        CoreConfig config = new CoreConfig(1, false, 30, "diagnostics", 5, 50);
        return new DiagnosticsService(config, tmp, java.util.logging.Logger.getLogger("test"));
    }

    private DatabaseConfig sqliteConfig() {
        return new DatabaseConfig(
                DatabaseConfig.Backend.SQLITE, "rebellion.sqlite",
                "localhost", 3306, "rebellion", "user", "",
                Map.of(), 10, 2, 5_000, 1_800_000, 2, 2000, true);
    }

    private static void await(CompletableFuture<?> future) {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future failed: " + e, e);
        }
    }

    private <T> T run(CompletableFuture<T> future) {
        await(future);
        return future.join();
    }

    /** Distinct gateway anchor per index (the vanilla ring positions). */
    private CompletableFuture<LineageResult> issueAt(int index, UUID to) {
        return service.issueFromGateway("world_the_end", 96 + index * 16, 64, 0, to);
    }

    private Lineage issueOk(int index, UUID to) {
        LineageResult result = run(issueAt(index, to));
        assertTrue(result.success(), "issuance " + index + " failed: " + result.detail());
        assertEquals(LineageOutcome.ISSUED, result.outcome());
        return result.lineage();
    }

    /** Hatches a held egg with a fresh entity; returns the HATCHED lineage. */
    private Lineage hatchOk(Lineage held, UUID owner) {
        UUID entity = UUID.randomUUID();
        LineageResult result = run(service.hatch(held.lineageId(), held.eggInstance(),
                owner, entity, 200.0));
        assertTrue(result.success(), "hatch failed: " + result.detail());
        assertEquals(LineageOutcome.HATCHED, result.outcome());
        return result.lineage();
    }

    // ------------------------------------------------------------------
    // Issuance: the only mint path
    // ------------------------------------------------------------------

    @Test
    void issuanceMintsSequentialSlotsRoutedToTheVictor() {
        Lineage first = issueOk(0, victor);
        Lineage second = issueOk(1, victor);

        assertEquals(1, first.slot());
        assertEquals(2, second.slot());
        assertEquals(LineageState.EGG_HELD, first.state());
        assertEquals(Optional.of(victor), first.ownerOpt());
        assertEquals(first.gateway(),
                Lineage.GatewayKey.of("world_the_end", 96, 64, 0));
        assertNotEquals(first.eggInstance(), second.eggInstance());
        assertEquals(2, service.count());

        // Mirror lookups work and the audit trail records the mints.
        assertEquals(Optional.of(first), service.byId(first.lineageId()));
        assertEquals(Optional.of(first), service.byGateway("world_the_end", 96, 64, 0));
        List<AuditRow> audit = run(service.auditTrail(first.lineageId()));
        assertEquals(1, audit.size());
        assertEquals("ISSUE", audit.get(0).action());
        assertEquals(victor, audit.get(0).actor());
    }

    @Test
    void argumentValidationFailsBeforeAnyDatabaseAccess() {
        assertEquals(LineageOutcome.ARGUMENT_INVALID,
                run(service.issueFromGateway("", 0, 64, 0, victor)).outcome());
        assertEquals(LineageOutcome.ARGUMENT_INVALID,
                run(service.issueFromGateway("w".repeat(65), 0, 64, 0, victor)).outcome());
        assertEquals(LineageOutcome.ARGUMENT_INVALID,
                run(service.issueFromGateway("ok", 0, 64, 0, null)).outcome());
        UUID id = UUID.randomUUID();
        assertEquals(LineageOutcome.ARGUMENT_INVALID,
                run(service.hatch(null, UUID.randomUUID(), victor,
                        UUID.randomUUID(), 200.0)).outcome());
        assertEquals(LineageOutcome.ARGUMENT_INVALID,
                run(service.hatch(id, UUID.randomUUID(), victor,
                        UUID.randomUUID(), 0.0)).outcome());
        assertEquals(0, service.count());
    }

    @Test
    void sameGatewayNeverMintsTwiceEvenForADifferentVictor() {
        Lineage first = issueOk(0, victor);

        // Sequential duplicate (event retry / re-observation).
        LineageResult retry = run(issueAt(0, victor));
        assertTrue(retry.success());
        assertEquals(LineageOutcome.ISSUED_ALREADY, retry.outcome());
        assertEquals(first.lineageId(), retry.lineage().lineageId());

        // A second victor at the same gateway must not receive the egg.
        LineageResult other = run(issueAt(0, stranger));
        assertEquals(LineageOutcome.ISSUED_ALREADY, other.outcome());
        assertEquals(Optional.of(victor), other.lineage().ownerOpt());
        assertEquals(1, service.count());
    }

    @Test
    void concurrentSameGatewayIssuanceResolvesToOneLineage() {
        CompletableFuture<LineageResult> a = issueAt(0, victor);
        CompletableFuture<LineageResult> b = issueAt(0, stranger);
        CompletableFuture.allOf(a, b).join();

        List<LineageResult> results = List.of(a.join(), b.join());
        long minted = results.stream()
                .filter(r -> r.outcome() == LineageOutcome.ISSUED).count();
        long already = results.stream()
                .filter(r -> r.outcome() == LineageOutcome.ISSUED_ALREADY).count();
        assertEquals(1, minted, "UNIQUE gateway must settle the mint race");
        assertEquals(1, already);
        assertEquals(1, service.count());
    }

    @Test
    void capHoldsUnderConcurrentDistinctGateways() {
        // Cap 3, five distinct gateways race: exactly three mint.
        List<CompletableFuture<LineageResult>> races = IntStream.range(0, 5)
                .mapToObj(i -> issueAt(i, victor))
                .toList();
        CompletableFuture.allOf(races.toArray(CompletableFuture[]::new)).join();

        long minted = races.stream().filter(f -> f.join().outcome() == LineageOutcome.ISSUED).count();
        long capped = races.stream().filter(f -> f.join().outcome() == LineageOutcome.CAP_REACHED).count();
        assertEquals(3, minted);
        assertEquals(2, capped);
        assertEquals(3, service.count());

        // Sequential attempts at the cap also refuse.
        assertEquals(LineageOutcome.CAP_REACHED, run(issueAt(100, victor)).outcome());
        assertEquals(3, service.count());
    }

    @Test
    void capCannotSilentlyExceedTheHardPolicyCeiling() {
        // Directly constructed config bypassing loader validation: cap 30.
        LineageService oversized = new LineageService(db, config(30),
                () -> 1_700_000_000_000L);
        await(oversized.loadAll());
        assertEquals(20, oversized.cap(), "runtime clamp must honor the policy ceiling");

        // Fill all 20 slots through distinct gateways.
        for (int i = 0; i < 20; i++) {
            LineageResult result = run(oversized.issueFromGateway("end", i, 64, 0, victor));
            assertEquals(LineageOutcome.ISSUED, result.outcome(), "issuance " + i);
        }
        assertEquals(20, oversized.count());
        // The 21st lineage can never exist, regardless of the config value.
        assertEquals(LineageOutcome.CAP_REACHED,
                run(oversized.issueFromGateway("end", 999, 64, 0, victor)).outcome());
        assertEquals(20, oversized.count());
    }

    // ------------------------------------------------------------------
    // Hatch: compare-and-set on the live egg instance
    // ------------------------------------------------------------------

    @Test
    void hatchBindsTheFreshEntityAndHealthSnapshot() {
        Lineage held = issueOk(0, victor);
        UUID entity = UUID.randomUUID();

        LineageResult result = run(service.hatch(held.lineageId(), held.eggInstance(),
                victor, entity, 200.0));
        assertEquals(LineageOutcome.HATCHED, result.outcome());
        Lineage hatched = result.lineage();
        assertEquals(LineageState.HATCHED, hatched.state());
        assertEquals(Optional.of(entity), hatched.entityOpt());
        assertEquals(200.0, hatched.health());

        assertEquals(Optional.of(hatched), service.byEntity(entity));
        assertEquals(Optional.empty(), service.byEggInstance(hatched.eggInstance()),
                "a hatched egg instance must no longer resolve as a hatching egg");
        assertEquals(1, service.count(), "hatching must never mint a lineage");
    }

    @Test
    void concurrentHatchClicksProduceExactlyOneDragon() {
        Lineage held = issueOk(0, victor);
        UUID egg = held.eggInstance();

        CompletableFuture<LineageResult> a =
                service.hatch(held.lineageId(), egg, victor, UUID.randomUUID(), 200.0);
        CompletableFuture<LineageResult> b =
                service.hatch(held.lineageId(), egg, victor, UUID.randomUUID(), 200.0);
        CompletableFuture.allOf(a, b).join();

        long hatched = List.of(a.join(), b.join()).stream()
                .filter(r -> r.outcome() == LineageOutcome.HATCHED).count();
        long conflicts = List.of(a.join(), b.join()).stream()
                .filter(r -> r.outcome() == LineageOutcome.HATCH_CONFLICT).count();
        assertEquals(1, hatched, "the CAS must settle concurrent clicks");
        assertEquals(1, conflicts);
        assertEquals(1, service.count());
    }

    @Test
    void clonedAndStaleEggsAlwaysLose() {
        Lineage held = issueOk(0, victor);
        UUID egg = held.eggInstance();
        UUID staleInstance = egg;

        // A clone hatches first (duplication glitch, same stamp).
        LineageResult cloneWin = run(service.hatch(held.lineageId(), egg,
                stranger, UUID.randomUUID(), 200.0));
        assertEquals(LineageOutcome.HATCHED, cloneWin.outcome());

        // The original holder's copy now conflicts (already hatched).
        LineageResult late = run(service.hatch(held.lineageId(), egg,
                victor, UUID.randomUUID(), 200.0));
        assertEquals(LineageOutcome.HATCH_CONFLICT, late.outcome());

        // Death cycle re-issues a fresh instance; the OLD stamp is stale.
        LineageResult death = run(service.processDeath(held.lineageId(),
                cloneWin.lineage().entityUuid(), Optional.of(killer)));
        assertEquals(LineageOutcome.EGG_RETURNED, death.outcome());
        UUID reissued = death.lineage().eggInstance();
        assertNotEquals(staleInstance, reissued);

        LineageResult staleHatch = run(service.hatch(held.lineageId(), staleInstance,
                killer, UUID.randomUUID(), 200.0));
        assertEquals(LineageOutcome.HATCH_CONFLICT, staleHatch.outcome());
        assertEquals(1, service.count(), "no path here may mint a lineage");
    }

    @Test
    void recoveryEggsCannotBeHatchedDirectly() {
        Lineage held = issueOk(0, victor);
        run(service.markEggUndeliverable(held.lineageId(), "inventory full"));

        // Even the live instance cannot hatch out of the mailbox.
        LineageResult direct = run(service.hatch(held.lineageId(), held.eggInstance(),
                victor, UUID.randomUUID(), 200.0));
        assertEquals(LineageOutcome.INVALID_STATE, direct.outcome());
        assertEquals(1, service.count());
    }

    // ------------------------------------------------------------------
    // Death processing: idempotent by construction
    // ------------------------------------------------------------------

    @Test
    void dragonDeathHandsTheEggToTheValidKiller() {
        Lineage held = issueOk(0, victor);
        Lineage hatched = hatchOk(held, victor);
        UUID oldInstance = hatched.eggInstance();

        LineageResult death = run(service.processDeath(hatched.lineageId(),
                hatched.entityUuid(), Optional.of(killer)));
        assertEquals(LineageOutcome.EGG_RETURNED, death.outcome());
        Lineage egg = death.lineage();
        assertEquals(LineageState.EGG_HELD, egg.state());
        assertEquals(Optional.of(killer), egg.ownerOpt());
        assertEquals(Optional.empty(), egg.entityOpt());
        assertNull(egg.health());
        assertNotEquals(oldInstance, egg.eggInstance(), "re-issue must mint a fresh stamp");
        assertEquals(Optional.empty(), service.byEntity(hatched.entityUuid()));
        assertEquals(Optional.empty(), service.byEggInstance(oldInstance),
                "stale instances must stop resolving");
        assertEquals(1, service.count(), "death is a transfer, never a mint");
    }

    @Test
    void simultaneousOwnerAndDragonDeathProduceExactlyOneEgg() {
        Lineage held = issueOk(0, victor);
        Lineage hatched = hatchOk(held, victor);
        UUID entity = hatched.entityUuid();

        // Both death paths (owner-death handler and dragon-death handler)
        // race with the same entity binding.
        CompletableFuture<LineageResult> ownerPath =
                service.processDeath(hatched.lineageId(), entity, Optional.of(killer));
        CompletableFuture<LineageResult> dragonPath =
                service.processDeath(hatched.lineageId(), entity, Optional.of(killer));
        CompletableFuture.allOf(ownerPath, dragonPath).join();

        List<LineageResult> results = List.of(ownerPath.join(), dragonPath.join());
        long returned = results.stream()
                .filter(r -> r.outcome() == LineageOutcome.EGG_RETURNED).count();
        long noops = results.stream()
                .filter(r -> r.outcome() == LineageOutcome.EGG_RETURN_NOOP).count();
        assertEquals(1, returned, "the entity CAS must settle the double delivery");
        assertEquals(1, noops);
        assertTrue(results.stream().allMatch(LineageResult::success));
        assertEquals(1, service.count(), "one lineage in, one lineage out");

        // The audit trail records exactly ONE death return.
        List<AuditRow> audit = run(service.auditTrail(hatched.lineageId()));
        assertEquals(1, audit.stream().filter(a -> a.action().equals("DEATH_RETURN")).count());
    }

    @Test
    void staleAndDuplicateDeathEventsAreNoOps() {
        Lineage held = issueOk(0, victor);
        Lineage hatched = hatchOk(held, victor);
        UUID entity = hatched.entityUuid();

        assertEquals(LineageOutcome.EGG_RETURNED,
                run(service.processDeath(hatched.lineageId(), entity, Optional.of(killer))).outcome());

        // Duplicate delivery of the same entity after processing.
        LineageResult dup = run(service.processDeath(hatched.lineageId(), entity,
                Optional.of(killer)));
        assertTrue(dup.success());
        assertEquals(LineageOutcome.EGG_RETURN_NOOP, dup.outcome());
        assertEquals(Optional.of(killer), dup.lineage().ownerOpt(),
                "the no-op must reflect the settled state, not re-deliver");

        // A fully unknown lineage/entity pair.
        assertEquals(LineageOutcome.NOT_FOUND,
                run(service.processDeath(UUID.randomUUID(), entity, Optional.of(killer))).outcome());
        assertEquals(1, service.count());
    }

    @Test
    void deathWithoutValidKillerParksInRecoveryWithOwnerAsClaimant() {
        Lineage held = issueOk(0, victor);
        Lineage hatched = hatchOk(held, victor);

        LineageResult death = run(service.processDeath(hatched.lineageId(),
                hatched.entityUuid(), Optional.empty()));
        assertEquals(LineageOutcome.EGG_RETURNED, death.outcome());
        assertEquals(LineageState.EGG_RECOVERY, death.lineage().state());
        assertEquals(Optional.of(victor), death.lineage().ownerOpt(),
                "the last owner is the recorded claimant");

        // A stranger cannot claim; the refusal must not reveal the claimant.
        LineageResult wrongClaim = run(service.claimRecoveredEgg(hatched.lineageId(), stranger));
        assertFalse(wrongClaim.success());
        assertEquals(LineageOutcome.INVALID_STATE, wrongClaim.outcome());
        assertTrue(wrongClaim.detail() == null
                || !wrongClaim.detail().contains(victor.toString()));

        // The recorded claimant re-issues with a fresh instance.
        LineageResult claim = run(service.claimRecoveredEgg(hatched.lineageId(), victor));
        assertEquals(LineageOutcome.CLAIMED, claim.outcome());
        assertEquals(LineageState.EGG_HELD, claim.lineage().state());
        assertNotEquals(death.lineage().eggInstance(), claim.lineage().eggInstance());
    }

    // ------------------------------------------------------------------
    // Recovery parking / claim / admin route
    // ------------------------------------------------------------------

    @Test
    void undeliverableEggsParkIdempotently() {
        Lineage held = issueOk(0, victor);

        LineageResult park = run(service.markEggUndeliverable(held.lineageId(),
                "victor offline"));
        assertEquals(LineageOutcome.RECOVERY_ENTERED, park.outcome());
        assertEquals(LineageState.EGG_RECOVERY, park.lineage().state());
        assertEquals(held.eggInstance(), park.lineage().eggInstance(),
                "parking keeps the instance; claim/route re-issue");

        // Delivery-retry loops can call park again without wedging.
        LineageResult repark = run(service.markEggUndeliverable(held.lineageId(), "again"));
        assertTrue(repark.success());
        assertEquals(LineageOutcome.RECOVERY_ENTERED, repark.outcome());
        assertFalse(repark.mutated());

        // A hatched lineage is not parkable.
        Lineage other = issueOk(1, victor);
        Lineage hatched = hatchOk(other, victor);
        assertEquals(LineageOutcome.INVALID_STATE,
                run(service.markEggUndeliverable(hatched.lineageId(), null)).outcome());
    }

    @Test
    void adminRouteReIssuesHeldAndRecoveryEggsToAnyPlayer() {
        Lineage held = issueOk(0, victor);
        UUID oldInstance = held.eggInstance();

        LineageResult routed = run(service.adminRouteEgg(held.lineageId(), admin, stranger));
        assertEquals(LineageOutcome.ROUTED, routed.outcome());
        assertEquals(Optional.of(stranger), routed.lineage().ownerOpt());
        assertNotEquals(oldInstance, routed.lineage().eggInstance());

        // Routing also works from recovery (admin recovery path).
        run(service.markEggUndeliverable(held.lineageId(), "lost"));
        LineageResult rerouted = run(service.adminRouteEgg(held.lineageId(), admin, victor));
        assertEquals(LineageOutcome.ROUTED, rerouted.outcome());
        assertEquals(LineageState.EGG_HELD, rerouted.lineage().state());

        // But never a living dragon.
        Lineage other = issueOk(1, victor);
        Lineage hatched = hatchOk(other, victor);
        assertEquals(LineageOutcome.INVALID_STATE,
                run(service.adminRouteEgg(hatched.lineageId(), admin, stranger)).outcome());

        // Every route is audited with the acting admin (the trail also holds
        // the ISSUE and PARK rows, which carry other actors by design).
        List<AuditRow> audit = run(service.auditTrail(held.lineageId()));
        List<AuditRow> routes = audit.stream()
                .filter(a -> a.action().equals("ADMIN_ROUTE")).toList();
        assertEquals(2, routes.size());
        assertTrue(routes.stream().allMatch(a -> admin.equals(a.actor())));
    }

    // ------------------------------------------------------------------
    // Retirement: audited, terminal, slot stays consumed
    // ------------------------------------------------------------------

    @Test
    void retirementIsAuditedTerminalAndKeepsTheSlotConsumed() {
        Lineage held = issueOk(0, victor);

        LineageResult retired = run(service.retire(held.lineageId(), admin, "testing"));
        assertEquals(LineageOutcome.RETIRED, retired.outcome());
        assertEquals(LineageState.RETIRED, retired.lineage().state());
        assertTrue(retired.lineage().ownerOpt().isEmpty());
        assertEquals(held.eggInstance(), retired.lineage().eggInstance(),
                "the last instance is kept for forensics");

        // Terminal: nothing works anymore.
        assertEquals(LineageOutcome.INVALID_STATE,
                run(service.retire(held.lineageId(), admin, "again")).outcome());
        assertEquals(LineageOutcome.INVALID_STATE,
                run(service.hatch(held.lineageId(), held.eggInstance(), victor,
                        UUID.randomUUID(), 200.0)).outcome());
        assertEquals(LineageOutcome.INVALID_STATE,
                run(service.claimRecoveredEgg(held.lineageId(), victor)).outcome());
        assertEquals(LineageOutcome.INVALID_STATE,
                run(service.adminRouteEgg(held.lineageId(), admin, stranger)).outcome());
        LineageResult death = run(service.processDeath(held.lineageId(),
                UUID.randomUUID(), Optional.of(killer)));
        assertTrue(death.success());
        assertEquals(LineageOutcome.EGG_RETURN_NOOP, death.outcome());

        // The gateway and slot stay consumed: same gateway = ISSUED_ALREADY,
        // and retired rows still count against the cap (cap 3 here).
        assertEquals(LineageOutcome.ISSUED_ALREADY,
                run(issueAt(0, stranger)).outcome());
        issueOk(1, victor);
        issueOk(2, victor);
        assertEquals(LineageOutcome.CAP_REACHED,
                run(issueAt(3, victor)).outcome(),
                "retirement must not free a lineage slot");
        assertEquals(3, service.count());

        // Audit: one RETIRE row with the admin and the stated reason.
        List<AuditRow> audit = run(service.auditTrail(held.lineageId()));
        AuditRow retireRow = audit.stream()
                .filter(a -> a.action().equals("RETIRE")).findFirst().orElseThrow();
        assertEquals(admin, retireRow.actor());
        assertTrue(retireRow.detail().contains("testing"));
        assertTrue(retireRow.detail().contains("EGG_HELD"));
    }

    // ------------------------------------------------------------------
    // Restart round-trip
    // ------------------------------------------------------------------

    @Test
    void fullStateSurvivesARestartRoundTrip() throws Exception {
        // This test needs more than the default cap 3, so it drives its own
        // service instance at cap 20 over the same database.
        LineageService first = new LineageService(db, config(20),
                () -> 1_700_000_000_000L);
        await(first.loadAll());

        Lineage eggHeld = firstIssue(first, 0, victor);
        Lineage parked = firstIssue(first, 1, victor);
        Lineage hatched = firstIssue(first, 2, victor);
        UUID parkedId = parked.lineageId();
        run(first.markEggUndeliverable(parkedId, "offline"));
        LineageResult hatchedResult = run(first.hatch(hatched.lineageId(),
                hatched.eggInstance(), victor, UUID.randomUUID(), 200.0));
        Lineage living = hatchedResult.lineage();
        Lineage retired = firstIssue(first, 3, victor);
        run(first.retire(retired.lineageId(), admin, "round-trip"));

        db.shutdown();
        db = newService();
        LineageService restarted = new LineageService(db, config(20),
                () -> 1_700_000_000_000L);
        await(restarted.loadAll());

        assertEquals(4, restarted.count());
        assertEquals(List.of(1, 2, 3, 4),
                restarted.all().stream().map(Lineage::slot).toList());

        assertEquals(Optional.of(eggHeld), restarted.byId(eggHeld.lineageId()));
        assertEquals(LineageState.EGG_RECOVERY,
                restarted.byId(parkedId).orElseThrow().state());
        assertEquals(Optional.of(living), restarted.byEntity(living.entityUuid()));
        assertEquals(living.eggInstance(),
                restarted.byId(living.lineageId()).orElseThrow().eggInstance());
        assertEquals(LineageState.RETIRED,
                restarted.byId(retired.lineageId()).orElseThrow().state());

        // All live identity stamps resolve again after the restart.
        assertEquals(Optional.of(eggHeld), restarted.byEggInstance(eggHeld.eggInstance()));
        assertEquals(Optional.of(parkedId), restarted
                .byGateway("world_the_end", 112, 64, 0).map(Lineage::lineageId));

        // And the lifecycle continues seamlessly after the restart.
        LineageResult death = run(restarted.processDeath(living.lineageId(),
                living.entityUuid(), Optional.of(killer)));
        assertEquals(LineageOutcome.EGG_RETURNED, death.outcome());
        LineageResult claim = run(restarted.claimRecoveredEgg(parkedId, victor));
        assertEquals(LineageOutcome.CLAIMED, claim.outcome());

        List<AuditRow> audit = run(restarted.auditTrail(living.lineageId()));
        assertTrue(audit.stream().anyMatch(a -> a.action().equals("ISSUE")));
        assertTrue(audit.stream().anyMatch(a -> a.action().equals("HATCH")));
        assertTrue(audit.stream().anyMatch(a -> a.action().equals("DEATH_RETURN")));
    }

    // ------------------------------------------------------------------
    // Controller persistence (schema v2): pearl heal + snapshots
    // ------------------------------------------------------------------

    @Test
    void pearlHealIsCooldownGuardedAndIdempotent() {
        Lineage held = firstIssue(service, 0, victor);
        UUID entity = UUID.randomUUID();
        issueOkHatch(held, entity);

        // First heal inside the cooldown window of a fresh hatch: the stamp
        // is 0, so the CAS floor (now - 10s) admits it.
        LineageResult heal = run(service.pearlHeal(held.lineageId(), entity,
                180.0, 10_000L));
        assertEquals(LineageOutcome.PEARL_HEALED, heal.outcome());
        assertTrue(heal.success() && heal.mutated());
        assertEquals(180.0, heal.lineage().health());
        assertEquals(clock.get(), heal.lineage().lastPearlHealUtc(),
                "the cooldown stamp must advance to the heal time");

        // Event retry within the cooldown: refused BEFORE any pearl is
        // consumed by the caller contract (§5.6 idempotency).
        LineageResult retry = run(service.pearlHeal(held.lineageId(), entity,
                200.0, 10_000L));
        assertEquals(LineageOutcome.PEARL_COOLDOWN, retry.outcome());
        assertFalse(retry.mutated());
        assertEquals(180.0, service.byId(held.lineageId()).orElseThrow().health());

        // After the cooldown elapses, the next heal wins again.
        clock.addAndGet(10_001L);
        LineageResult later = run(service.pearlHeal(held.lineageId(), entity,
                200.0, 10_000L));
        assertEquals(LineageOutcome.PEARL_HEALED, later.outcome());
        assertEquals(200.0, later.lineage().health());

        // Stale entity binding loses (superseded dragon death).
        assertEquals(LineageOutcome.HATCH_CONFLICT,
                run(service.pearlHeal(held.lineageId(), UUID.randomUUID(),
                        150.0, 10_000L)).outcome());

        // Non-hatched lineage refuses with INVALID_STATE.
        Lineage egg = firstIssue(service, 1, victor);
        assertEquals(LineageOutcome.INVALID_STATE,
                run(service.pearlHeal(egg.lineageId(), UUID.randomUUID(),
                        150.0, 10_000L)).outcome());

        // The heal is audited once per committed mutation.
        List<AuditRow> audit = run(service.auditTrail(held.lineageId()));
        assertEquals(2, audit.stream().filter(a -> a.action().equals("PEARL_HEAL")).count());
    }

    @Test
    void persistSnapshotIsEntityPinnedAndIdempotentForStaleBindings() {
        Lineage held = firstIssue(service, 0, victor);
        UUID entity = UUID.randomUUID();
        issueOkHatch(held, entity);

        Lineage.PersistedLocation loc =
                new Lineage.PersistedLocation("world", 12.5, 96.0, -30.25);
        LineageResult saved = run(service.persistSnapshot(held.lineageId(), entity,
                175.5, loc));
        assertEquals(LineageOutcome.SNAPSHOT_SAVED, saved.outcome());
        Lineage mirrored = service.byId(held.lineageId()).orElseThrow();
        assertEquals(Optional.of(loc), mirrored.locationOpt());
        assertEquals(175.5, mirrored.health());
        assertEquals(clock.get(), mirrored.lastPersistUtc());

        // Snapshot for a superseded binding: benign typed no-op.
        LineageResult stale = run(service.persistSnapshot(held.lineageId(),
                UUID.randomUUID(), 100.0, loc));
        assertEquals(LineageOutcome.SNAPSHOT_STALE, stale.outcome());
        assertTrue(stale.success() && !stale.mutated());
        assertEquals(175.5, service.byId(held.lineageId()).orElseThrow().health());

        // After death the binding is gone; snapshots stay no-ops.
        run(service.processDeath(held.lineageId(), entity, Optional.of(killer)));
        LineageResult late = run(service.persistSnapshot(held.lineageId(), entity,
                90.0, loc));
        assertEquals(LineageOutcome.SNAPSHOT_STALE, late.outcome());
    }

    @Test
    void snapshotsAndPearlStampsSurviveARestartRoundTrip() throws Exception {
        Lineage held = firstIssue(service, 0, victor);
        UUID entity = UUID.randomUUID();
        issueOkHatch(held, entity);
        clock.addAndGet(5_000L);
        run(service.pearlHeal(held.lineageId(), entity, 190.0, 10_000L));
        clock.addAndGet(5_000L);
        Lineage.PersistedLocation loc =
                new Lineage.PersistedLocation("world_the_end", -12.5, 88.0, 44.75);
        run(service.persistSnapshot(held.lineageId(), entity, 188.0, loc));
        Lineage living = service.byId(held.lineageId()).orElseThrow();

        db.shutdown();
        db = newService();
        LineageService restarted = new LineageService(db, config(3), clock::get);
        await(restarted.loadAll());

        Lineage back = restarted.byId(held.lineageId()).orElseThrow();
        assertEquals(Optional.of(living.locationOpt().orElseThrow()), back.locationOpt());
        assertEquals(188.0, back.health());
        assertEquals(living.lastPearlHealUtc(), back.lastPearlHealUtc());
        assertEquals(living.lastPersistUtc(), back.lastPersistUtc());

        // The cooldown guard still holds after the restart: a heal retried
        // inside the window is still refused.
        assertEquals(LineageOutcome.PEARL_COOLDOWN,
                run(restarted.pearlHeal(held.lineageId(), entity, 200.0, 10_000L)).outcome());
    }

    /** Hatch helper that asserts the mutation committed. */
    private void issueOkHatch(Lineage held, UUID entity) {
        LineageResult result = run(service.hatch(held.lineageId(),
                held.eggInstance(), victor, entity, 200.0));
        assertEquals(LineageOutcome.HATCHED, result.outcome(), result.detail());
    }

    /** Issues through an arbitrary service instance (restart test helper). */
    private Lineage firstIssue(LineageService via, int index, UUID to) {
        LineageResult result = run(via.issueFromGateway("world_the_end",
                96 + index * 16, 64, 0, to));
        assertTrue(result.success(), "issuance " + index + " failed: " + result.detail());
        return result.lineage();
    }
}
