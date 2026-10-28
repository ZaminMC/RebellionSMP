package net.zaminmc.rebellion.rpg.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.zaminmc.rebellion.rpg.Ingredient;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Pure ingredient math over plain {@code ItemStack} arrays (ADR-0017).
 * Only {@code getType()} / {@code getAmount()} are read — no ItemMeta, no
 * server registry — so the claim protocol's inventory half is testable
 * serverless. The controller layers these onto the live inventory:
 * verification over the storage contents, removal through the sanctioned
 * {@code Inventory.removeItem} mutator, so the physical half of "reserve,
 * remove, commit" never touches stacks directly.
 *
 * <p>Scope: storage contents only. Armor and offhand slots are excluded
 * deliberately — a ritual must never eat a player's worn gear or the
 * shield they are holding.</p>
 */
public final class Ingredients {

    private Ingredients() {
    }

    /** @return the total held amount of a material across the contents. */
    public static int count(@NotNull ItemStack[] contents, @NotNull Material material) {
        int total = 0;
        for (ItemStack stack : contents) {
            if (stack != null && !stack.getType().isAir()
                    && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** @return true when every required reagent is present in full. */
    public static boolean hasAll(@NotNull ItemStack[] contents,
            @NotNull List<Ingredient> required) {
        for (Ingredient ingredient : required) {
            if (count(contents, ingredient.material()) < ingredient.amount()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds one removal stack per required reagent for
     * {@code Inventory.removeItem}. Callers must verify with
     * {@link #hasAll} first; the removal call reports any remainder as a
     * leftover map, which is the race detector for the impossible-in-practice
     * "inventory changed between verify and remove on the main thread"
     * window.
     *
     * @return material to amount requested, in definition order.
     */
    public static @NotNull Map<Material, Integer> removalPlan(
            @NotNull List<Ingredient> required) {
        Map<Material, Integer> plan = new HashMap<>();
        for (Ingredient ingredient : required) {
            plan.merge(ingredient.material(), ingredient.amount(), Integer::sum);
        }
        return plan;
    }
}
