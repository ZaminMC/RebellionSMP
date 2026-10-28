package net.zaminmc.rebellion.wards.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardType;
import net.zaminmc.rebellion.wards.WardsConfig;
import net.zaminmc.rebellion.wards.WardsConfig.AlertChannel;
import net.zaminmc.rebellion.wards.WardsPlugin;
import net.zaminmc.rebellion.wards.detect.AlertDecision;
import net.zaminmc.rebellion.wards.distance.DistanceService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * {@link AlertRouter} decision logic: channel routing (team/owner/both,
 * owner fallback) and template rendering through the module's real bundled
 * messages.yml (calibrated vs uncalibrated distance, decoy label, name
 * placeholder safety). Pure statics — no server needed.
 */
class AlertRouterTest {

    private static final String WORLD = "world";
    private final UUID owner = UUID.randomUUID();
    private final UUID teammate = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();
    private final UUID intruder = UUID.randomUUID();

    /** The real bundled catalog — the tests assert the shipped templates. */
    private static MessageCatalog catalog() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = WardsPlugin.class.getResourceAsStream("/messages.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return MessageCatalog.parse(yaml).catalog();
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(c);
    }

    private WardsConfig config(AlertChannel channel) {
        return new WardsConfig("LODESTONE", 6, 16.0, 256.0, 3, 12, 2000,
                Set.of("leader", "officer"), true, 20, 0, true,
                "AMETHYST_SHARD", channel, 300, true,
                Map.of(), "blocks", false);
    }

    private TeamAffiliationService teams(boolean teamOnline) {
        return new TeamAffiliationService() {
            @Override
            public Optional<UUID> teamOf(UUID player) {
                return Optional.of(teamId);
            }

            @Override
            public Optional<String> rankOf(UUID player) {
                return Optional.of("leader");
            }

            @Override
            public Set<UUID> onlineMembersOf(UUID team) {
                return team.equals(teamId) && teamOnline
                        ? Set.of(owner, teammate) : Set.of();
            }
        };
    }

    private Ward teamWard() {
        return new Ward(UUID.randomUUID(), "north", owner, Optional.of(teamId),
                WORLD, 0, 64, 0, 64, WardType.WARD, true, 0L);
    }

    // ------------------------------------------------------------------
    // Channel routing
    // ------------------------------------------------------------------

    @Test
    void teamChannelRoutesToOnlineTeamMembers() {
        Set<UUID> recipients = AlertRouter.recipients(teamWard(),
                config(AlertChannel.TEAM), teams(true));
        assertEquals(Set.of(owner, teammate), recipients);
    }

    @Test
    void teamChannelFallsBackToOwnerWhenTeamHasNoOnlineMembers() {
        Set<UUID> recipients = AlertRouter.recipients(teamWard(),
                config(AlertChannel.TEAM), teams(false));
        assertEquals(Set.of(owner), recipients);
    }

    @Test
    void teamChannelFallsBackToOwnerWithoutTeamsModule() {
        Set<UUID> recipients = AlertRouter.recipients(teamWard(),
                config(AlertChannel.TEAM), null);
        assertEquals(Set.of(owner), recipients);
    }

    @Test
    void ownerChannelRoutesToOwnerOnly() {
        Set<UUID> recipients = AlertRouter.recipients(teamWard(),
                config(AlertChannel.OWNER), teams(true));
        assertEquals(Set.of(owner), recipients);
    }

    @Test
    void bothChannelUnionsOwnerAndTeam() {
        Set<UUID> recipients = AlertRouter.recipients(teamWard(),
                config(AlertChannel.BOTH), teams(true));
        assertEquals(Set.of(owner, teammate), recipients);
    }

