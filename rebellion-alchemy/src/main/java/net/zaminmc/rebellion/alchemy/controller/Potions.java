package net.zaminmc.rebellion.alchemy.controller;

import java.util.List;
import net.zaminmc.rebellion.alchemy.UnlockDefinition;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Live potion reads and the boosted-effect application (ADR-0020 §2).
 * The type check speaks {@link PotionType} — the serverless-safe
 * vocabulary ADR-0019 proved — and only the actual effect application
 * crosses into registry territory ({@code PotionEffectType}), exactly
 * where a live server exists.
 *
 * <p>Boosts scale the potion's <em>own</em> values through the pure
 * {@link PotionBoost} math: the unlock upgrades what was in the bottle,
 * never replaces it with a flat template. Both drink paths (manual
 * consume and auto-consume) share {@link #apply}.</p>
 */
public final class Potions {

    private Potions() {
    }

    /** @return true when the stack is a drinkable potion of the type. */
    public static boolean isOfType(@Nullable ItemStack stack,
            @NotNull PotionType type) {
        if (stack == null || stack.getType() != Material.POTION
                || !stack.hasItemMeta()) {
            return false;
        }
        if (stack.getItemMeta() instanceof PotionMeta meta) {
            return meta.hasBasePotionType() && type.equals(meta.getBasePotionType());
        }
        return false;
    }

    /** @return the drinkable potion's base type, when it carries one. */
    public static @Nullable PotionType baseType(@Nullable ItemStack stack) {
        if (stack == null || stack.getType() != Material.POTION
                || !stack.hasItemMeta()) {
            return null;
        }
        if (stack.getItemMeta() instanceof PotionMeta meta
                && meta.hasBasePotionType()) {
            return meta.getBasePotionType();
        }
        return null;
    }

    /**
     * Applies the potion's own effects to the entity, boosted when the
     * drinker holds a POWER unlock for the potion type. An overriding
     * add (not a stack): vanilla's application of the consumed item is
     * replaced by the boosted values.
     *
     * @param boost the holder's matching POWER unlock, or null for none
     */
    public static void apply(@NotNull LivingEntity entity,
            @NotNull ItemStack potionStack, @Nullable UnlockDefinition boost) {
        if (!(potionStack.getItemMeta() instanceof PotionMeta meta)) {
            return;
        }
        for (PotionEffect effect : meta.getAllEffects()) {
            PotionEffect boosted = boost == null ? effect
                    : boost(effect, boost);
            entity.addPotionEffect(boosted);
        }
    }

    /** Boosts one effect through the pure math. */
    static @NotNull PotionEffect boost(@NotNull PotionEffect effect,
            @NotNull UnlockDefinition definition) {
        PotionBoost.Boost values = PotionBoost.boost(effect.getAmplifier(),
                effect.getDuration(), definition);
        return effect.withDuration(values.durationTicks())
                .withAmplifier(values.amplifier());
    }
}
