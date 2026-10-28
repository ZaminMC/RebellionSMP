package net.zaminmc.rebellion.alchemy.controller;

import net.zaminmc.rebellion.alchemy.UnlockDefinition;

/**
 * The pure POWER-boost math (ADR-0020 §2): how a held POWER unlock
 * rewrites the effect values of a potion being drunk. Applied at drink
 * time (manual consume and auto-consume alike) over the potion's <em>own</em>
 * values — the unlock scales what was actually in the bottle, never
 * replaces it with a flat template. The amplifier ceiling is 255 (the
 * protocol value of the vanilla amplifier byte); the duration ceiling
 * guards the ticks math against overflow at extreme multipliers.
 *
 * <p>Only primitives here — no PotionEffect, no registry — so the math is
 * serverless-testable; the live callers build the actual effect objects.</p>
 */
public final class PotionBoost {

    /** The vanilla protocol amplifier ceiling. */
    public static final int MAX_AMPLIFIER = 255;
    /** Hard duration ceiling in ticks (about 41 days) — overflow guard. */
    public static final long MAX_DURATION_TICKS = 72_000_000L;

    private PotionBoost() {
    }

    /** One boosted effect value pair. */
    public record Boost(int amplifier, int durationTicks) {
    }

    /**
     * @param amplifier the potion's own effect amplifier (0-based)
     * @param durationTicks the potion's own effect duration in ticks
     * @param definition the holder's POWER unlock for this potion type
     * @return the boosted values
     */
    public static Boost boost(int amplifier, int durationTicks,
            UnlockDefinition definition) {
        int amplified = (int) Math.min((long) amplifier
                + definition.amplifierBonus(), MAX_AMPLIFIER);
        long scaled = Math.round(durationTicks * definition.durationMultiplier());
        int duration = (int) Math.min(scaled, MAX_DURATION_TICKS);
        return new Boost(amplified, Math.max(0, duration));
    }
}
