package net.zaminmc.rebellion.items.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Identity parsing contract: versioned schemas, forged values fail closed.
 * Server-free by design — the Bukkit adapter delegates all decisions here.
 */
final class ItemIdentityTest {

    private static final String GOOD_INSTANCE = UUID.randomUUID().toString();

    @Test
    void validFullIdentityParses() {
        Optional<ItemIdentity> id = ItemIdentity.parse(1, "EPIC", "void_talisman",
                GOOD_INSTANCE, "artifact");
        assertTrue(id.isPresent());
        assertEquals(1, id.get().schema());
        assertEquals("epic", id.get().rarityKey());
        assertEquals("void_talisman", id.get().itemId());
        assertEquals(GOOD_INSTANCE, id.get().instanceId().toString());
        assertEquals("artifact", id.get().talismanTierKey());
    }

    @Test
    void minimalVanillaAnnotatedIdentityParses() {
        Optional<ItemIdentity> id = ItemIdentity.parse(1, "common", null, null, null);
        assertTrue(id.isPresent());
        assertNull(id.get().itemId());
        assertNull(id.get().instanceId());
        assertNull(id.get().talismanTierKey());
    }

    @Test
    void unknownFutureSchemaFailsClosed() {
        assertTrue(ItemIdentity.parse(2, "common", null, null, null).isEmpty());
        assertTrue(ItemIdentity.parse(99, "EPIC", "x", null, null).isEmpty());
    }

    @Test
    void zeroAndNegativeSchemasAreInvalid() {
        assertTrue(ItemIdentity.parse(0, "common", null, null, null).isEmpty());
        assertTrue(ItemIdentity.parse(-1, "common", null, null, null).isEmpty());
    }

    @Test
    void unknownRarityKeyFailsClosed() {
        assertTrue(ItemIdentity.parse(1, "ABSURD", null, null, null).isEmpty());
        assertTrue(ItemIdentity.parse(1, null, null, null, null).isEmpty());
    }

    @Test
    void forgedInstanceIdFailsClosed() {
        assertTrue(ItemIdentity.parse(1, "common", null, "not-a-uuid", null).isEmpty());
        // Missing group: not 8-4-4-4-12.
        assertTrue(ItemIdentity.parse(1, "common", null, "12345678-1234-1234-1234", null).isEmpty());
        // Last group has 11 chars instead of 12.
        assertTrue(ItemIdentity.parse(1, "common",
                null, "12345678-1234-1234-1234-12345678901", null).isEmpty());
        // A correctly shaped UUID is accepted — shape alone is not authorization,
        // but shape errors must fail closed.
        assertTrue(ItemIdentity.parse(1, "common",
                null, "12345678-1234-1234-1234-1234567890ab", null).isPresent());
    }

    @Test
    void malformedCustomItemIdFailsClosed() {
        // Uppercase rejected — ids are lowercase by contract.
        assertTrue(ItemIdentity.parse(1, "common", "BadId", null, null).isEmpty());
        // Spaces rejected.
        assertTrue(ItemIdentity.parse(1, "common", "bad id", null, null).isEmpty());
        // Path-ish input rejected.
        assertTrue(ItemIdentity.parse(1, "common", "../etc/passwd", null, null).isEmpty());
        // Empty rejected.
        assertTrue(ItemIdentity.parse(1, "common", "", null, null).isEmpty());
        // 65+ chars rejected.
        assertTrue(ItemIdentity.parse(1, "common", "a".repeat(65), null, null).isEmpty());
        // 64 chars accepted.
        assertTrue(ItemIdentity.parse(1, "common", "a".repeat(64), null, null).isPresent());
    }

    @Test
    void unknownTalismanTierFailsClosed() {
        assertTrue(ItemIdentity.parse(1, "common", null, null, "GOD").isEmpty());
        assertTrue(ItemIdentity.parse(1, "common", null, null, "relic").isPresent());
    }

    @Test
    void schemaAnnotationCheck() {
        assertTrue(ItemIdentity.isAnnotatedSchema(1));
        assertFalse(ItemIdentity.isAnnotatedSchema(0));
        assertFalse(ItemIdentity.isAnnotatedSchema(2));
        assertFalse(ItemIdentity.isAnnotatedSchema(-1));
    }

    @Test
    void keyNamesAreStable() {
        // These strings are a persisted wire format; never change existing keys.
        assertEquals("rebellion", ItemIdentity.NAMESPACE);
        assertEquals("schema", ItemIdentity.KEY_SCHEMA);
        assertEquals("rarity", ItemIdentity.KEY_RARITY);
        assertEquals("item_id", ItemIdentity.KEY_ITEM_ID);
        assertEquals("instance_id", ItemIdentity.KEY_INSTANCE_ID);
        assertEquals("talisman_tier", ItemIdentity.KEY_TALISMAN_TIER);
    }
}
