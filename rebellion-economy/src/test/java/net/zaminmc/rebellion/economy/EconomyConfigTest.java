package net.zaminmc.rebellion.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the operator contract of {@link EconomyConfig} (ADR-0021): the
 * currency display family, the overflow-safe maximum bound, defaults,
 * and the collect-all issue behavior on invalid documents.
 */
final class EconomyConfigTest {

    @Test
    void defaultsAreConservative() {
        EconomyConfig config = EconomyConfig.defaults();
        assertEquals("Rebellion Marks", config.currency().name());
        assertEquals("RM", config.currency().symbol());
        assertEquals(100, config.currency().scale());
        assertEquals(1_000_000_000_000_00L, config.maxBalanceMinor());
    }

    @Test
    void minimalValidDocumentLoads() {
        EconomyConfig.LoadResult result = EconomyConfig.load("""
                currency:
                  name: Marks
                  symbol: M
                  scale: 1000
                bounds:
                  max-balance: 500000
                """);
        assertTrue(result.valid(), () -> String.join("; ", result.issues()));
        assertEquals("Marks", result.config().currency().name());
        assertEquals(1000, result.config().currency().scale());
        assertEquals(500_000L, result.config().maxBalanceMinor());
    }

    @Test
    void emptyDocumentFallsBackToDefaults() {
        EconomyConfig.LoadResult result = EconomyConfig.load("");
        assertTrue(result.valid(), () -> String.join("; ", result.issues()));
        assertEquals(EconomyConfig.defaults(), result.config());
    }

    @Test
    void everyInvalidBoundIsCollected() {
        EconomyConfig.LoadResult result = EconomyConfig.load("""
                currency:
                  name: ''
                  symbol: waytoolongsymbol
                  scale: -5
                bounds:
                  max-balance: 0
                """);
        assertFalse(result.valid());
        assertTrue(result.issues().size() >= 3,
                () -> String.join("; ", result.issues()));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("currency.name")));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("currency.symbol")));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("currency.scale")));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("bounds.max-balance")));
    }

    @Test
    void maxBalanceCannotExceedTheOverflowSafeLimit() {
        EconomyConfig.LoadResult result = EconomyConfig.load("""
                bounds:
                  max-balance: 9223372036854775807
                """);
        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("bounds.max-balance")));
    }

    @Test
    void keyAndReasonGrammarsArePinned() {
        assertTrue(EconomyConfig.KEY.matcher("prison-fee:0b37a1c8-9").matches());
        assertTrue(EconomyConfig.KEY.matcher("shop.buy.42").matches());
        assertFalse(EconomyConfig.KEY.matcher("UPPER").matches());
        assertFalse(EconomyConfig.KEY.matcher("with space").matches());
        assertFalse(EconomyConfig.KEY.matcher("").matches());

        assertTrue(EconomyConfig.REASON.matcher("prison.release-fee").matches());
        assertTrue(EconomyConfig.REASON.matcher("admin.grant").matches());
        assertFalse(EconomyConfig.REASON.matcher("Not A Reason").matches());
    }
}
