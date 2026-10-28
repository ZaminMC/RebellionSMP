package net.zaminmc.rebellion.alchemy;

import org.bukkit.Material;

/**
 * One cost item of an unlock definition: an item material plus an amount
 * (ADR-0019 §5). Materials are validated registry-free — the ADR-0015
 * serverless lesson, identical to the RPG ingredient contract.
 *
 * @param material the item material (never air)
 * @param amount 1..MAX_AMOUNT
 */
public record CostItem(Material material, int amount) {

    public static final int MAX_AMOUNT = 64;

    public CostItem {
        if (material == null || material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            throw new IllegalArgumentException("cost material must be a valid "
                    + "item material (got " + material + ")");
        }
        if (amount < 1 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("cost amount must be 1.."
                    + MAX_AMOUNT + " (got " + amount + ")");
        }
    }
}
