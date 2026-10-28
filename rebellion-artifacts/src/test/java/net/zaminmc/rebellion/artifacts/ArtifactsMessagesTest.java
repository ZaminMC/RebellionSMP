package net.zaminmc.rebellion.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RebellionArtifacts' bundled messages.yml is the module's complete
 * player-feedback contract (ADR-0012). These tests pin it: every key the
 * commands, listeners, sweep, observer and purge executor render must
 * exist, the whole file must strict-parse (explicitly closed MiniMessage
 * tags), the load path must extract it into the data folder, and the
 * literal-bracket escapes in usage lines must survive as text.
 */
final class ArtifactsMessagesTest {

    private static final Logger LOG = Logger.getLogger("test");

    /** Every key the ArtifactsPlugin surface renders. */
    private static final List<String> KEYS = List.of(
            "prefix",
            "common.no-permission", "common.unavailable",
            "usage", "player-only",
            "artifact.overworld.short", "artifact.nether.short",
            "artifact.end.short",
            "item.overworld.name", "item.overworld.lore",
            "item.nether.name", "item.nether.lore",
            "item.end.name", "item.end.lore",
            "state.origin", "state.held", "state.lost",
            "state.returning", "state.spent",
            "status.header", "status.line", "status.event",
            "origin.presentation-name", "origin.not-origin",
            "origin.inventory-full", "origin.captured", "origin.capture-refused",
            "flow.gained", "flow.craft-refused",
            "event.triggered", "event.join-notice", "event.reminder",
            "event.completed",
            "return.none-held", "return.confirm", "return.ok", "return.refused",
            "spend.no-event", "spend.not-holder", "spend.confirm", "spend.ok",
            "spend.refused",
            "purge.disabled", "purge.no-event", "purge.not-holder",
            "purge.too-early", "purge.already-executed", "purge.confirm",
            "purge.warning", "purge.executed",
            "admin.usage", "admin.list-header", "admin.info",
            "admin.artifact-required",
            "admin.route-usage", "admin.target-offline", "admin.route-ok",
            "admin.route-refused", "admin.audit-header", "admin.audit-empty",
            "admin.audit-line", "admin.complete-confirm", "admin.complete-ok",
            "admin.events-header", "admin.events-line");

    private static YamlConfiguration bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ArtifactsPlugin.class.getResourceAsStream("/messages.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
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
            assertNotNull(catalog.template(key), "missing key: " + key);
        }
        // No dead keys: everything in the file is rendered somewhere.
        assertEquals(KEYS.size(), countLeafKeys(bundled()),
                "the catalog and the pinned key list must match 1:1");
    }

    @Test
    void stateNamesRenderAsReadableText() {
        MessageCatalog catalog = MessageCatalog.parse(bundled()).catalog();
        assertTrue(plain(catalog.raw("state.origin")).contains("origin"));
        assertTrue(plain(catalog.raw("state.spent")).contains("spent"));
    }

    @Test
    void usageLineKeepsLiteralSubcommandBrackets() {
        MessageCatalog catalog = MessageCatalog.parse(bundled()).catalog();
        String usage = plain(catalog.raw("usage"));
        assertTrue(usage.contains("<status|return|spend|purge>"),
                "escaped brackets must render literally: " + usage);
    }

    @Test
    void loadExtractsTheBundledFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("messages.yml");
        MessageCatalog.LoadResult result = MessageCatalog.load(file,
                "messages.yml", ArtifactsPlugin.class, LOG);
        assertTrue(Files.exists(file), "first load must extract the bundled file");
        assertTrue(result.valid(), () -> result.issues().toString());
        for (String key : KEYS) {
            assertNotNull(result.catalog().template(key), "missing key: " + key);
        }
    }

    private static int countLeafKeys(YamlConfiguration yaml) {
        return countLeafKeys("", yaml);
    }

    private static int countLeafKeys(String prefix,
            org.bukkit.configuration.ConfigurationSection section) {
        int count = 0;
        for (var entry : section.getValues(false).entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey()
                    : prefix + "." + entry.getKey();
            if (entry.getValue()
                    instanceof org.bukkit.configuration.ConfigurationSection child) {
                count += countLeafKeys(key, child);
            } else if (entry.getValue() != null) {
                count++;
            }
        }
        return count;
    }
}
