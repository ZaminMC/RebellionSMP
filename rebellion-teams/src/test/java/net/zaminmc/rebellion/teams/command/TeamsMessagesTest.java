package net.zaminmc.rebellion.teams.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.teams.TeamOutcome;
import net.zaminmc.rebellion.teams.TeamsPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RebellionTeams' bundled messages.yml is the complete player-feedback
 * contract (ADR-0012). Pins: strict-parse of the whole file, coverage of
 * every (action, outcome) key the command layer can render, the chat format
 * templates, and the first-boot extraction into the data folder.
 */
final class TeamsMessagesTest {

    private static final Logger LOG = Logger.getLogger("test");

    /** Action → every outcome the service returns for it (mirrors TeamService's contract). */
    private static final Map<String, List<TeamOutcome>> ACTION_OUTCOMES = Map.ofEntries(
            Map.entry("create", List.of(TeamOutcome.OK, TeamOutcome.NAME_INVALID,
                    TeamOutcome.NAME_TAKEN, TeamOutcome.ALREADY_IN_TEAM,
                    TeamOutcome.CREATE_COOLDOWN, TeamOutcome.DB_FAILURE, TeamOutcome.OVERLOADED)),
            Map.entry("invite", List.of(TeamOutcome.OK, TeamOutcome.NOT_IN_TEAM,
                    TeamOutcome.NO_OFFICER_RIGHTS, TeamOutcome.INVITE_QUEUE_FULL,
                    TeamOutcome.INVITE_RATE_LIMITED, TeamOutcome.INVITE_SELF,
                    TeamOutcome.INVITE_TARGET_IN_TEAM, TeamOutcome.DB_FAILURE)),
            Map.entry("uninvite", List.of(TeamOutcome.OK, TeamOutcome.NOT_IN_TEAM,
                    TeamOutcome.NO_OFFICER_RIGHTS, TeamOutcome.INVITE_QUEUE_FULL,
                    TeamOutcome.INVITE_RATE_LIMITED, TeamOutcome.INVITE_NOT_FOUND,
                    TeamOutcome.DB_FAILURE)),
            Map.entry("accept", List.of(TeamOutcome.OK, TeamOutcome.NO_INVITE,
                    TeamOutcome.INVITE_NOT_FOUND, TeamOutcome.TEAM_FULL,
                    TeamOutcome.ALREADY_IN_TEAM, TeamOutcome.DB_FAILURE)),
            Map.entry("kick", List.of(TeamOutcome.OK, TeamOutcome.NOT_IN_TEAM,
                    TeamOutcome.CANT_TARGET_SELF, TeamOutcome.TARGET_NOT_MEMBER,
                    TeamOutcome.CANT_TARGET_LEADER, TeamOutcome.NO_OFFICER_RIGHTS,
                    TeamOutcome.NO_LEADER_RIGHTS, TeamOutcome.DB_FAILURE)),
            Map.entry("leave", List.of(TeamOutcome.OK, TeamOutcome.NOT_IN_TEAM,
                    TeamOutcome.NO_LEADER_RIGHTS, TeamOutcome.DB_FAILURE)),
            Map.entry("rename", List.of(TeamOutcome.OK, TeamOutcome.NOT_IN_TEAM,
                    TeamOutcome.NO_LEADER_RIGHTS, TeamOutcome.NAME_INVALID,
                    TeamOutcome.NAME_TAKEN, TeamOutcome.RENAME_COOLDOWN,
                    TeamOutcome.DB_FAILURE)),
            Map.entry("rank", List.of(TeamOutcome.OK, TeamOutcome.NOT_IN_TEAM,
                    TeamOutcome.NO_LEADER_RIGHTS, TeamOutcome.CANT_TARGET_SELF,
                    TeamOutcome.TARGET_NOT_MEMBER, TeamOutcome.CANT_TARGET_LEADER,
                    TeamOutcome.ALREADY_OFFICER, TeamOutcome.TARGET_NOT_OFFICER,
                    TeamOutcome.DB_FAILURE)),
            Map.entry("friendlyfire", List.of(TeamOutcome.OK, TeamOutcome.NOT_IN_TEAM,
                    TeamOutcome.NO_LEADER_RIGHTS, TeamOutcome.DB_FAILURE)),
            Map.entry("relation", List.of(TeamOutcome.OK, TeamOutcome.NOT_IN_TEAM,
                    TeamOutcome.NO_LEADER_RIGHTS, TeamOutcome.TEAM_NOT_FOUND,
                    TeamOutcome.RELATION_OWN_TEAM, TeamOutcome.DB_FAILURE)));

    private static final List<String> COMMAND_KEYS = List.of(
            "prefix", "usage", "player-only", "not-in-team", "target-not-online",
            "name-invalid", "name-taken", "no-team-named",
            "common.no-permission", "common.unavailable",
            "team.invite.usage", "team.uninvite.usage", "team.kick.usage",
            "team.rank.promote-usage", "team.rank.demote-usage", "team.rank.transfer-usage",
            "team.friendlyfire.usage", "team.relation.usage",
            "info.unavailable", "info.header", "info.summary", "info.fire-on", "info.fire-off",
            "info.member-line", "info.rank-leader", "info.rank-officer", "info.relations-line",
            "invites.unavailable", "invites.none", "invites.header", "invites.line",
            "history.unavailable", "history.none", "history.header", "history.line",
            "chat.player-only", "chat.usage", "chat.need-team", "chat.proximity-disabled",
            "chat.unknown-channel", "chat.switched-team", "chat.switched-proximity",
            "chat.switched-global", "chat.rate-limited", "chat.team-format",
            "chat.proximity-format");

    private static YamlConfiguration bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = TeamsPlugin.class.getResourceAsStream("/messages.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return yaml;
    }

    private static String plain(Component c) {
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
        for (var entry : ACTION_OUTCOMES.entrySet()) {
            for (TeamOutcome outcome : entry.getValue()) {
                String key = TeamCommand.outcomeKey(entry.getKey(), outcome);
                assertNotNull(catalog.template(key),
                        "messages.yml is missing key " + key + " for " + entry.getKey());
            }
        }
    }

    @Test
    void chatFormatsRenderSenderAndMessageComponents() {
        MessageCatalog catalog = MessageCatalog.parse(bundled()).catalog();
        Component sender = Component.text("Steve");
        Component message = Component.text("hi");
        assertEquals("[Team] Steve: hi",
                plain(catalog.raw("chat.team-format",
                        Placeholder.component("sender", sender),
                        Placeholder.component("message", message))));
        assertEquals("<Steve> hi",
                plain(catalog.raw("chat.proximity-format",
                        Placeholder.component("sender", sender),
                        Placeholder.component("message", message))));
    }

    @Test
    void usageAndAcceptTemplatesRenderLiteralAngleBrackets() {
        MessageCatalog catalog = MessageCatalog.parse(bundled()).catalog();
        assertTrue(plain(catalog.raw("usage")).startsWith("Usage: /team <create|"));
        assertTrue(plain(catalog.raw("team.accept.no-invite"))
                .contains("accept <team-name> when several are pending"));
        assertTrue(plain(catalog.raw("chat.usage")).contains("/chat <global|team|proximity>"));
    }

    @Test
    void loadExtractsBundledFileIntoDataFolder(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("messages.yml");
        MessageCatalog.LoadResult result =
                MessageCatalog.load(file, "messages.yml", TeamsPlugin.class, LOG);
        assertTrue(Files.exists(file), "bundled messages.yml must be extracted on first boot");
        assertTrue(result.valid(), () -> result.issues().toString());
    }
}
