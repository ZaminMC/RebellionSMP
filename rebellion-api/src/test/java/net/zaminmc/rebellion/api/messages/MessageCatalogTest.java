package net.zaminmc.rebellion.api.messages;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MessageCatalog} loading, merging, validation and rendering — the
 * shared contract every module's messages.yml relies on. YamlConfiguration
 * and MiniMessage are server-free library types, so no live server is needed
 * (verified 2026-10-28; same approach as CoreMessagesTest).
 */
final class MessageCatalogTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static YamlConfiguration fixture() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = MessageCatalogTest.class.getResourceAsStream("/messages.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return yaml;
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(component);
    }

    @Test
    void bundledFixtureParsesStrictlyAndRenders() {
        MessageCatalog.LoadResult result = MessageCatalog.parse(fixture());
        assertTrue(result.valid(), () -> result.issues().toString());
        MessageCatalog catalog = result.catalog();
        assertEquals("Denied.", plain(catalog.raw("common.no-permission")));
        assertEquals("[Test] Denied.", plain(catalog.msg("common.no-permission")));
        // Runtime placeholder resolved at render time.
        assertEquals("Report spam to staff.",
                plain(catalog.raw("common.report", Placeholder.unparsed("detail", "spam"))));
        // Literal angle brackets survive as text, not tags.
        assertEquals("Usage: /test invite <player>", plain(catalog.raw("usage.player")));
    }

    @Test
    void parseRejectsUnclosedTagsAndKeepsPlaceholderTags() {
        YamlConfiguration bad = fixture();
        bad.set("common.no-permission", "<red>unclosed");
        MessageCatalog.LoadResult rejected = MessageCatalog.parse(bad);
        assertFalse(rejected.valid());
        assertNull(rejected.catalog(), "a rejected candidate must not yield a catalog");
        assertTrue(rejected.issues().stream()
                .anyMatch(i -> i.key().equals("common.no-permission")
                        && i.problem().contains("invalid MiniMessage")),
                () -> rejected.issues().toString());

        // Bare runtime placeholders must NOT be rejected even without resolvers.
        YamlConfiguration ok = fixture();
        ok.set("common.no-permission", "<red>Quote <white><incident></white> now.</red>");
        assertTrue(MessageCatalog.parse(ok).valid(),
                () -> MessageCatalog.parse(ok).issues().toString());
    }

    @Test
    void loadCreatesMissingFileFromBundledResource(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        MessageCatalog.LoadResult result = MessageCatalog.load(file, "messages.yml",
                MessageCatalogTest.class, LOG);
        assertTrue(Files.exists(file), "default file must be created");
        assertTrue(result.valid(), () -> result.issues().toString());
        assertEquals("Denied.", plain(result.catalog().raw("common.no-permission")));
    }

    @Test
    void loadNeverOverwritesAdminEditsAndFillsMissingKeys(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        Files.writeString(file, "prefix: \"<gray>[X] </gray>\"\ncommon:\n  no-permission: \"<red>No.</red>\"\n",
                java.nio.charset.StandardCharsets.UTF_8);
        MessageCatalog.LoadResult result = MessageCatalog.load(file, "messages.yml",
                MessageCatalogTest.class, LOG);
        MessageCatalog catalog = result.catalog();
        // Admin edits win…
        assertEquals("[X] No.", plain(catalog.msg("common.no-permission")));
        assertEquals("[X] ", plain(catalog.prefix()));
        // …and the file on disk keeps the admin's edit.
        assertTrue(Files.readString(file).contains("[X]"));
        // Missing keys fall back to bundled defaults and are reported.
        assertEquals("Usage: /test invite <player>", plain(catalog.raw("usage.player")));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.key().equals("usage.player")), () -> result.issues().toString());
    }

    @Test
    void loadFallsBackToDefaultForInvalidUserTemplate(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        Files.writeString(file, "common:\n  no-permission: \"<red>unclosed\"\n",
                java.nio.charset.StandardCharsets.UTF_8);
        MessageCatalog.LoadResult result = MessageCatalog.load(file, "messages.yml",
                MessageCatalogTest.class, LOG);
        // Boot must come up: the broken edit is reported and the bundled
        // default renders instead of lenient garbage.
        assertEquals("Denied.", plain(result.catalog().raw("common.no-permission")));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.key().equals("common.no-permission")
                        && i.problem().contains("using bundled default")),
                () -> result.issues().toString());
    }

    @Test
    void loadKeepsUserOnlyKeysAndRejectsIllegalResourceNames(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        Files.writeString(file, "custom:\n  extra: \"<green>Mine.</green>\"\n",
                java.nio.charset.StandardCharsets.UTF_8);
        MessageCatalog catalog = MessageCatalog
                .load(file, "messages.yml", MessageCatalogTest.class, LOG).catalog();
        assertEquals("Mine.", plain(catalog.raw("custom.extra")));
        // A crafted resource name can never steer extraction outside the data folder.
        assertThrows(MessageCatalog.MessageFileException.class, () ->
                MessageCatalog.load(tmp.resolve("x.yml"), "../secrets.yml",
                        MessageCatalogTest.class, LOG));
    }

    @Test
    void unknownKeyRendersAsItsOwnName() {
        MessageCatalog catalog = MessageCatalog.parse(fixture()).catalog();
        assertEquals("no.such.key", plain(catalog.raw("no.such.key")));
        assertNull(catalog.template("no.such.key"));
    }

    @Test
    void mergeMatchesLoadForIdenticalInputs(@TempDir Path tmp) throws Exception {
        // merge() is the pure core of load(); equality of outcomes for the
        // same inputs pins the Core adapter's behavior to the module path.
        Path file = tmp.resolve("messages.yml");
        Files.writeString(file, "prefix: \"<gray>[X]</gray>\"\n", java.nio.charset.StandardCharsets.UTF_8);
        YamlConfiguration user = new YamlConfiguration();
        user.load(file.toFile());
        MessageCatalog.LoadResult viaMerge = MessageCatalog.merge(user, fixture());
        MessageCatalog.LoadResult viaLoad = MessageCatalog.load(file, "messages.yml",
                MessageCatalogTest.class, LOG);
        assertEquals(viaMerge.issues().size(), viaLoad.issues().size());
        assertEquals(plain(viaMerge.catalog().prefix()), plain(viaLoad.catalog().prefix()));
        assertEquals(plain(viaMerge.catalog().raw("usage.player")),
                plain(viaLoad.catalog().raw("usage.player")));
    }
}
