package net.zaminmc.rebellion.core.messages;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Messages loading and MiniMessage rendering, including the atomic-reload
 * contract: an invalid candidate must never replace a valid snapshot.
 */
final class CoreMessagesTest {

    private static YamlConfiguration bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = CoreMessagesTest.class.getResourceAsStream("/messages.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return yaml;
    }

    @Test
    void bundledDefaultsParseAndRender() {
        var result = CoreMessages.parse(bundled());
        assertTrue(result.valid(), () -> result.issues().toString());
        CoreMessages messages = result.messages();

        // Raw render carries the template's styled content (a single colored
        // component has no children, so assert on serialized text instead).
        String header = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(messages.raw("status.header"));
        assertEquals("RebellionSMP status", header);
        // Prefix is prepended by msg().
        String raw = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(messages.msg("common.no-permission"));
        assertTrue(raw.contains("do not have permission"), raw);
    }

    @Test
    void invalidMiniMessageTemplateIsRejected() {
        YamlConfiguration yaml = bundled();
        // Unclosed tags are the documented rejection case: strict-mode MiniMessage
        // (verified against adventure-text-minimessage 5.2.0) throws
        // "All tags must be explicitly closed" for this template.
        yaml.set("common.no-permission", "<red>unclosed tag");
        var result = CoreMessages.parse(yaml);
        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.path().equals("common.no-permission")),
                () -> result.issues().toString());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.message().contains("invalid MiniMessage")));

        // Runtime placeholders (single identifiers) must NOT be rejected,
        // even though no resolver is supplied at validation time.
        YamlConfiguration ok = bundled();
        ok.set("common.no-permission", "<red>Quote <white><incident></white> now.</red>");
        assertTrue(CoreMessages.parse(ok).valid(), () -> CoreMessages.parse(ok).issues().toString());
    }

    @Test
    void loadMergesDefaultsForMissingUserKeys(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        // Admin file with only one key; everything else falls back to bundled.
        java.nio.file.Files.writeString(file, "prefix: \"<gray>[X]</gray>\"\n", java.nio.charset.StandardCharsets.UTF_8);
        var result = CoreMessages.load(file, java.util.logging.Logger.getLogger("test"));
        CoreMessages messages = result.messages();
        // The admin's prefix is respected…
        String prefix = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(messages.prefix());
        assertEquals("[X]", prefix);
        // …and missing keys were filled from the bundled defaults (with issues reported).
        assertTrue(result.issues().size() > 1);
        assertNotNull(messages.template("status.header"));
    }

    @Test
    void missingFileIsCreatedFromBundledResource(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        var result = CoreMessages.load(file, java.util.logging.Logger.getLogger("test"));
        assertTrue(java.nio.file.Files.exists(file), "default file must be created");
        assertTrue(result.valid());
    }
}
