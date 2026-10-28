package net.zaminmc.rebellion.rpg;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * One validated ritual definition from the {@code rituals} config family
 * (ADR-0016 §5). The definition is the operator-owned contract; the
 * service enforces every rule that is state (rate limit, cooldown,
 * session caps, precision ceiling, failure roll bounds) against it.
 *
 * <p>Controller-only values — preparation animation, XP/economy cost,
 * particle feel — are deliberately absent until they are enforced
 * (documentation describes actual implemented behavior).</p>
 *
 * @param key the ritual key ({@code [a-z0-9_]{1,32}})
 * @param enabled whether the ritual can be reserved at all
 * @param kind the commit family (TRACKING / COUNTER_MASK / KNOWLEDGE)
 * @param ingredients the required reagents (at least one)
 * @param cooldownSeconds min seconds after a COMMITTED attempt before the
 *        next reservation (released reservations never buy a cooldown)
 * @param rateLimitAttempts attempts (any claim state) allowed per window
 * @param rateLimitSeconds the rate-limit window
 * @param durationSeconds the granted session/effect duration
 * @param precision the tracking-precision ceiling (TRACKING only)
 * @param failureChance 0.0..<0.95 — a losing roll still commits the claim
 *        (cost + cooldown bought) but writes no session
 * @param maxSessionsPerCaster cap on simultaneously active tracking
 *        sessions per caster (TRACKING only)
 * @param knowledgeKey the discovery key unlocked by a KNOWLEDGE commit
 */
public record RitualDefinition(
        @NotNull String key,
        boolean enabled,
        @NotNull RitualKind kind,
        @NotNull List<Ingredient> ingredients,
        long cooldownSeconds,
        int rateLimitAttempts,
        long rateLimitSeconds,
        long durationSeconds,
        @NotNull Precision precision,
        double failureChance,
        int maxSessionsPerCaster,
        @NotNull Optional<String> knowledgeKey) {

    /** Structural bounds (portable validation; mirrored by RpgConfig). */
    public static final int MAX_KEY = 32;
    public static final long MAX_SECONDS = 2_592_000L; // 30 days
    public static final int MIN_DURATION_SECONDS = 30;
    public static final int MAX_DURATION_SECONDS = 86_400;
    public static final int MAX_RATE_ATTEMPTS = 100;
    public static final long MAX_RATE_WINDOW_SECONDS = 86_400;
    public static final double MAX_FAILURE_CHANCE = 0.95;
    public static final int MAX_SESSIONS = 10;

    public RitualDefinition {
        ingredients = List.copyOf(ingredients);
        knowledgeKey = Optional.ofNullable(knowledgeKey.orElse(null));
    }

    /** @return the configured cooldown in milliseconds. */
    public long cooldownMs() {
        return cooldownSeconds * 1000L;
    }

    /** @return the configured rate-limit window in milliseconds. */
    public long rateLimitWindowMs() {
        return rateLimitSeconds * 1000L;
    }

    /** @return the configured duration in milliseconds. */
    public long durationMs() {
        return durationSeconds * 1000L;
    }

    /** @return a deterministic digest of the ingredient set (refund
     *         evidence format; the controller snapshots the same shape). */
    public @NotNull String digest() {
        StringBuilder sb = new StringBuilder();
        for (Ingredient ingredient : ingredients) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(ingredient.material().name().toLowerCase(
                    java.util.Locale.ROOT)).append(':').append(ingredient.amount());
        }
        return sb.toString();
    }
}
