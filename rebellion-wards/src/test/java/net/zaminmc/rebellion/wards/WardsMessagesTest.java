package net.zaminmc.rebellion.wards;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.wards.command.WardCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RebellionWards' bundled messages.yml is the complete player-feedback
 * contract (ADR-0012). Pins: strict-parse of the whole file, coverage of
 * every (action, outcome) key the command layer can render, literal
 * angle-bracket escaping in usage/listing texts, and the first-boot
 * extraction into the data folder.
 */
final class WardsMessagesTest {

    private static final Logger LOG = Logger.getLogger("test");

    /** Action → every outcome the service returns for it (WardService contract). */
    private static final Map<String, List<WardOutcome>> ACTION_OUTCOMES = Map.of(
            "create", List.of(WardOutcome.OK, WardOutcome.NAME_INVALID,
                    WardOutcome.NAME_TAKEN, WardOutcome.RADIUS_INVALID,
                    WardOutcome.PLAYER_CAP, WardOutcome.TEAM_CAP, WardOutcome.GLOBAL_CAP,
                    WardOutcome.ANCHOR_TAKEN, WardOutcome.ANCHOR_INVALID,
                    WardOutcome.DECOYS_DISABLED, WardOutcome.DB_ERROR),
            "remove", List.of(WardOutcome.OK, WardOutcome.NOT_FOUND,
                    WardOutcome.NO_RIGHTS, WardOutcome.DB_ERROR),
            "toggle", List.of(WardOutcome.OK, WardOutcome.NOT_FOUND,
                    WardOutcome.NO_RIGHTS, WardOutcome.DB_ERROR));

    private static final List<String> COMMAND_KEYS = List.of(
            "prefix", "usage", "player-only",
            "common.no-permission", "common.unavailable", "common.unexpected",
            "ward.create.usage", "ward.create.usage-decoy",
            "ward.create.radius-not-a-number", "ward.create.no-anchor",
            "ward.remove.usage", "ward.toggle.usage", "info.usage",
            "no-such-ward",
            "list.empty", "list.header", "list.line", "list.different-world",
            "state.active", "state.inactive", "state.decoy-tag",
            "info.header", "info.type-line", "info.type-ward", "info.type-decoy",
            "info.geometry", "info.insiders", "info.no-protection",
            "admin.usage", "admin.not-online", "admin.list-header", "admin.list-line",
            "admin.remove-usage", "admin.not-an-id",
            "admin.adminremove.ok", "admin.adminremove.not-found",
            "admin.mask.usage", "admin.mask.disabled", "admin.mask.not-online",
            "admin.mask.seconds-invalid", "admin.mask.seconds-range",
            "admin.mask.granted", "admin.mask.granted-target",
            "alert.enter", "alert.leave", "alert.decoy-tag", "alert.unknown-player",
            "anchor.broken");

    private static YamlConfiguration bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = WardsPlugin.class.getResourceAsStream("/messages.yml")) {
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
        for (String key : COMMAND_KEYS) {
            assertNotNull(catalog.template(key), "messages.yml is missing key " + key);
        }
        // Every (action, outcome) pair the command layer can render.
        for (var entry : ACTION_OUTCOMES.entrySet()) {
            for (WardOutcome outcome : entry.getValue()) {
                if (outcome == WardOutcome.OK && entry.getKey().equals("toggle")) {
                    // Toggle OK renders through reactivated/deactivated instead.
                    assertNotNull(catalog.template("ward.toggle.reactivated"));
                    assertNotNull(catalog.template("ward.toggle.deactivated"));
                    continue;
                }
                String key = WardCommand.outcomeKey(entry.getKey(), outcome);
                assertNotNull(catalog.template(key),
                        "messages.yml is missing key " + key + " for " + entry.getKey());
            }
        }
    }

    @Test
    void literalAngleBracketsRenderAsTextNotTags() {
        MessageCatalog catalog = MessageCatalog.parse(bundled()).catalog();
        // The old hard-coded text shipped HTML entities that rendered
        // literally as "&lt;name&gt;" in game — the catalog templates must
        // render real angle brackets.
        String empty = plain(catalog.raw("list.empty"));
        assertTrue(empty.contains("/ward create <name> <radius>"), empty);
        assertFalse(empty.contains("&lt;"), empty);
        assertTrue(plain(catalog.raw("usage"))
                .startsWith("Usage: /ward <create|remove|toggle|list|info>"));
        assertTrue(plain(catalog.raw("admin.usage"))
                .contains("/wardadmin <list [player]|remove <id>"));
    }

    @Test
    void loadExtractsBundledFileIntoDataFolder(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        MessageCatalog.LoadResult result =
                MessageCatalog.load(file, "messages.yml", WardsPlugin.class, LOG);
        assertTrue(Files.exists(file), "bundled messages.yml must be extracted on first boot");
        assertTrue(result.valid(), () -> result.issues().toString());
    }
}
