package net.zaminmc.rebellion.dragons.policy;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Per-dragon fireball token bucket (master prompt §5.6): a dragon may launch
 * at most {@code maxPerWindow} plugin fireballs in any rolling
 * {@code windowMs}, with a {@code minIntervalMs} anti-burst guard between
 * consecutive launches. Hard bounds are enforced at config load; this class
 * trusts its constructor arguments and enforces the runtime contract only.
 *
 * <p>Pure and Bukkit-free on purpose: the budget arithmetic is exactly the
 * part §5.6 demands tested (30-per-minute cap, no spam), and it is unit-
 * tested directly with a fake clock. The controller calls
 * {@link #tryLaunch} <em>before</em> spawning the projectile; a refusal
 * means nothing spawns, so the budget can never be exceeded by a race
 * between check and spawn on the main thread (all launches happen there).</p>
 */
public final class FireballBudget {

    /** Launch decision: ok, or refusal with the milliseconds until retry. */
    public record Decision(boolean allowed, long retryAfterMs, int used, int max) {
        static Decision refused(long retryAfterMs, int used, int max) {
            return new Decision(false, retryAfterMs, used, max);
        }

        static Decision allowed(int used, int max) {
            return new Decision(true, 0L, used, max);
        }
    }

    private final int maxPerWindow;
    private final long windowMs;
    private final long minIntervalMs;
    private final Map<UUID, Window> windows = new HashMap<>();

    private static final class Window {
        final ArrayDeque<Long> launches = new ArrayDeque<>();
        long lastLaunch = Long.MIN_VALUE;
    }

    public FireballBudget(int maxPerWindow, long windowMs, long minIntervalMs) {
        if (maxPerWindow < 1 || windowMs <= 0 || minIntervalMs < 0) {
            throw new IllegalArgumentException(
                    "maxPerWindow >= 1, windowMs > 0, minIntervalMs >= 0 required");
        }
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
        this.minIntervalMs = minIntervalMs;
    }

    /**
     * Tries to reserve one launch slot for the given dragon at
     * {@code nowUtc}. When allowed, the timestamp is recorded immediately —
     * callers must launch only when {@code allowed} is true.
     */
    public @NotNull Decision tryLaunch(@NotNull UUID dragon, long nowUtc) {
        Window w = windows.computeIfAbsent(dragon, k -> new Window());
        // Evict launches that fell out of the rolling window.
        while (!w.launches.isEmpty() && nowUtc - w.launches.peekFirst() >= windowMs) {
            w.launches.pollFirst();
        }
        if (!w.launches.isEmpty()) {
            long sinceLast = nowUtc - w.lastLaunch;
            if (sinceLast < minIntervalMs) {
                return Decision.refused(minIntervalMs - sinceLast, w.launches.size(),
                        maxPerWindow);
            }
        }
        if (w.launches.size() >= maxPerWindow) {
            long oldest = w.launches.peekFirst();
            return Decision.refused(oldest + windowMs - nowUtc, w.launches.size(),
                    maxPerWindow);
        }
        w.launches.addLast(nowUtc);
        w.lastLaunch = nowUtc;
        return Decision.allowed(w.launches.size(), maxPerWindow);
    }

    /** @return launches recorded for this dragon inside the current window (diagnostics). */
    public int usedInWindow(@NotNull UUID dragon, long nowUtc) {
        Window w = windows.get(dragon);
        if (w == null) {
            return 0;
        }
        while (!w.launches.isEmpty() && nowUtc - w.launches.peekFirst() >= windowMs) {
            w.launches.pollFirst();
        }
        return w.launches.size();
    }

    /** Drops all accounting for a dragon (death/restart hygiene). */
    public void forget(@NotNull UUID dragon) {
        windows.remove(dragon);
    }
}
