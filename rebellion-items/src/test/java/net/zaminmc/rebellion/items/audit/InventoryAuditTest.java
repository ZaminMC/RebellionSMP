package net.zaminmc.rebellion.items.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.items.identity.ItemIdentity;
import org.junit.jupiter.api.Test;

/**
 * Audit planner: visit order, tagged/untagged accounting, unique-instance
 * counting, rarity histogram, and entry validation.
 */
final class InventoryAuditTest {

    private static ItemIdentity identity(String rarity) {
        return ItemIdentity.parse(1, rarity, null, null, null).orElseThrow();
    }

    private static ItemIdentity uniqueIdentity(String rarity, String itemId) {
        return ItemIdentity.parse(1, rarity, itemId, UUID.randomUUID().toString(), "normal")
                .orElseThrow();
    }

    @Test
    void countsScannedSeparatelyFromTagged() {
        var report = InventoryAudit.of(List.of(
                new AuditEntry("inventory", 0, "STONE", 64, null),
                new AuditEntry("inventory", 1, "DIAMOND_SWORD", 1, identity("rare")),
                new AuditEntry("enderchest", 3, "PAPER", 12, null)));
        assertEquals(3, report.scannedStacks());
        assertEquals(1, report.taggedStacks());
        assertEquals(0, report.uniqueStacks());
        assertEquals(1, report.tagged().size());
        assertEquals("rare", report.tagged().get(0).rarityKey());
        assertEquals("DIAMOND_SWORD", report.tagged().get(0).material());
    }

    @Test
    void visitOrderIsPreserved() {
        var report = InventoryAudit.of(List.of(
                new AuditEntry("armor", 2, "NETHERITE_CHESTPLATE", 1, identity("epic")),
                new AuditEntry("offhand", 40, "SHIELD", 1, null),
                new AuditEntry("inventory", 7, "SUGAR", 3, uniqueIdentity("uncommon", "talisman_haste_normal")),
                new AuditEntry("enderchest", 26, "ECHO_SHARD", 1, uniqueIdentity("epic", "talisman_haste_relic"))));
        assertEquals(List.of("armor", "inventory", "enderchest"),
                report.tagged().stream().map(InventoryAudit.TaggedLine::container).toList());
        assertEquals(2, report.uniqueStacks());
        assertEquals("talisman_haste_normal", report.tagged().get(1).itemId());
        assertEquals("normal", report.tagged().get(1).talismanTierKey());
        // Instance ids are rendered in canonical form.
        assertEquals(report.tagged().get(2).instanceId(),
                report.tagged().get(2).instanceId().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void histogramMergesPerRarityInFirstSeenOrder() {
        var report = InventoryAudit.of(List.of(
                new AuditEntry("inventory", 0, "A", 1, identity("rare")),
                new AuditEntry("inventory", 1, "B", 1, identity("epic")),
                new AuditEntry("inventory", 2, "C", 1, identity("rare")),
                new AuditEntry("inventory", 3, "D", 1, identity("rare"))));
        assertEquals(2, report.byRarity().size());
        assertEquals(3, report.byRarity().get("rare"));
        assertEquals(1, report.byRarity().get("epic"));
        assertEquals(List.of("rare", "epic"), List.copyOf(report.byRarity().keySet()));
    }

    @Test
    void emptyVisitYieldsEmptyReport() {
        var report = InventoryAudit.of(List.of());
        assertEquals(0, report.scannedStacks());
        assertTrue(report.tagged().isEmpty());
        assertTrue(report.byRarity().isEmpty());
    }

    @Test
    void rejectsBlankContainerAndNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditEntry(" ", 0, "STONE", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditEntry("inventory", 0, "STONE", 0, null));
    }
}
