package net.zaminmc.rebellion.dragons.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * FireballBudget arithmetic - the 5.6 runtime budget proof: at most 30 in
 * a rolling 60 s window per dragon, min-interval anti-burst, per-dragon
 * isolation, window rollover, and refusal hints that always point forward
 * in time.
 */
final class FireballBudgetTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void capsAtThirtyPerRollingSixtySeconds() {
        FireballBudget budget = new FireballBudget(30, 60_000L, 0L);
        long t = 1_000_000L;
        for (int i = 0; i < 30; i++) {
            assertTrue(budget.tryLaunch(A, t).allowed(), "launch " + i + " must pass");
            t += 100L;
        }
        FireballBudget.Decision refused = budget.tryLaunch(A, t);
        assertFalse(refused.allowed());
        assertEquals(30, refused.used());
        assertEquals(30, refused.max());
        // The retry hint is the oldest launch + window - now: forward in time.
        // 30 launches happened at t-3000, t-2900, ..., t-100 (loop increments
        // after each launch), so the oldest is t-3000.
        long oldest = t - 30 * 100L;
        assertEquals(oldest + 60_000L - t, refused.retryAfterMs());
    }

    @Test
    void windowRollsOverAndFreesSlots() {
        FireballBudget budget = new FireballBudget(3, 10_000L, 0L);
        long t = 500_000L;
        for (int i = 0; i < 3; i++) {
            assertTrue(budget.tryLaunch(A, t).allowed());
        }
        assertFalse(budget.tryLaunch(A, t + 1_000L).allowed());
        // The first launch left the window: one slot is free again. The two
        // remaining launches shared the same stamp t, so they age out
        // together later.
        assertTrue(budget.tryLaunch(A, t + 10_001L).allowed());
        assertEquals(1, budget.usedInWindow(A, t + 10_001L),
                "only the just-launched entry is inside the window now");
    }

    @Test
    void minIntervalBlocksBursts() {
        FireballBudget budget = new FireballBudget(30, 60_000L, 2_000L);
        long t = 700_000L;
        assertTrue(budget.tryLaunch(A, t).allowed());
        FireballBudget.Decision tooSoon = budget.tryLaunch(A, t + 500L);
        assertFalse(tooSoon.allowed());
        assertEquals(1_500L, tooSoon.retryAfterMs());
        assertTrue(budget.tryLaunch(A, t + 2_000L).allowed());
    }

    @Test
    void dragonsAreIsolated() {
        FireballBudget budget = new FireballBudget(1, 60_000L, 0L);
        long t = 900_000L;
        assertTrue(budget.tryLaunch(A, t).allowed());
        // Dragon B has its own window: A's saturation never taxes B.
        assertTrue(budget.tryLaunch(B, t).allowed());
        assertFalse(budget.tryLaunch(A, t).allowed());
        assertFalse(budget.tryLaunch(B, t).allowed());
    }

    @Test
    void forgetDropsAllAccounting() {
        FireballBudget budget = new FireballBudget(1, 60_000L, 0L);
        long t = 100L;
        assertTrue(budget.tryLaunch(A, t).allowed());
        assertFalse(budget.tryLaunch(A, t).allowed());
        budget.forget(A);
        assertTrue(budget.tryLaunch(A, t).allowed(), "forget must reset the window");
        assertEquals(0, budget.usedInWindow(B, t));
    }

    @Test
    void constructorRejectsAbsurdArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new FireballBudget(0, 60_000L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new FireballBudget(30, 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new FireballBudget(30, 60_000L, -1L));
    }
}