    @Test
    void teamlessWardAlwaysRoutesToOwner() {
        Ward teamless = new Ward(UUID.randomUUID(), "solo", owner, Optional.empty(),
                WORLD, 0, 64, 0, 64, WardType.WARD, true, 0L);
        for (AlertChannel channel : AlertChannel.values()) {
            assertEquals(Set.of(owner), AlertRouter.recipients(teamless,
                    config(channel), teams(true)), "channel " + channel);
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Test
    void enterMessageCarriesNameWardAndUncalibratedDistance() {
        DistanceService blocks = new DistanceService(config(AlertChannel.TEAM));
        AlertDecision alert = AlertDecision.enter(teamWard(), intruder, 2400);
        String text = plain(AlertRouter.renderText(alert, blocks, id -> "Steve", catalog()));
        assertTrue(text.contains("Steve"), text);
        assertTrue(text.contains("north"), text);
        assertTrue(text.contains("2400 blocks"), "uncalibrated distance: " + text);
        assertFalse(text.contains("km"), "uncalibrated must never say km: " + text);
    }

    @Test
    void enterMessageUsesCalibratedKilometers() {
        WardsConfig calibrated = new WardsConfig("LODESTONE", 6, 16.0, 256.0, 3, 12, 2000,
                Set.of("leader", "officer"), true, 20, 0, true, "AMETHYST_SHARD",
                AlertChannel.TEAM, 300, true, Map.of(WORLD, 1000.0), "blocks", false);
        DistanceService km = new DistanceService(calibrated);
        AlertDecision alert = AlertDecision.enter(teamWard(), intruder, 2400);
        String text = plain(AlertRouter.renderText(alert, km, id -> "Alex", catalog()));
        assertTrue(text.contains("2.4 km"), "the master prompt's example: " + text);
    }

    @Test
    void decoyAlertsAreLabeledAndLeaveAlertsSkipDistance() {
        DistanceService blocks = new DistanceService(config(AlertChannel.TEAM));
        Ward decoy = new Ward(UUID.randomUUID(), "fake", owner, Optional.of(teamId),
                WORLD, 0, 64, 0, 64, WardType.DECOY, true, 0L);

        String enter = plain(AlertRouter.renderText(AlertDecision.enter(decoy, intruder, 10),
                blocks, id -> "Steve", catalog()));
        assertTrue(enter.contains("decoy"), enter);

        String leave = plain(AlertRouter.renderText(AlertDecision.leave(decoy, intruder),
                blocks, id -> "Steve", catalog()));
        assertTrue(leave.contains("left the radius"), leave);
        assertFalse(leave.contains("blocks"), "leave has no distance: " + leave);
    }

    @Test
    void namesAreSafeAgainstMiniMessageInjection() {
        DistanceService blocks = new DistanceService(config(AlertChannel.TEAM));
        AlertDecision alert = AlertDecision.enter(teamWard(), intruder, 10);
        // The name is inserted as an unparsed placeholder: tags and click
        // events are structurally impossible, not stripped — prove it on the
        // rendered component.
        net.kyori.adventure.text.Component rendered = AlertRouter.renderText(alert, blocks,
                id -> "<red>Evil<click:run_command:'/kill'>", catalog());
        assertNoClickEvents(rendered);
        assertTrue(plain(rendered).contains("Evil"), "the plain name survives");
        assertTrue(plain(rendered).contains("is approaching"),
                "template tags still render: " + plain(rendered));
    }

    private static void assertNoClickEvents(net.kyori.adventure.text.Component component) {
        org.junit.jupiter.api.Assertions.assertNull(component.clickEvent(),
                "injected name must not create a click event");
        for (net.kyori.adventure.text.Component child : component.children()) {
            assertNoClickEvents(child);
        }
    }

    @Test
    void offlineIntruderRendersAsSomeone() {
        DistanceService blocks = new DistanceService(config(AlertChannel.TEAM));
        AlertDecision alert = AlertDecision.enter(teamWard(), intruder, 10);
        String text = plain(AlertRouter.renderText(alert, blocks, id -> null, catalog()));
        assertTrue(text.contains("someone"), text);
    }
}
