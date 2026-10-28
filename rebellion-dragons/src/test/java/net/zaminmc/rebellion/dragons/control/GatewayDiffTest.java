package net.zaminmc.rebellion.dragons.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.zaminmc.rebellion.dragons.Lineage;
import org.junit.jupiter.api.Test;

/**
 * GatewayDiff mint-decision - the ADR-0014 fail-closed proof. Only gateways
 * that emerged inside the victory's observation window AND do not already
 * back a lineage are mintable; count mismatches are refused; scan misses
 * are warnings, never guesses.
 */
final class GatewayDiffTest {

    private static Lineage.GatewayKey key(String world, int x) {
        return Lineage.GatewayKey.of(world, x, 64, 0);
    }

    @Test
    void onlyEmergingUnregisteredGatewaysMint() {
        GatewayDiff.Decision decision = GatewayDiff.decide(
                List.of(key("the_end", 0), key("the_end", 16)),
                List.of(key("the_end", 0), key("the_end", 16), key("the_end", 32)),
                List.of(), 1);
        assertTrue(decision.mintAnything());
        assertEquals(List.of(key("the_end", 32)), decision.mint());
        assertNull(decision.warning());
    }

    @Test
    void alreadyRegisteredPositionsNeverMintAgain() {
        GatewayDiff.Decision decision = GatewayDiff.decide(
                List.of(key("the_end", 0)),
                List.of(key("the_end", 0), key("the_end", 48)),
                List.of(key("the_end", 48)), 1);
        assertTrue(!decision.mintAnything(),
                "a gateway backing an existing lineage is not a mint candidate");
        // Vanilla announced one new gateway, yet nothing mintable emerged:
        // that anomaly is warned (never silently skipped, never guessed).
        assertTrue(decision.warning().contains("scan miss"),
                "count-rose-but-no-candidate must warn about the miss");
    }

    @Test
    void candidatesOutsideTheBaselineButRegisteredAreExcluded() {
        // A gateway that predates the window AND backs a lineage.
        GatewayDiff.Decision decision = GatewayDiff.decide(
                List.of(),
                List.of(key("the_end", 8), key("the_end", 24)),
                List.of(key("the_end", 8)), 1);
        assertEquals(List.of(key("the_end", 24)), decision.mint());
    }

    @Test
    void ambiguousCountMismatchFailsClosed() {
        GatewayDiff.Decision decision = GatewayDiff.decide(
                List.of(key("the_end", 0)),
                List.of(key("the_end", 0), key("the_end", 16), key("the_end", 32)),
                List.of(), 1);
        assertTrue(!decision.mintAnything(), "2 candidates vs 1 announced: refuse");
        assertTrue(decision.warning().contains("ambiguous"));
    }

    @Test
    void scanMissIsAWarnedNoMint() {
        GatewayDiff.Decision decision = GatewayDiff.decide(
                List.of(key("the_end", 0), key("the_end", 16)),
                List.of(key("the_end", 0), key("the_end", 16)),
                List.of(), 2);
        assertTrue(!decision.mintAnything());
        assertTrue(decision.warning().contains("scan miss"),
                "warning must explain the miss and point at the bounds");
    }

    @Test
    void quietWindowWithNoAnnouncementMintsNothing() {
        GatewayDiff.Decision decision = GatewayDiff.decide(
                List.of(key("the_end", 0)),
                List.of(key("the_end", 0)),
                List.of(), 0);
        assertTrue(!decision.mintAnything());
        assertNull(decision.warning());
    }

    @Test
    void unreadableCountSkipsTheCrossCheck() {
        // countDelta -1 = the count could not be read: attribution relies on
        // the set-diff alone, still restricted to emerging unregistered ones.
        GatewayDiff.Decision decision = GatewayDiff.decide(
                List.of(key("the_end", 0)),
                List.of(key("the_end", 0), key("the_end", 40)),
                List.of(), -1);
        assertEquals(List.of(key("the_end", 40)), decision.mint());
        assertNull(decision.warning());
    }

    @Test
    void multipleAnnouncedGatewaysAllMintWhenTheyMatch() {
        GatewayDiff.Decision decision = GatewayDiff.decide(
                List.of(),
                List.of(key("the_end", 1), key("the_end", 2), key("the_end", 3)),
                List.of(), 3);
        assertEquals(3, decision.mint().size());
    }
}
