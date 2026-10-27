package net.zaminmc.rebellion.items.command;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * RebellionItems' placeholder contribution: pure in-memory rarity lookups,
 * junk input rejected, unknown parameters handed back to the router.
 */
final class ItemsPlaceholdersTest {

    private static net.zaminmc.rebellion.items.rarity.RarityRegistry registry() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ItemsPlaceholdersTest.class.getResourceAsStream("/rarities.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var loaded = new net.zaminmc.rebellion.items.rarity.RarityLoader(
                name -> true).load(yaml);
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        return loaded.registry();
    }

    @Test
    void rarityOfConfiguredMaterialResolves() throws Exception {
        ItemsPlaceholders placeholders = new ItemsPlaceholders(registry());
        assertEquals("epic", placeholders.resolve("rarity_DIAMOND_SWORD"));
        assertEquals("mythic", placeholders.resolve("rarity_DRAGON_EGG"));
        // Case-insensitive in PAPI usage.
        assertEquals("epic", placeholders.resolve("rarity_diamond_sword"));
    }

    @Test
    void rarityOfUnknownMaterialFallsBackToCommon() throws Exception {
        ItemsPlaceholders placeholders = new ItemsPlaceholders(registry());
        assertEquals("common", placeholders.resolve("rarity_DIRT"));
        assertEquals("common", placeholders.resolve("rarity_SOME_FUTURE_BLOCK"));
    }

    @Test
    void junkInputIsRejected() throws Exception {
        ItemsPlaceholders placeholders = new ItemsPlaceholders(registry());
        assertNull(placeholders.resolve("rarity_"));
        assertNull(placeholders.resolve("rarity_<script>"));
        assertNull(placeholders.resolve("rarity_DIAMOND SWORD"));
        assertNull(placeholders.resolve("rarity_D%ROP"));
    }

    @Test
    void foreignParametersAreNotConsumed() throws Exception {
        ItemsPlaceholders placeholders = new ItemsPlaceholders(registry());
        // Parameters owned by other modules must pass through as null.
        assertNull(placeholders.resolve("team_name"));
        assertNull(placeholders.resolve("dragon_owned"));
        assertNull(placeholders.resolve(""));
        assertNull(placeholders.resolve("rarity"));
    }
}
