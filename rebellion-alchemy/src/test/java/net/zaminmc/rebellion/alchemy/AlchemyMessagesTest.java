package net.zaminmc.rebellion.alchemy;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RebellionAlchemy's bundled messages.yml is the module's complete
 * player-feedback contract (ADR-0012). These tests pin it: every key the
 * commands, listeners and controller render must exist and the whole file
 * must strict-parse (explicitly closed MiniMessage tags).
 */
final class AlchemyMessagesTest {

    private static final Logger LOG = Logger.getLogger("test");

    /** Every key the AlchemyPlugin surface renders. */
    private static final List<String> KEYS = List.of(
            "prefix",
            "common.no-permission", "common.unavailable",
            "usage", "player-only",
            "brewing.status.line", "brewing.status.maxed",
            "brewing.status.unlocks", "brewing.status.rules",
            "brewing.unlocks.header", "brewing.unlocks.line",
            "brewing.unlocks.state.owned", "brewing.unlocks.state.unowned",
            "brewing.unlocks.state.disabled",
            "brewing.unlock.usage", "brewing.unlock.unknown",
            "brewing.unlock.disabled", "brewing.unlock.missing",
            "brewing.unlock.ok", "brewing.unlock.already-owned",
            "brewing.unlock.repeated", "brewing.unlock.removed-mid-purchase",
            "brewing.unlock.level-too-low", "brewing.unlock.prerequisite",
            "brewing.unlock.owned", "brewing.unlock.reservation-held",
            "brewing.rules.usage", "brewing.rules.header", "brewing.rules.none",
            "brewing.rules.line", "brewing.rules.state.active",
            "brewing.rules.state.paused", "brewing.rules.unknown-id",
            "brewing.rules.add.usage", "brewing.rules.add.bad-trigger",
            "brewing.rules.add.bad-potion", "brewing.rules.add.bad-threshold",
            "brewing.rules.add.bad-storage", "brewing.rules.add.ok",
            "brewing.rules.add.refused.trigger", "brewing.rules.add.refused.potion",
            "brewing.rules.add.refused.threshold",
            "brewing.rules.add.refused.cooldown",
            "brewing.rules.add.refused.priority", "brewing.rules.add.refused.cap",
            "brewing.rules.toggle.paused", "brewing.rules.toggle.resumed",
            "brewing.rules.remove.ok", "brewing.rules.remove.noop",
            "bag.title", "bag.unknown", "bag.locked", "bag.nesting",
            "auto.fired",
            "admin.usage", "admin.events-usage", "admin.events-header",
            "admin.events-line", "admin.events-none", "admin.player-unknown",
            "admin.grant-usage", "admin.grant.ok", "admin.grant.noop",
            "admin.grant.unknown", "admin.bag-usage", "admin.bag-online-only",
            "admin.bag-unknown", "admin.bag.minted", "admin.bag.received");

    private static YamlConfiguration bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = AlchemyPlugin.class.getResourceAsStream("/messages.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return yaml;
    }

    @Test
    void bundledFileStrictParsesAsACatalog() {
        MessageCatalog.LoadResult result = MessageCatalog.parse(bundled());
        assertTrue(result.valid(), () -> result.issues().toString());
    }

    @Test
    void everyRenderedKeyExists() {
        YamlConfiguration yaml = bundled();
        for (String key : KEYS) {
            assertNotNull(yaml.getString(key), "missing message key: " + key);
        }
    }

    @Test
    void everyRenderedKeyStrictParses() {
        for (String key : KEYS) {
            YamlConfiguration single = new YamlConfiguration();
            single.set("k", bundled().getString(key));
            MessageCatalog.LoadResult result = MessageCatalog.parse(single);
            assertTrue(result.valid(), "key '" + key + "': " + result.issues());
        }
    }

    @Test
    void loadingThroughTheRealFileLoaderRoundTrips(@TempDir Path dir)
            throws Exception {
        // The same path the plugin takes (bundled resource -> data folder
        // -> catalog): first load extracts, second load reads the
        // extracted file unchanged.
        MessageCatalog.LoadResult first = MessageCatalog.load(
                dir.resolve("messages.yml"), "messages.yml",
                AlchemyPlugin.class, LOG);
        assertTrue(first.valid(), () -> first.issues().toString());
        MessageCatalog.LoadResult second = MessageCatalog.load(
                dir.resolve("messages.yml"), "messages.yml",
                AlchemyPlugin.class, LOG);
        assertTrue(second.valid(), () -> second.issues().toString());
        MessageCatalog catalog = second.catalog();
        for (String key : KEYS) {
            assertNotNull(catalog.template(key), "missing key via loader: " + key);
        }
    }
}
