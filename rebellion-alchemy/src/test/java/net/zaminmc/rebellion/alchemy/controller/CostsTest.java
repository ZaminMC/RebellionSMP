package net.zaminmc.rebellion.alchemy.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.zaminmc.rebellion.alchemy.CostItem;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure cost math (ADR-0020 §5): the efficiency cost-reduction
 * (floored at 1 — a discount can never mint a free component), the
 * removal plan and the human summary. The ItemStack-array halves
 * ({@code count}/{@code hasAll}/{@code missing}) are live paths
 * ({@code ItemStack} construction routes through RegistryAccess, proven
 * unreachable off-server — the ADR-0015 lesson) and are exercised by the
 * boot smoke.
 */
final class CostsTest {

    private static final List<CostItem> PRICE = List.of(
            new CostItem(Material.AMETHYST_SHARD, 8),
            new CostItem(Material.GOLD_INGOT, 4));

    @Test
    void reductionScalesAmountsAndKeepsOrder() {
        List<CostItem> reduced = Costs.reduced(PRICE, 0.25);
        assertEquals(Material.AMETHYST_SHARD, reduced.get(0).material());
        assertEquals(6, reduced.get(0).amount());
        assertEquals(Material.GOLD_INGOT, reduced.get(1).material());
        assertEquals(3, reduced.get(1).amount());
    }

    @Test
    void reductionFloorsAtOneItem() {
        // 4 gold * 0.5 -> 2; 4 gold * 0.75 -> 1; but a 90% cut on 4 would
        // be 0.4 -> floored at 1, never free.
        assertEquals(1, Costs.reduced(PRICE, 0.90).get(1).amount());
        assertEquals(1, Costs.reduced(
                List.of(new CostItem(Material.DIAMOND, 2)), 0.99).get(0).amount());
    }

    @Test
    void zeroReductionIsIdentity() {
        assertEquals(PRICE, Costs.reduced(PRICE, 0.0));
        // Defensive clamp: negative or >= 1 inputs never explode or free.
        assertEquals(PRICE, Costs.reduced(PRICE, -0.5));
        assertEquals(1, Costs.reduced(PRICE, 2.0).get(0).amount());
    }

    @Test
    void removalPlanMergesDuplicateMaterials() {
        List<CostItem> cost = List.of(new CostItem(Material.GOLD_INGOT, 4),
                new CostItem(Material.GOLD_INGOT, 3),
                new CostItem(Material.DIAMOND, 2));
        Map<Material, Integer> plan = Costs.removalPlan(cost);
        assertEquals(7, plan.get(Material.GOLD_INGOT));
        assertEquals(2, plan.get(Material.DIAMOND));
        assertEquals(2, plan.size());
    }

    @Test
    void describeIsLowercaseHumanReadable() {
        assertEquals("amethyst_shard x8, gold_ingot x4", Costs.describe(PRICE));
        assertEquals("", Costs.describe(List.of()));
    }

    @Test
    void reducedNeverExceedsTheBasePrice() {
        for (double reduction : new double[] {0.0, 0.10, 0.25, 0.5, 0.9, 0.99}) {
            List<CostItem> reduced = Costs.reduced(PRICE, reduction);
            for (int i = 0; i < PRICE.size(); i++) {
                assertTrue(reduced.get(i).amount() <= PRICE.get(i).amount(),
                        "reduction must never raise a price");
            }
        }
    }
}
