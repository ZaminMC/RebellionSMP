package net.zaminmc.rebellion.rpg;

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
 * RebellionRPG's bundled messages.yml is the module's complete
 * player-feedback contract (ADR-0012). These tests pin it: every key the
 * commands, listener and controller render must exist and the whole file
 * must strict-parse (explicitly closed MiniMessage tags).
 */
final class RpgMessagesTest {

    private static final Logger LOG = Logger.getLogger("test");

    /** Every key the RpgPlugin surface renders. */
    private static final List<String> KEYS = List.of(
            "prefix",
            "common.no-permission", "common.unavailable",
            "usage", "player-only",
            "ritual.list.header", "ritual.list.line",
            "ritual.list.state.known", "ritual.list.state.unknown",
            "ritual.list.state.disabled",
            "ritual.cast.usage", "ritual.cast.unknown", "ritual.cast.disabled",
            "ritual.cast.wrong-kind", "ritual.cast.missing", "ritual.cast.ok",
            "ritual.cast.already-known", "ritual.cast.removed-mid-cast",
            "ritual.track.usage", "ritual.track.wrong-kind",
            "ritual.track.unknown-target", "ritual.track.ok", "ritual.track.failed",
            "ritual.mask.usage", "ritual.mask.wrong-kind", "ritual.mask.ok",
            "ritual.refused.rate-limited", "ritual.refused.cooldown",
            "ritual.refused.reservation-held", "ritual.refused.session-cap",
            "ritual.release.ok", "ritual.release.noop",
            "ritual.status.header", "ritual.status.sessions-none",
            "ritual.status.sessions-line", "ritual.status.effects-none",
            "ritual.status.effects-line", "ritual.status.claims-none",
            "ritual.status.claims-line", "ritual.status.discoveries-none",
            "ritual.status.discoveries-line",
            "ritual.signal.none", "ritual.signal.header", "ritual.signal.masked",
            "ritual.signal.offline", "ritual.signal.line",
            "ritual.signal.range.close", "ritual.signal.range.mid",
            "ritual.signal.range.far", "ritual.signal.range.beyond",
            "soul.interact.ok", "soul.interact.already", "soul.interact.not-active",
            "admin.usage", "admin.events-header", "admin.events-line",
            "admin.events-none", "admin.player-unknown",
            "admin.revoke.usage", "admin.revoke.ok", "admin.revoke.noop",
            "admin.revoke.not-found");

    private static YamlConfiguration bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = RpgPlugin.class.getResourceAsStream("/messages.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return yaml;
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
    void loadExtractsBundledFileIntoDataFolder(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        MessageCatalog.LoadResult result =
                MessageCatalog.load(file, "messages.yml", RpgPlugin.class, LOG);
        assertTrue(Files.exists(file), "bundled messages.yml must be extracted on first boot");
        assertTrue(result.valid(), () -> result.issues().toString());
    }
}
