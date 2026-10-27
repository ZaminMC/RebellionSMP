package net.zaminmc.rebellion.items.audit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.zaminmc.rebellion.items.identity.ItemIdentity;

/**
 * Server-free audit planner for the admin inventory audit view
 * ({@code /rebellionitems audit <player>}).
 *
 * <p>Takes already-extracted identity records and produces the structured
 * report the command renders: one line per identity-tagged stack plus summary
 * counts (scanned stacks, tagged stacks, unique instances, per-rarity
 * histogram). Keeping this pure makes ordering, counting and histogram
 * behavior unit-testable without a live server.</p>
 */
public final class InventoryAudit {

    /** One reported line: a stack carrying a valid RebellionSMP identity. */
    public record TaggedLine(String container, int slot, String material, int amount,
            String rarityKey, String itemId, String instanceId, String talismanTierKey) {
    }

    /**
     * @param tagged        tagged stacks in visit order (stable: armor, then
     *                      offhand, then storage, then ender chest — as
     *                      supplied by the caller)
     * @param scannedStacks non-air stacks visited, tagged or not
     * @param taggedStacks  stacks with a parsed (valid) identity
     * @param uniqueStacks  tagged stacks carrying an instance id
     * @param byRarity      tagged stack count per rarity key, insertion order
     *                      by first appearance
     */
    public record Report(List<TaggedLine> tagged, int scannedStacks, int taggedStacks,
            int uniqueStacks, Map<String, Integer> byRarity) {
    }

    private InventoryAudit() {
    }

    /** @return report over the visited entries; never null */
    public static Report of(Collection<AuditEntry> entries) {
        List<TaggedLine> tagged = new ArrayList<>();
        Map<String, Integer> byRarity = new LinkedHashMap<>();
        int scanned = 0;
        int unique = 0;
        for (AuditEntry e : entries) {
            scanned++;
            ItemIdentity id = e.identity();
            if (id == null) {
                continue;
            }
            tagged.add(new TaggedLine(e.container(), e.slot(), e.material(), e.amount(),
                    id.rarityKey(), id.itemId(),
                    id.instanceId() == null ? null : id.instanceId().toString(),
                    id.talismanTierKey()));
            byRarity.merge(id.rarityKey(), 1, Integer::sum);
            if (id.instanceId() != null) {
                unique++;
            }
        }
        return new Report(List.copyOf(tagged), scanned, tagged.size(), unique,
                java.util.Collections.unmodifiableMap(byRarity));
    }
}
