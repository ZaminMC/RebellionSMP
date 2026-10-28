package net.zaminmc.rebellion.dragons;

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
 * RebellionDragons' bundled messages.yml is the module's complete
 * player-feedback contract (ADR-0012). These tests pin it: every key the
 * commands, listeners, controller and observer render must exist, the whole
 * file must strict-parse (explicitly closed MiniMessage tags), the load
 * path must extract it into the data folder, and escaped angle brackets
 * must survive as literal text.
 */
final class DragonsMessagesTest {

    private static final Logger LOG = Logger.getLogger("test");

    /** Every key DragonsPlugin's surface renders. */
    private static final List<String> KEYS = List.of(
            "prefix",
            "common.no-permission", "common.unavailable",
            "usage", "player-only",
            "dragon.hatch.usage", "dragon.hatch.no-egg", "dragon.hatch.not-your-egg",
            "dragon.hatch.ok", "dragon.hatch.conflict",
            "dragon.status.none", "dragon.status.header", "dragon.status.detail",
            "dragon.status.gateway", "dragon.status.pearl",
            "dragon.status.state.egg-held", "dragon.status.state.egg-recovery",
            "dragon.status.state.hatched", "dragon.status.state.retired",
            "dragon.status.location-unknown", "dragon.status.pearl-ready",
            "dragon.call.ok", "dragon.call.cooldown", "dragon.call.too-far",
            "dragon.call.not-hatched", "dragon.call.not-found",
            "dragon.mount.disabled", "dragon.mount.ok", "dragon.mount.too-far",
            "dragon.mount.not-hatched", "dragon.mount.not-yours",
            "dragon.dismount.ok",
            "dragon.dismiss.ok",
            "dragon.attack.usage", "dragon.attack.no-target", "dragon.attack.ok",
            "dragon.attack.budget", "dragon.attack.interval",
            "dragon.attack.not-hatched", "dragon.attack.friendly",
            "dragon.claim.none", "dragon.claim.ok", "dragon.claim.not-yours",
            "feed.ok", "feed.cooldown", "feed.not-owner",
            "egg.name", "egg.lore-1", "egg.lore-2",
            "egg.dropped", "egg.undeliverable",
            "victory.minted", "victory.cap",
            "admin.usage", "admin.list-header", "admin.list-empty",
            "admin.list-line", "admin.not-found",
            "admin.audit-header", "admin.audit-line", "admin.audit-none",
            "admin.route.usage", "admin.route.not-online", "admin.route.ok",
            "admin.retire.usage", "admin.retire.confirm", "admin.retire.ok");

    private static YamlConfiguration bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = DragonsPlugin.class.getResourceAsStream("/messages.yml")) {
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
            assertNotNull(catalog.template(key), "messages.yml is missing key " + key);
        }
    }

    @Test
    void usageTemplatesRenderLiteralAngleBrackets() {
        MessageCatalog catalog = MessageCatalog.parse(bundled()).catalog();
        assertTrue(plain(catalog.raw("usage"))
                .contains("/dragon <hatch|status|call|mount|dismount|dismiss|attack|claim>"),
                "usage must render escaped brackets as literal text");
        assertTrue(plain(catalog.raw("admin.usage")).contains("retire <slot> confirm"));
        assertTrue(plain(catalog.raw("admin.route.usage")).contains("<slot> <player>"));
    }

    @Test
    void loadExtractsBundledFileIntoDataFolder(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        MessageCatalog.LoadResult result =
                MessageCatalog.load(file, "messages.yml", DragonsPlugin.class, LOG);
        assertTrue(Files.exists(file), "bundled messages.yml must be extracted on first boot");
        assertTrue(result.valid(), () -> result.issues().toString());
        assertEquals(Files.readString(file),
                new String(java.util.Objects.requireNonNull(
                        DragonsPlugin.class.getResourceAsStream("/messages.yml"))
                        .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
