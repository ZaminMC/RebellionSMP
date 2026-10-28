package net.zaminmc.rebellion.alchemy.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.zaminmc.rebellion.alchemy.AlchemyClaim;
import net.zaminmc.rebellion.alchemy.AlchemyConfig;
import net.zaminmc.rebellion.alchemy.AlchemyOutcome;
import net.zaminmc.rebellion.alchemy.AlchemyResult;
import net.zaminmc.rebellion.alchemy.AlchemyService;
import net.zaminmc.rebellion.alchemy.AutoPolicy;
import net.zaminmc.rebellion.alchemy.AutoRule;
import net.zaminmc.rebellion.alchemy.AutoTrigger;
import net.zaminmc.rebellion.alchemy.BagDefinition;
import net.zaminmc.rebellion.alchemy.BrewingCurve;
import net.zaminmc.rebellion.alchemy.BrewProgress;
import net.zaminmc.rebellion.alchemy.ClaimPurpose;
import net.zaminmc.rebellion.alchemy.ClaimState;
import net.zaminmc.rebellion.alchemy.Leveling;
import net.zaminmc.rebellion.alchemy.StorageScope;
import net.zaminmc.rebellion.alchemy.UnlockDefinition;
import net.zaminmc.rebellion.alchemy.UnlockGrant;
import net.zaminmc.rebellion.alchemy.UnlockKind;
import net.zaminmc.rebellion.core.config.CoreConfig;
import net.zaminmc.rebellion.core.config.DatabaseConfig;
import net.zaminmc.rebellion.core.db.DatabaseService;
import net.zaminmc.rebellion.core.diagnostics.DiagnosticsService;
import org.bukkit.Material;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AlchemyService against a real SQLite database through Core's actual
 * DatabaseService — the ADR-0019 evidence set. Every §5.7 persistence
 * invariant is exercised for real: per-source idempotent XP with derived
 * level-ups and the clamp, the atomic accumulate path, the unlock purchase
 * claim protocol (level gate, prerequisite gate, refusals, refund release,
 * all-or-none commit, re-delivery no-ops, admin grants, the stale sweep),
 * the auto-rule lifecycle (policy gates, per-player cap, ownership-guarded
 * toggle/remove, idempotent defines), the auto-consume execution protocol
 * (cooldown authority, drink race guard, re-delivery, mid-flight removal)
 * and the restart round-trip. The clock is injected — deterministic.
 */
final class AlchemyServiceIT {

    @TempDir
    Path tmp;

    private DatabaseService db;
    private AlchemyService service;
    private AlchemyConfig config;

    /** Mutable service clock: cooldown/sweep tests advance it. */
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

    private final UUID player = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static AlchemyConfig config() {
        return AlchemyConfig.defaults();
    }

    /** Defaults with the policy replaced (cap tests). */
    private static AlchemyConfig config(AutoPolicy policy) {
        AlchemyConfig base = AlchemyConfig.defaults();
        return new AlchemyConfig(base.brewing(), base.unlocks(), base.bags(),
                policy);
    }

    /** Defaults with potent_swiftness chained behind frugal_brewing. */
    private static AlchemyConfig chainedConfig() {
        AlchemyConfig base = AlchemyConfig.defaults();
        Map<String, UnlockDefinition> unlocks = new HashMap<>(base.unlocks());
        unlocks.put("potent_swiftness", new UnlockDefinition("potent_swiftness",
                true, UnlockKind.POWER, 1, List.of("frugal_brewing"),
                List.of(new net.zaminmc.rebellion.alchemy.CostItem(
                        Material.AMETHYST_SHARD, 8)),
                java.util.Optional.of(PotionType.SWIFTNESS), 1, 1.5,
                0.0, 0, 0));
        return new AlchemyConfig(base.brewing(), unlocks, base.bags(),
                base.autoPolicy());
    }

    @BeforeEach
    void boot() throws Exception {
        db = newService();
        config = config();
        service = new AlchemyService(db, config, clock::get);
    }

    @AfterEach
    void shutdown() {
        db.shutdown();
    }

    private DatabaseService newService() throws Exception {
        DatabaseService fresh = new DatabaseService(sqliteConfig(), tmp,
                java.util.logging.Logger.getLogger("test"), diagnostics());
        await(fresh.bootstrap());
        await(fresh.applyModuleMigrations("alchemy", AlchemySchema.migrations()));
        return fresh;
    }

