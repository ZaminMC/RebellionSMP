package net.zaminmc.rebellion.alchemy.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.zaminmc.rebellion.alchemy.CostItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Pure cost math over plain {@code ItemStack} arrays (ADR-0020 §5) — the
 * ADR-0017 {@code Ingredients} discipline applied to unlock purchases.
 * Only {@code getType()}/{@code getAmount()} are read — no ItemMeta, no
 * registry — so the purchase flow's inventory half is testable serverless.
 *
 * <p>Efficiency's {@code cost-reduction} is applied here, as pure math
 * over the definition's cost list: every amount is scaled and <em>floored
 * at 1</em> — a reduction can lower a price but never mint a free
 * component, so no discount can make an unlock costless.</p>
 *
 * <p>Scope: storage contents only. Armor and off-hand slots are excluded
 * deliberately — a purchase must never eat worn gear or the held
 * shield.</p>
 */
public final class Costs {

    private Costs() {
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

    /** @return true when every cost item is present in full. */
    public static boolean hasAll(@NotNull ItemStack[] contents,
            @NotNull List<CostItem> required) {
        for (CostItem item : required) {
            if (count(contents, item.material()) < item.amount()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies the efficiency cost reduction to a definition's cost list
     * (pure). Amounts are scaled down and floored at 1; the result keeps
     * definition order.
     *
     * @param reduction the holder's summed cost-reduction fraction, 0..&lt;1
     *        (clamped defensively)
     */
    public static @NotNull List<CostItem> reduced(@NotNull List<CostItem> cost,
            double reduction) {
        double clamped = Math.min(Math.max(reduction, 0.0), 0.99);
        List<CostItem> reduced = new ArrayList<>(cost.size());
        for (CostItem item : cost) {
            int amount = (int) Math.round(item.amount() * (1.0 - clamped));
            reduced.add(new CostItem(item.material(), Math.max(1, amount)));
        }
        return List.copyOf(reduced);
    }

    /**
     * Builds the removal plan for {@code Inventory.removeItem}: material to
     * total amount, merged across the cost list.
     */
    public static @NotNull Map<Material, Integer> removalPlan(
            @NotNull List<CostItem> cost) {
        Map<Material, Integer> plan = new java.util.HashMap<>();
        for (CostItem item : cost) {
            plan.merge(item.material(), item.amount(), Integer::sum);
        }
        return plan;
    }

    /** @return the still-missing part of the cost list ("gold_ingot x2"). */
    public static @NotNull List<String> missing(@NotNull ItemStack[] contents,
            @NotNull List<CostItem> cost) {
        List<String> missing = new ArrayList<>();
        for (CostItem item : cost) {
            int held = count(contents, item.material());
            if (held < item.amount()) {
                missing.add(item.material().name().toLowerCase(
                        Locale.ROOT) + " x" + (item.amount() - held));
            }
        }
        return List.copyOf(missing);
    }

    /** @return the human cost summary ("gold_ingot x4, diamond x2"). */
    public static @NotNull String describe(@NotNull List<CostItem> cost) {
        StringBuilder sb = new StringBuilder();
        for (CostItem item : cost) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(item.material().name().toLowerCase(Locale.ROOT))
                    .append(" x").append(item.amount());
        }
        return sb.toString();
    }
}
