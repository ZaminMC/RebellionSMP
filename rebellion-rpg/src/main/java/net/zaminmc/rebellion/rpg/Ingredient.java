package net.zaminmc.rebellion.rpg;

import org.bukkit.Material;

/**
 * One ingredient of a ritual definition: an item material plus an amount.
 * Materials are validated registry-free (enum-constant comparison only —
 * the ADR-0015 serverless lesson: {@code Material.isItem()} routes through
 * RegistryAccess, which does not exist off-server).
 *
 * @param material the item material (never air)
 * @param amount 1..64
 */
public record Ingredient(Material material, int amount) {

    public static final int MAX_AMOUNT = 64;

    public Ingredient {
        if (material == null || material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            throw new IllegalArgumentException("ingredient material must be a "
                    + "valid item material (got " + material + ")");
        }
        if (amount < 1 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("ingredient amount must be "
                    + "1.." + MAX_AMOUNT + " (got " + amount + ")");
        }
    }
}
