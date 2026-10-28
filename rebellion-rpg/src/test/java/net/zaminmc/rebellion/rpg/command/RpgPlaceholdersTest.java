package net.zaminmc.rebellion.rpg.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.rpg.RpgPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure halves of the discovery placeholder: the parameter shape
 * ({@code ritual_<key>_discovered}) and the localized state wording the
 * placeholder renders into menus/PAPI text. The viewer-scoped resolution
 * itself is a live path over the join-loaded cache (RpgCacheListener +
 * RpgPlugin) and is exercised by the boot smoke; everything decidable
 * without a viewer is pinned here.
 */
final class RpgPlaceholdersTest {

    private static MessageCatalog bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = RpgPlugin.class.getResourceAsStream("/messages.yml")) {
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
    void parseKeyAcceptsTheShippedKnowledgeShape() {
        // The shipped knowledge key (granted by soul_lore / wandering_soul).
        assertEquals("ritual_soul_communion",
                RpgPlaceholders.parseKey("ritual_ritual_soul_communion_discovered"));
        // Params matching is case-insensitive on the fixed scaffolding.
        assertEquals("tracking", RpgPlaceholders.parseKey("RITUAL_tracking_DISCOVERED"));
        // 32 chars is the boundary the schema/validation allows.
        assertEquals("k".repeat(32), RpgPlaceholders.parseKey("ritual_" + "k".repeat(32)
                + "_discovered"));
    }

    @Test
    void parseKeyRejectsEverythingElse() {
        assertNull(RpgPlaceholders.parseKey("team_x_discovered"), "wrong prefix");
        assertNull(RpgPlaceholders.parseKey("ritual_foo"), "missing _discovered suffix");
        assertNull(RpgPlaceholders.parseKey("ritual__discovered"), "empty key");
        assertNull(RpgPlaceholders.parseKey("ritual_b$d_discovered"), "invalid charset");
        assertNull(RpgPlaceholders.parseKey("ritual_" + "k".repeat(33) + "_discovered"),
                "key longer than 32 chars");
        assertNull(RpgPlaceholders.parseKey("ritual__discovered_extra"), "trailing junk");
        assertNull(RpgPlaceholders.parseKey(""), "empty params");
    }

    @Test
    void stateWordReusesTheRitualListVocabulary() {
        MessageCatalog catalog = bundled();
        // Same template /ritual list renders — one vocabulary, operator-tunable.
        assertEquals(catalog.template("ritual.list.state.known"),
                RpgPlaceholders.stateWord(true, catalog));
        assertEquals(catalog.template("ritual.list.state.unknown"),
                RpgPlaceholders.stateWord(false, catalog));
        // The bundled wording is what menus actually substitute.
        assertEquals("<green>known</green>", RpgPlaceholders.stateWord(true, catalog));
        assertEquals("<aqua>undiscovered</aqua>", RpgPlaceholders.stateWord(false, catalog));
    }

    @Test
    void stateWordIsNullWhenTheCatalogHasNoTemplate() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString("prefix: \"\"\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        MessageCatalog.LoadResult result = MessageCatalog.parse(yaml);
        if (!result.valid()) {
            throw new IllegalStateException(result.issues().toString());
        }
        assertNull(RpgPlaceholders.stateWord(true, result.catalog()),
                "a missing template must leave the token untouched, not invent wording");
    }
}
