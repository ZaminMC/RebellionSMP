package net.zaminmc.rebellion.items;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RebellionItems' bundled messages.yml is the complete player-feedback
 * contract (ADR-0012). These tests pin it: every key the command layer
 * renders must exist, the whole file must strict-parse (explicitly closed
 * MiniMessage tags), the load path must extract it into the data folder, and
 * escaped angle brackets must survive as literal text.
 */
final class ItemsMessagesTest {

    private static final Logger LOG = Logger.getLogger("test");

    /** Every key ItemsCommand, MenuCommandBridge and MenuHost render. */
    private static final List<String> KEYS = List.of(
            "prefix",
            "common.no-permission",
            "usage",
            "inspect.player-only", "inspect.none", "inspect.header", "inspect.line",
            "annotate.player-only", "annotate.hold-stack", "annotate.success",
            "annotate.rejected",
            "give.usage", "give.unknown-item", "give.player-offline",
            "give.console-needs-player", "give.material-missing", "give.success",
            "audit.usage", "audit.player-offline", "audit.header", "audit.summary",
            "audit.line", "audit.per-rarity", "audit.none",
            "reload.success", "reload.failed", "reload.issue", "reload.menu-commands",
            "status.header", "status.config", "status.counts", "status.module",
            "menu.player-only", "menu.usage", "menu.unknown", "menu.unavailable",
            "menu.console-denied");

    private static YamlConfiguration bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ItemsModuleState.class.getResourceAsStream("/messages.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return yaml;
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(c);
    }

    @Test
    void bundledFileStrictParsesAndCoversEveryRenderedKey() {
        MessageCatalog.LoadResult result = MessageCatalog.parse(bundled());
        assertTrue(result.valid(), () -> result.issues().toString());
        MessageCatalog catalog = result.catalog();
        for (String key : KEYS) {
            assertNotNull(catalog.template(key), "messages.yml is missing key " + key);
        }
    }

    @Test
    void usageTemplatesRenderLiteralAngleBrackets() {
        MessageCatalog catalog = MessageCatalog.parse(bundled()).catalog();
        String usage = plain(catalog.raw("usage"));
        assertEquals("Usage: /rebellionitems <inspect|annotate|give|audit|reload|status>", usage);
        assertTrue(plain(catalog.raw("menu.usage")).contains("/rebellionmenu <menu-id>"));
        assertTrue(plain(catalog.raw("give.usage")).contains("<item-id>"));
    }

    @Test
    void loadExtractsBundledFileIntoDataFolder(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        MessageCatalog.LoadResult result =
                MessageCatalog.load(file, "messages.yml", ItemsModuleState.class, LOG);
        assertTrue(Files.exists(file), "bundled messages.yml must be extracted on first boot");
        assertTrue(result.valid(), () -> result.issues().toString());
        // The extracted file is byte-identical to the bundled resource: admin
        // edits start from exactly what shipped.
        assertEquals(Files.readString(file),
                new String(java.util.Objects.requireNonNull(
                        ItemsModuleState.class.getResourceAsStream("/messages.yml"))
                        .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
