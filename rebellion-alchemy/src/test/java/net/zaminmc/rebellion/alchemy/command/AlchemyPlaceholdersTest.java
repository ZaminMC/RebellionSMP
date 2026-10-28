package net.zaminmc.rebellion.alchemy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.zaminmc.rebellion.alchemy.AlchemyPlugin;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure halves of the alchemy placeholders (ADR-0020 §5): the
 * {@code brewing_unlock_<key>} parameter shape and the localized
 * owned/unowned wording that menus and PAPI consumers render. The
 * viewer-scoped resolution itself is a live path over the join-loaded
 * cache (AlchemyCacheListener + AlchemyPlugin) and is exercised by the
 * boot smoke; everything decidable without a viewer is pinned here.
 */
final class AlchemyPlaceholdersTest {

    private static MessageCatalog bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = AlchemyPlugin.class.getResourceAsStream("/messages.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        MessageCatalog.LoadResult result = MessageCatalog.parse(yaml);
        if (!result.valid()) {
            throw new IllegalStateException(result.issues().toString());
        }
        return result.catalog();
    }

    @Test
    void parseUnlockKeyAcceptsTheShippedShape() {
        // The shipped unlock keys.
        assertEquals("potent_swiftness",
                AlchemyPlaceholders.parseUnlockKey("brewing_unlock_potent_swiftness"));
        assertEquals("frugal_brewing",
                AlchemyPlaceholders.parseUnlockKey("BREWING_UNLOCK_frugal_brewing"),
                "parameter matching is case-insensitive on the fixed scaffolding");
        // 32 chars is the boundary the schema validation allows.
        assertEquals("k".repeat(32),
                AlchemyPlaceholders.parseUnlockKey("brewing_unlock_" + "k".repeat(32)));
    }

    @Test
    void parseUnlockKeyRejectsEverythingElse() {
        assertNull(AlchemyPlaceholders.parseUnlockKey("brewing_level"),
                "the numeric placeholders are handled separately");
        assertNull(AlchemyPlaceholders.parseUnlockKey("brewing_unlock_"),
                "empty key");
        assertNull(AlchemyPlaceholders.parseUnlockKey("brewing_unlock_b$d"),
                "invalid charset");
        assertNull(AlchemyPlaceholders.parseUnlockKey("unlock_potent_swiftness"),
                "missing prefix");
        assertNull(AlchemyPlaceholders.parseUnlockKey(
                "brewing_unlock_" + "k".repeat(33)), "key longer than 32 chars");
        assertNull(AlchemyPlaceholders.parseUnlockKey(""), "empty params");
    }

    @Test
    void ownedWordReusesTheUnlocksListVocabulary() {
        MessageCatalog catalog = bundled();
        // Same template /brewing unlocks renders — one vocabulary, operator
        // -tunable. A missing template yields null (token stays raw).
        assertEquals(catalog.template("brewing.unlocks.state.owned"),
                AlchemyPlaceholders.ownedWord(true, catalog));
        assertEquals(catalog.template("brewing.unlocks.state.unowned"),
                AlchemyPlaceholders.ownedWord(false, catalog));
        // The bundled wording is what menus actually substitute.
        assertEquals("<green>owned</green>",
                AlchemyPlaceholders.ownedWord(true, catalog));
        assertEquals("<aqua>available</aqua>",
                AlchemyPlaceholders.ownedWord(false, catalog));
    }

    @Test
    void missingTemplateLeavesTheTokenHonest() {
        MessageCatalog empty = MessageCatalog.parse(new YamlConfiguration())
                .catalog();
        assertNull(AlchemyPlaceholders.ownedWord(true, empty),
                "no template configured -> null -> the token stays raw text "
                        + "instead of lying about the viewer's state");
    }
}