    private DiagnosticsService diagnostics() {
        CoreConfig core = new CoreConfig(1, false, 30, "diagnostics", 5, 50);
        return new DiagnosticsService(core, tmp,
                java.util.logging.Logger.getLogger("test"));
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

    private static <T> T awaitVal(CompletableFuture<T> future) {
        await(future);
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Awards XP until the player holds the given level (fresh sources). */
    private void awardToLevel(int level) {
        BrewingCurve curve = config.brewing();
        long needed = Leveling.totalXpForLevel(curve, level);
        AlchemyResult<BrewProgress> result = awaitVal(service.awardBrewXp(player,
                needed, "brew:boot:" + level));
        assertTrue(result.success(), result::toString);
    }

    /** Defines a HEALTH_BELOW HEALING rule with a 60 s cooldown. */
    private AutoRule defineHealingRule(UUID owner) {
        UUID ruleId = UUID.randomUUID();
        AlchemyResult<AutoRule> result = awaitVal(service.defineAutoRule(ruleId,
                owner, AutoTrigger.HEALTH_BELOW, PotionType.HEALING, 40, 60, 1,
                StorageScope.ANY, "test"));
        assertTrue(result.success(), result::toString);
        return result.payload();
    }

    // ------------------------------------------------------------------
    // Brewing XP
    // ------------------------------------------------------------------

    @Test
    void awardAccumulatesAndDerivesLevelUps() {
        AlchemyResult<BrewProgress> first = awaitVal(service.awardBrewXp(player,
                10, "brew:stand1:1"));
        assertEquals(AlchemyOutcome.XP_GAINED, first.outcome());
        assertTrue(first.mutated());
        assertEquals(1, first.payload().level());

        AlchemyResult<BrewProgress> second = awaitVal(service.awardBrewXp(player,
                100, "brew:stand1:2"));
        assertEquals(AlchemyOutcome.LEVEL_UP, second.outcome());
        assertEquals(2, second.payload().level());
        assertEquals("level 1->2", second.detail());

        BrewProgress progress = awaitVal(service.progress(player));
        assertEquals(110, progress.totalXp());
        assertEquals(2, progress.level());
        assertEquals(10, progress.xpIntoLevel());
        // Level 2 -> 3 costs 125 (base + one growth); 10 already sit inside,
        // so 115 remain.
        assertEquals(115, progress.nextLevelCost());
        assertFalse(progress.maxedOut());
    }

    @Test
    void duplicateSourceIsAnIdempotentNoop() {
        awaitVal(service.awardBrewXp(player, 10, "brew:stand1:1"));
        AlchemyResult<BrewProgress> replay = awaitVal(service.awardBrewXp(player,
                10, "brew:stand1:1"));
        assertEquals(AlchemyOutcome.XP_NOOP, replay.outcome());
        assertTrue(replay.success(), "an idempotent no-op is a success");
        assertFalse(replay.mutated());
        assertEquals(10, replay.payload().totalXp());

        // A different source still awards.
        AlchemyResult<BrewProgress> fresh = awaitVal(service.awardBrewXp(player,
                10, "brew:stand1:2"));
        assertEquals(AlchemyOutcome.XP_GAINED, fresh.outcome());
        assertEquals(20, awaitVal(service.progress(player)).totalXp());
        // Exactly one XP_AWARD audit row per source.
        assertEquals(2, awaitVal(service.eventsFor(player)).stream()
                .filter(e -> e.type().equals("XP_AWARD")).count());
    }

    @Test
    void existingRowTakesTheAtomicAccumulatePath() {
        // Pre-insert the progress row the way a concurrent first award would.
        await(db.transaction(tx -> {
            AlchemyStore.insertProgress(tx, player, 50, clock.get());
            return null;
        }));
        AlchemyResult<BrewProgress> result = awaitVal(service.awardBrewXp(player,
                10, "brew:stand1:1"));
        assertEquals(AlchemyOutcome.XP_GAINED, result.outcome());
        assertEquals(60, result.payload().totalXp(),
                "the insert classified its PK loss into the accumulate");
    }

    @Test
    void levelClampsAtTheConfiguredMaximum() {
        AlchemyConfig clamped = new AlchemyConfig(new BrewingCurve(2, 10, 0, 10),
                config().unlocks(), config().bags(), config().autoPolicy());
        service = new AlchemyService(db, clamped, clock::get);
        // The first award crosses the only threshold there is (1 -> 2).
        AlchemyResult<BrewProgress> up = awaitVal(service.awardBrewXp(player,
                10, "brew:stand1:1"));
        assertEquals(AlchemyOutcome.LEVEL_UP, up.outcome());

        AlchemyResult<BrewProgress> beyond = awaitVal(service.awardBrewXp(player,
                500, "brew:stand1:2"));
        assertEquals(AlchemyOutcome.XP_GAINED, beyond.outcome(),
                "XP past max is still XP - only the level clamps");
        BrewProgress progress = beyond.payload();
        assertEquals(2, progress.level());
        assertTrue(progress.maxedOut());
        assertEquals(0, progress.nextLevelCost());
    }

    @Test
    void badAwardArgumentsAreRefused() {
        assertEquals(AlchemyOutcome.ARGUMENT_INVALID, awaitVal(service.awardBrewXp(
                player, 0, "brew:1")).outcome());
        assertEquals(AlchemyOutcome.ARGUMENT_INVALID, awaitVal(service.awardBrewXp(
                player, AlchemyService.MAX_AWARD + 1, "brew:1")).outcome());
        assertEquals(AlchemyOutcome.ARGUMENT_INVALID, awaitVal(service.awardBrewXp(
                player, 5, "Bad Key!")).outcome());
        assertEquals(0, awaitVal(service.progress(player)).totalXp());
    }

    // ------------------------------------------------------------------
    // Unlock purchase protocol
    // ------------------------------------------------------------------

    @Test
    void unlockPurchaseHappyPath() {
        awardToLevel(3);
        AlchemyResult<AlchemyClaim> reserved = awaitVal(service.reserveUnlock(
                player, "frugal_brewing", "gold_ingot:4"));
        assertEquals(AlchemyOutcome.RESERVED, reserved.outcome());
        assertEquals(ClaimState.RESERVED, reserved.payload().state());
        assertEquals(ClaimPurpose.UNLOCK_PURCHASE, reserved.payload().purpose());

        AlchemyResult<UnlockGrant> committed = awaitVal(service.commitUnlock(
                reserved.payload().claimId(), player));
        assertEquals(AlchemyOutcome.UNLOCK_GRANTED, committed.outcome());
        assertEquals(reserved.payload().claimId(), committed.payload().claimId());
        assertTrue(awaitVal(service.hasUnlock(player, "frugal_brewing")));
        assertEquals(1, awaitVal(service.unlocks(player)).size());
    }

    @Test
    void unlockPurchaseRefusalFamilies() {
        // Level gate: a fresh player is level 1, potent_swiftness wants 5.
        assertEquals(AlchemyOutcome.LEVEL_TOO_LOW, awaitVal(service.reserveUnlock(
                player, "potent_swiftness", "amethyst_shard:8")).outcome());
        assertEquals(AlchemyOutcome.UNLOCK_UNKNOWN, awaitVal(service.reserveUnlock(
                player, "nope", "x:1")).outcome());

        // Prerequisite gate: potent_swiftness chained behind frugal_brewing.
        config = chainedConfig();
        service = new AlchemyService(db, config, clock::get);
        awardToLevel(3);
        assertEquals(AlchemyOutcome.PREREQUISITE_MISSING, awaitVal(
                service.reserveUnlock(player, "potent_swiftness",
                        "amethyst_shard:8")).outcome());

        // Single-reservation race guard.
        AlchemyResult<AlchemyClaim> reserved = awaitVal(service.reserveUnlock(
                player, "frugal_brewing", "gold_ingot:4"));
        assertEquals(AlchemyOutcome.RESERVED, reserved.outcome());
        assertEquals(AlchemyOutcome.RESERVATION_HELD, awaitVal(service.reserveUnlock(
                player, "frugal_brewing", "gold_ingot:4")).outcome());

        // Already-owned after commit.
        awaitVal(service.commitUnlock(reserved.payload().claimId(), player));
        assertEquals(AlchemyOutcome.ALREADY_OWNED, awaitVal(service.reserveUnlock(
                player, "frugal_brewing", "gold_ingot:4")).outcome());
    }

    @Test
    void disabledUnlockIsRefused() {
        AlchemyConfig base = AlchemyConfig.defaults();
        UnlockDefinition disabled = new UnlockDefinition("frugal_brewing", false,
                UnlockKind.EFFICIENCY, 1, List.of(),
                base.unlocks().get("frugal_brewing").cost(),
                java.util.Optional.empty(), 0, 1.0, 0.10, 0, 15);
        Map<String, UnlockDefinition> unlocks = new HashMap<>(base.unlocks());
        unlocks.put("frugal_brewing", disabled);
        AlchemyConfig disabledConfig = new AlchemyConfig(base.brewing(), unlocks,
                base.bags(), base.autoPolicy());
        service = new AlchemyService(db, disabledConfig, clock::get);
        assertEquals(AlchemyOutcome.UNLOCK_DISABLED, awaitVal(service.reserveUnlock(
                player, "frugal_brewing", "gold_ingot:4")).outcome());
    }

    @Test
    void releaseIsTheRefundPathAndIsIdempotent() {
        awardToLevel(3);
        AlchemyClaim reserved = awaitVal(service.reserveUnlock(player,
                "frugal_brewing", "gold_ingot:4")).payload();
        AlchemyResult<AlchemyClaim> released = awaitVal(service.releaseReservation(
                reserved.claimId(), player, "inventory-short"));
        assertEquals(AlchemyOutcome.RELEASED, released.outcome());

        AlchemyResult<AlchemyClaim> again = awaitVal(service.releaseReservation(
                reserved.claimId(), player, "inventory-short"));
        assertEquals(AlchemyOutcome.RELEASE_NOOP, again.outcome());
        assertFalse(again.mutated());

        List<AlchemyClaim> claims = awaitVal(service.claims(player));
        assertEquals(1, claims.size());
        assertEquals(ClaimState.RELEASED, claims.get(0).state());
        assertEquals("gold_ingot:4", claims.get(0).digest(),
                "the digest survives as refund evidence");
        assertEquals(1, awaitVal(service.eventsFor(player)).stream()
                .filter(e -> e.type().equals("CLAIM_RELEASE")).count());
    }

    @Test
    void commitRedeliveryResolvesTheOriginalOutcome() {
        awardToLevel(3);
        AlchemyClaim reserved = awaitVal(service.reserveUnlock(player,
                "frugal_brewing", "gold_ingot:4")).payload();
        awaitVal(service.commitUnlock(reserved.claimId(), player));

        AlchemyResult<UnlockGrant> replay = awaitVal(service.commitUnlock(
                reserved.claimId(), player));
        assertEquals(AlchemyOutcome.COMMIT_NOOP, replay.outcome());
        assertTrue(replay.success());
        assertFalse(replay.mutated());
        assertTrue(replay.detail().contains("original outcome GRANTED"),
                replay::detail);
    }

    @Test
    void claimOwnershipIsEnforced() {
        awardToLevel(3);
        AlchemyClaim reserved = awaitVal(service.reserveUnlock(player,
                "frugal_brewing", "gold_ingot:4")).payload();
        assertEquals(AlchemyOutcome.ARGUMENT_INVALID, awaitVal(service.commitUnlock(
                reserved.claimId(), other)).outcome());
        assertEquals(AlchemyOutcome.ARGUMENT_INVALID, awaitVal(
                service.releaseReservation(reserved.claimId(), other, "x"))
                .outcome());
        assertEquals(AlchemyOutcome.CLAIM_NOT_FOUND, awaitVal(service.commitUnlock(
                UUID.randomUUID(), player)).outcome());
        // The claim survives the rejected access attempts untouched.
        assertEquals(ClaimState.RESERVED, awaitVal(service.claims(player))
                .get(0).state());
    }

    @Test
    void staleClaimSweepReleasesWithAudit() {
        awardToLevel(3);
        AlchemyClaim reserved = awaitVal(service.reserveUnlock(player,
                "frugal_brewing", "gold_ingot:4")).payload();
        clock.addAndGet(10 * 60_000L);
        assertEquals(1, awaitVal(service.releaseStaleClaims(5 * 60_000L)));
        assertEquals(0, awaitVal(service.releaseStaleClaims(5 * 60_000L)),
                "re-runs are no-ops");
        List<AlchemyClaim> claims = awaitVal(service.claims(player));
        assertEquals(ClaimState.RELEASED, claims.get(0).state());
        assertEquals("gold_ingot:4", claims.get(0).digest());
        assertEquals(1, awaitVal(service.eventsFor(player)).stream()
                .filter(e -> e.detail() != null
                        && e.detail().contains("stale-sweep")).count());
    }

    @Test
    void adminGrantIsIdempotentAndAudited() {
        AlchemyResult<UnlockGrant> grant = awaitVal(service.grantUnlock(player,
                "frugal_brewing", "recovery"));
        assertEquals(AlchemyOutcome.UNLOCK_GRANTED, grant.outcome());
        assertNull(grant.payload().claimId(), "admin grants carry no claim");

        AlchemyResult<UnlockGrant> replay = awaitVal(service.grantUnlock(player,
                "frugal_brewing", "recovery"));
        assertEquals(AlchemyOutcome.UNLOCK_GRANT_NOOP, replay.outcome());
        assertTrue(replay.success());
        assertFalse(replay.mutated());

        assertEquals(AlchemyOutcome.UNLOCK_UNKNOWN, awaitVal(service.grantUnlock(
                player, "nope", "recovery")).outcome());
        // Purchasing what an admin already granted is still refused.
        awardToLevel(3);
        assertEquals(AlchemyOutcome.ALREADY_OWNED, awaitVal(service.reserveUnlock(
                player, "frugal_brewing", "gold_ingot:4")).outcome());
    }

    @Test
    void adminGrantBetweenReserveAndCommitSpendsTheClaim() {
        awardToLevel(3);
        AlchemyClaim reserved = awaitVal(service.reserveUnlock(player,
                "frugal_brewing", "gold_ingot:4")).payload();
        awaitVal(service.grantUnlock(player, "frugal_brewing", "race"));

        AlchemyResult<UnlockGrant> committed = awaitVal(service.commitUnlock(
                reserved.claimId(), player));
        assertEquals(AlchemyOutcome.UNLOCK_GRANTED, committed.outcome());
        assertTrue(committed.detail().contains("already owned - cost spent"),
                committed::detail);
        assertNull(committed.payload().claimId());
        assertEquals("ALREADY_OWNED", awaitVal(service.eventsFor(player)).stream()
                .filter(e -> e.type().equals("UNLOCK_COMMIT")).findFirst()
                .orElseThrow().outcome());
    }

    // ------------------------------------------------------------------
    // Auto-consumption rules
    // ------------------------------------------------------------------

    @Test
    void ruleDefinitionFollowsTheDeterministicConflictOrder() {
        defineHealingRuleWith(player, 3, "r3");
        defineHealingRuleWith(player, 1, "r1");
        defineHealingRuleWith(player, 2, "r2");
        List<AutoRule> rules = awaitVal(service.rules(player));
        assertEquals(3, rules.size());
        assertTrue(rules.get(0).priority() <= rules.get(1).priority());
        assertEquals(1, rules.get(0).priority());
        assertEquals(2, rules.get(1).priority());
        assertEquals(3, rules.get(2).priority());
        assertTrue(rules.get(0).enabled(), "rules are born enabled (opt-out toggle)");
        assertEquals(0, awaitVal(service.rules(other)).size());
    }

    private void defineHealingRuleWith(UUID owner, int priority, String suffix) {
        AlchemyResult<AutoRule> result = awaitVal(service.defineAutoRule(
                UUID.nameUUIDFromBytes((owner + ":" + suffix).getBytes()), owner,
                AutoTrigger.HEALTH_BELOW, PotionType.HEALING, 40, 60, priority,
                StorageScope.ANY, "test"));
        assertTrue(result.success(), result::toString);
    }

    @Test
    void ruleDefinitionRefusalFamilies() {
        assertEquals(AlchemyOutcome.TRIGGER_DISABLED, awaitVal(service.defineAutoRule(
                UUID.randomUUID(), player, AutoTrigger.FALLING, PotionType.HEALING,
                null, 60, 1, StorageScope.ANY, "t")).outcome(),
                "FALLING ships disabled by policy");
        assertEquals(AlchemyOutcome.POTION_NOT_ALLOWED, awaitVal(service.defineAutoRule(
                UUID.randomUUID(), player, AutoTrigger.BURNING, PotionType.POISON,
                null, 60, 1, StorageScope.ANY, "t")).outcome());
        assertEquals(AlchemyOutcome.THRESHOLD_OUT_OF_BOUNDS, awaitVal(
                service.defineAutoRule(UUID.randomUUID(), player,
                        AutoTrigger.HEALTH_BELOW, PotionType.HEALING, null, 60, 1,
                        StorageScope.ANY, "t")).outcome(),
                "HEALTH_BELOW requires its threshold");
        assertEquals(AlchemyOutcome.THRESHOLD_OUT_OF_BOUNDS, awaitVal(
                service.defineAutoRule(UUID.randomUUID(), player,
                        AutoTrigger.HEALTH_BELOW, PotionType.HEALING, 100, 60, 1,
                        StorageScope.ANY, "t")).outcome());
        assertEquals(AlchemyOutcome.ARGUMENT_INVALID, awaitVal(service.defineAutoRule(
                UUID.randomUUID(), player, AutoTrigger.BURNING, PotionType.HEALING,
                40, 60, 1, StorageScope.ANY, "t")).outcome(),
                "BURNING carries no threshold");
        assertEquals(AlchemyOutcome.COOLDOWN_BELOW_FLOOR, awaitVal(
                service.defineAutoRule(UUID.randomUUID(), player,
                        AutoTrigger.BURNING, PotionType.FIRE_RESISTANCE, null, 30, 1,
                        StorageScope.ANY, "t")).outcome(),
                "the anti-bot floor is policy");
        assertEquals(AlchemyOutcome.PRIORITY_OUT_OF_RANGE, awaitVal(
                service.defineAutoRule(UUID.randomUUID(), player,
                        AutoTrigger.BURNING, PotionType.FIRE_RESISTANCE, null, 60, 0,
                        StorageScope.ANY, "t")).outcome());
        assertEquals(AlchemyOutcome.STORAGE_INVALID, awaitVal(service.defineAutoRule(
                UUID.randomUUID(), player, AutoTrigger.BURNING,
                PotionType.FIRE_RESISTANCE, null, 60, 1, null, "t")).outcome());
    }

    @Test
    void ruleCapCountsLiveRowsOnly() {
        AutoPolicy tight = new AutoPolicy(config().autoPolicy().triggers(), 1, 99,
                60, 1, 100, 2, config().autoPolicy().allowedPotionTypes());
        service = new AlchemyService(db, config(tight), clock::get);
        defineHealingRuleWith(player, 1, "a");
        defineHealingRuleWith(player, 2, "b");
        AlchemyResult<AutoRule> third = awaitVal(service.defineAutoRule(
                UUID.randomUUID(), player, AutoTrigger.BURNING,
                PotionType.FIRE_RESISTANCE, null, 60, 1, StorageScope.ANY, "t"));
        assertEquals(AlchemyOutcome.RULE_CAP_REACHED, third.outcome());

        // Removing one frees a slot - the cap counts live rows.
        AutoRule first = awaitVal(service.rules(player)).get(0);
        awaitVal(service.removeAutoRule(first.ruleId(), player, "cleanup"));
        AlchemyResult<AutoRule> now = awaitVal(service.defineAutoRule(
                UUID.randomUUID(), player, AutoTrigger.BURNING,
                PotionType.FIRE_RESISTANCE, null, 60, 1, StorageScope.ANY, "t"));
        assertEquals(AlchemyOutcome.RULE_DEFINED, now.outcome());
    }

    @Test
    void ruleDefineIsIdempotentByRuleId() {
        AutoRule rule = defineHealingRule(player);
        AlchemyResult<AutoRule> again = awaitVal(service.defineAutoRule(
                rule.ruleId(), player, AutoTrigger.BURNING, PotionType.HEALING,
                null, 120, 5, StorageScope.BAG, "redefine"));
        assertEquals(AlchemyOutcome.RULE_EXISTS, again.outcome());
        assertTrue(again.success());
        assertFalse(again.mutated());
        assertEquals(rule.trigger(), again.payload().trigger(),
                "the original definition is carried untouched");
        assertEquals(1, awaitVal(service.rules(player)).size());
    }

    @Test
    void ruleToggleIsOwnershipGuardedAndIdempotent() {
        AutoRule rule = defineHealingRule(player);
        AlchemyResult<AutoRule> off = awaitVal(service.toggleAutoRule(rule.ruleId(),
                player, false, "saving potions"));
        assertEquals(AlchemyOutcome.RULE_DISABLED, off.outcome());
        assertFalse(off.payload().enabled());
        assertFalse(awaitVal(service.rule(rule.ruleId())).orElseThrow().enabled());

        AlchemyResult<AutoRule> noop = awaitVal(service.toggleAutoRule(
                rule.ruleId(), player, false, "again"));
        assertEquals(AlchemyOutcome.TOGGLE_NOOP, noop.outcome());
        assertFalse(noop.mutated());

        assertEquals(AlchemyOutcome.ARGUMENT_INVALID, awaitVal(service.toggleAutoRule(
                rule.ruleId(), other, true, "foreign")).outcome());
    }

    @Test
    void ruleRemovalStampsAndKeepsForensics() {
        AutoRule rule = defineHealingRule(player);
        AlchemyResult<AutoRule> removed = awaitVal(service.removeAutoRule(
                rule.ruleId(), player, "cleanup"));
        assertEquals(AlchemyOutcome.RULE_REMOVED, removed.outcome());
        assertNotNull(removed.payload().removedUtc());
        assertEquals(0, awaitVal(service.rules(player)).size(),
                "removed rules stop resolving");

        List<AutoRule> all = awaitVal(service.allRules(player));
        assertEquals(1, all.size());
        assertNotNull(all.get(0).removedUtc(), "the row is kept for forensics");

        assertEquals(AlchemyOutcome.RULE_REMOVE_NOOP, awaitVal(
                service.removeAutoRule(rule.ruleId(), player, "again")).outcome());
        assertEquals(AlchemyOutcome.RULE_NOT_FOUND, awaitVal(service.toggleAutoRule(
                rule.ruleId(), player, true, "gone")).outcome());
        assertEquals(AlchemyOutcome.RULE_NOT_FOUND, awaitVal(service.reserveAutoConsume(
                player, rule.ruleId(), "potion:heal:1")).outcome(),
                "removed rules refuse execution too");
        assertEquals(AlchemyOutcome.RULE_EXISTS, awaitVal(service.defineAutoRule(
                rule.ruleId(), player, AutoTrigger.BURNING, PotionType.HEALING,
                null, 60, 1, StorageScope.ANY, "reuse")).outcome(),
                "a removed rule id is consumed forever");
    }

    // ------------------------------------------------------------------
    // Auto-consumption execution protocol
    // ------------------------------------------------------------------

    @Test
    void autoConsumeProtocolBuysCooldownsAtomically() {
        AutoRule rule = defineHealingRule(player);
        AlchemyResult<AlchemyClaim> reserved = awaitVal(service.reserveAutoConsume(
                player, rule.ruleId(), "potion:healing:1"));
        assertEquals(AlchemyOutcome.AUTO_RESERVED, reserved.outcome());

        // Drink race guard: one execution reservation per rule at a time.
        assertEquals(AlchemyOutcome.RESERVATION_HELD, awaitVal(
                service.reserveAutoConsume(player, rule.ruleId(),
                        "potion:healing:1")).outcome());

        AlchemyResult<AutoRule> consumed = awaitVal(service.commitAutoConsume(
                reserved.payload().claimId(), player));
        assertEquals(AlchemyOutcome.AUTO_CONSUMED, consumed.outcome());
        assertNotNull(consumed.payload().lastFiredUtc(),
                "the cooldown stamp is the commit's all-or-none partner");

        // The stamp is the authority: an immediate re-fire is refused.
        assertEquals(AlchemyOutcome.COOLDOWN_ACTIVE, awaitVal(
                service.reserveAutoConsume(player, rule.ruleId(),
                        "potion:healing:1")).outcome());

        // Cooldown elapsed (60 s) -> the next cycle fires again.
        clock.addAndGet(60_001L);
        AlchemyResult<AlchemyClaim> second = awaitVal(service.reserveAutoConsume(
                player, rule.ruleId(), "potion:healing:1"));
        assertEquals(AlchemyOutcome.AUTO_RESERVED, second.outcome());
        assertEquals(AlchemyOutcome.AUTO_CONSUMED, awaitVal(service.commitAutoConsume(
                second.payload().claimId(), player)).outcome());
    }

    @Test
    void autoConsumeRedeliveryCarriesTheOriginalOutcome() {
        AutoRule rule = defineHealingRule(player);
        AlchemyClaim reserved = awaitVal(service.reserveAutoConsume(player,
                rule.ruleId(), "potion:healing:1")).payload();
        awaitVal(service.commitAutoConsume(reserved.claimId(), player));
        AlchemyResult<AutoRule> replay = awaitVal(service.commitAutoConsume(
                reserved.claimId(), player));
        assertEquals(AlchemyOutcome.COMMIT_NOOP, replay.outcome());
        assertFalse(replay.mutated());
        assertTrue(replay.detail().contains("original outcome FIRED"),
                replay::detail);
    }

    @Test
    void autoConsumeGuardsOwnershipAndState() {
        AutoRule rule = defineHealingRule(player);
        assertEquals(AlchemyOutcome.RULE_NOT_FOUND, awaitVal(service.reserveAutoConsume(
                player, UUID.randomUUID(), "potion:healing:1")).outcome());

        awaitVal(service.toggleAutoRule(rule.ruleId(), player, false, "off"));
        assertEquals(AlchemyOutcome.RULE_NOT_ACTIVE, awaitVal(
                service.reserveAutoConsume(player, rule.ruleId(),
                        "potion:healing:1")).outcome());

        awaitVal(service.toggleAutoRule(rule.ruleId(), player, true, "on"));
        AlchemyResult<AlchemyClaim> reserved = awaitVal(service.reserveAutoConsume(
                player, rule.ruleId(), "potion:healing:1"));
        assertEquals(AlchemyOutcome.ARGUMENT_INVALID, awaitVal(
                service.commitAutoConsume(reserved.payload().claimId(), other))
                .outcome());
    }

    @Test
    void midFlightRemovalCommitsUnstamped() {
        AutoRule rule = defineHealingRule(player);
        AlchemyClaim reserved = awaitVal(service.reserveAutoConsume(player,
                rule.ruleId(), "potion:healing:1")).payload();
        // The rule vanishes between the drink and the commit.
        awaitVal(service.removeAutoRule(rule.ruleId(), player, "changed mind"));
        AlchemyResult<AutoRule> consumed = awaitVal(service.commitAutoConsume(
                reserved.claimId(), player));
        assertEquals(AlchemyOutcome.AUTO_CONSUMED, consumed.outcome());
        assertTrue(consumed.detail().contains("stamp not applied"),
                consumed::detail);
        assertNull(consumed.payload().lastFiredUtc());
        assertEquals("FIRED_UNSTAMPED", awaitVal(service.eventsFor(player)).stream()
                .filter(e -> e.type().equals("AUTO_CONSUME")).findFirst()
                .orElseThrow().outcome());
    }

    // ------------------------------------------------------------------
    // Restart round-trip
    // ------------------------------------------------------------------

    @Test
    void stateSurvivesARestart() throws Exception {
        awardToLevel(3);
        AlchemyClaim reserved = awaitVal(service.reserveUnlock(player,
                "frugal_brewing", "gold_ingot:4")).payload();
        awaitVal(service.commitUnlock(reserved.claimId(), player));
        AutoRule rule = defineHealingRule(player);
        AlchemyClaim consume = awaitVal(service.reserveAutoConsume(player,
                rule.ruleId(), "potion:healing:1")).payload();
        long firedAt = clock.get();
        awaitVal(service.commitAutoConsume(consume.claimId(), player));

        db.shutdown();
        db = newService();
        service = new AlchemyService(db, config, clock::get);

        BrewProgress progress = awaitVal(service.progress(player));
        assertEquals(225, progress.totalXp());
        assertEquals(3, progress.level(), "the level re-derives from the row");
        assertTrue(awaitVal(service.hasUnlock(player, "frugal_brewing")));
        List<AutoRule> rules = awaitVal(service.rules(player));
        assertEquals(1, rules.size());
        assertEquals(firedAt, rules.get(0).lastFiredUtc(),
                "the cooldown stamp survives - no re-trigger after restart");

        // New work on the restarted service resolves normally.
        assertEquals(AlchemyOutcome.COOLDOWN_ACTIVE, awaitVal(
                service.reserveAutoConsume(player, rule.ruleId(),
                        "potion:healing:1")).outcome());
        // xp award + unlock commit + rule define + auto consume = the durable
        // audit trail.
        assertTrue(awaitVal(service.eventsFor(player)).size() >= 4,
                "the audit trail is durable");
    }
}
