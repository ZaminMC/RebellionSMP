package net.zaminmc.rebellion.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Pins the placeholder routing contract, above all that routing is
 * <em>viewer-threaded</em>: a player-scoped request must reach module
 * contributors through {@code resolveFor(player, params)} with the very
 * same viewer. Before session 15 the router called the player-independent
 * {@code resolve(params)}, so every viewer-scoped module parameter
 * (ritual discoveries, team/dragon/ward viewer facts) resolved null through
 * PlaceholderAPI while the module thought it had answered.
 */
final class CorePlaceholdersTest {

    /** Records the viewer it was asked with; answers one fixed parameter. */
    private static final class RecordingContributor implements PlaceholderContributor {
        final List<Player> seenViewers = new ArrayList<>();
        final List<String> seenParams = new ArrayList<>();

        @Override
        public String resolve(String params) {
            throw new AssertionError("router must not call resolve(params) on contributors");
        }

        @Override
        public String resolveFor(Player player, String params) {
            seenViewers.add(player);
            seenParams.add(params);
            // Viewer-scoped like the real module contributors: no viewer,
            // no answer.
            return player != null && "team_x".equals(params) ? "crew" : null;
        }
    }

    /** Serverless fake of the Player interface: identity is all routing needs. */
    private static Player fakePlayer() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "fake-player";
                    case "hashCode" -> 42;
                    default -> null;
                });
    }

    private static CorePlaceholders coreWith(List<PlaceholderContributor> list) {
        return new CorePlaceholders("9.9.9-test",
                () -> "1/1", () -> "sqlite", () -> "0",
                () -> list);
    }

    @Test
    void coreParametersResolveThroughTheExpansion() {
        CorePlaceholders core = coreWith(List.of());
        assertEquals("9.9.9-test", core.resolveFor(null, "version"));
        assertEquals("1/1", core.resolveFor(null, "modules_healthy"));
        assertEquals("sqlite", core.resolveFor(null, "database_backend"));
        assertEquals("0", core.resolveFor(null, "modules_degraded"));
        // The same parameters answer player-independent requests too.
        assertEquals("9.9.9-test", core.resolve("version"));
    }

    @Test
    void routingForwardsTheViewerToContributors() {
        RecordingContributor recorder = new RecordingContributor();
        CorePlaceholders core = coreWith(List.of(recorder));
        Player viewer = fakePlayer();

        assertEquals("crew", core.resolveFor(viewer, "team_x"));
        assertEquals(1, recorder.seenViewers.size());
        assertSame(viewer, recorder.seenViewers.get(0), "the exact viewer must be forwarded");
        assertEquals("team_x", recorder.seenParams.get(0));
    }

    @Test
    void playerIndependentRoutingAsksWithANullViewer() {
        RecordingContributor recorder = new RecordingContributor();
        CorePlaceholders core = coreWith(List.of(recorder));

        assertNull(core.resolve("team_x")); // viewer-scoped answers nothing without a viewer
        assertEquals(1, recorder.seenViewers.size());
        assertNull(recorder.seenViewers.get(0));
    }

    @Test
    void unknownParametersStayNullForEveryViewer() {
        RecordingContributor recorder = new RecordingContributor();
        CorePlaceholders core = coreWith(List.of(recorder));

        assertNull(core.resolveFor(fakePlayer(), "nobody_owns_this"));
        assertEquals(1, recorder.seenViewers.size());
    }

    @Test
    void selfRegistrationIsExcludedAndCannotLoop() {
        List<PlaceholderContributor> list = new ArrayList<>();
        CorePlaceholders core = coreWith(list);
        list.add(core); // core registers itself as a contributor in production
        RecordingContributor recorder = new RecordingContributor();
        list.add(recorder);

        assertEquals("crew", core.resolveFor(fakePlayer(), "team_x"));
        assertEquals(1, recorder.seenViewers.size());
    }

    @Test
    void firstNonNullContributorAnswerWins() {
        RecordingContributor first = new RecordingContributor();
        RecordingContributor second = new RecordingContributor();
        CorePlaceholders core = coreWith(List.of(first, second));

        assertEquals("crew", core.resolveFor(fakePlayer(), "team_x"));
        assertEquals(1, first.seenViewers.size());
        assertEquals(0, second.seenViewers.size());
    }

    @Test
    void theExpansionRoutesContributorParametersWithTheViewer() {
        List<Player> routed = new ArrayList<>();
        PlaceholderContributor rpgLike = new PlaceholderContributor() {
            @Override
            public String resolve(String params) {
                return null;
            }

            @Override
            public String resolveFor(Player player, String params) {
                routed.add(player);
                return "ritual_communion".equals(params) ? "<green>known</green>" : null;
            }
        };
        CorePlaceholders core = coreWith(List.of(rpgLike));
        Player viewer = fakePlayer();

        assertEquals("<green>known</green>", core.resolveFor(viewer, "ritual_communion"));
        assertSame(viewer, routed.get(0));
    }
}
