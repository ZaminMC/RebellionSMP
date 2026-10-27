package net.zaminmc.rebellion.wards.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link TimedMasking} — grant, lazy expiry, extension by maximum, and
 * revoke. Pure store semantics; the resolver combining it with held-item
 * masking is covered by the detector suite.
 */
class TimedMaskingTest {

    @Test
    void grantsExpireAndAreLazilyRemoved() {
        TimedMasking masking = new TimedMasking();
        UUID player = UUID.randomUUID();
        masking.mask(player, 10_000L);

        assertTrue(masking.masked(player, 5_000L));
        assertFalse(masking.masked(player, 10_000L), "expiry is inclusive");
        assertFalse(masking.masked(player, 20_000L));
        assertEquals(0, masking.activeGrants(), "expired grant was dropped on read");
    }

    @Test
    void overlappingGrantsExtendNeverShorten() {
        TimedMasking masking = new TimedMasking();
        UUID player = UUID.randomUUID();
        masking.mask(player, 50_000L);
        masking.mask(player, 20_000L); // shorter grant must not shorten
        assertTrue(masking.masked(player, 40_000L));
        assertEquals(1, masking.activeGrants());

        masking.mask(player, 80_000L); // longer extends
        assertTrue(masking.masked(player, 70_000L));
    }

    @Test
    void clearRevokesImmediately() {
        TimedMasking masking = new TimedMasking();
        UUID player = UUID.randomUUID();
        masking.mask(player, 100_000L);
        masking.clear(player);
        assertFalse(masking.masked(player, 1_000L));
        assertEquals(0, masking.activeGrants());
    }

    @Test
    void grantsArePerPlayer() {
        TimedMasking masking = new TimedMasking();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        masking.mask(a, 100_000L);
        assertTrue(masking.masked(a, 1_000L));
        assertFalse(masking.masked(b, 1_000L));
    }
}
