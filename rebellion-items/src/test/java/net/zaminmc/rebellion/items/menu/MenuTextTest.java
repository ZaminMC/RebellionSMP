package net.zaminmc.rebellion.items.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure menu-placeholder substitution (§9/§10): rebellion-namespace
 * tokens only, unresolved tokens stay visible, values substitute verbatim
 * (no regex/quotable characters in a value can corrupt the line).
 */
final class MenuTextTest {

    private static final Map<String, String> RPG_LIKE = Map.of(
            "ritual_ritual_soul_communion_discovered", "<green>known</green>",
            "version", "0.1.0-dev.11");

    @Test
    void stringsWithoutTokensPassThroughUnchanged() {
        String plain = "<gold>Ritual Guide</gold>";
        assertEquals(plain, MenuText.placeholders(plain, p -> {
            throw new AssertionError("resolver must not be asked when no token exists");
        }));
        assertEquals("", MenuText.placeholders("", p -> null));
    }

    @Test
    void substitutesASingleToken() {
        // The substituted value carries the contributor's MiniMessage
        // color tags through verbatim - the host parses them when rendering.
        assertEquals("Communion: <green>known</green>",
                MenuText.placeholders("Communion: %rebellion_ritual_ritual_soul_communion_discovered%",
                        RPG_LIKE::get));
    }

    @Test
    void substitutesSeveralTokensOnOneLine() {
        assertEquals("v0.1.0-dev.11, knowledge <green>known</green>",
                MenuText.placeholders(
                        "v%rebellion_version%, knowledge %rebellion_ritual_ritual_soul_communion_discovered%",
                        RPG_LIKE::get));
    }

    @Test
    void unresolvedTokensStayVisibleForTheOperator() {
        assertEquals("State: %rebellion_unknown_module_param%",
                MenuText.placeholders("State: %rebellion_unknown_module_param%", p -> null));
    }

    @Test
    void valuesSubstituteVerbatimIncludingQuotableCharacters() {
        // $ and backslash are regex-replacement metacharacters; the operator's
        // message text must never be interpreted through them.
        Map<String, String> values = new HashMap<>();
        values.put("team", "a$b\\c<green>");
        assertEquals("Team a$b\\c<green>!",
                MenuText.placeholders("Team %rebellion_team%!", values::get));
    }

    @Test
    void emptyAnswersAreValuesNotMisses() {
        assertEquals("x", MenuText.placeholders("x%rebellion_blank%", p -> ""));
    }

    @Test
    void foreignNamespacesAreNeverTouched() {
        String foreign = "%player% %essentials_balance% %rebellion";
        assertEquals(foreign, MenuText.placeholders(foreign, p -> "replaced"));
    }

    @Test
    void parameterCharsetForwardsUpperLowerDigitsHyphens() {
        Map<String, String> seen = new HashMap<>();
        MenuText.placeholders(
                "%rebellion_ABC_def-123% %rebellion_ritual_ritual_soul_communion_discovered%",
                p -> {
                    seen.put(p, "ok");
                    return null;
                });
        assertEquals(java.util.Set.of("ABC_def-123", "ritual_ritual_soul_communion_discovered"),
                seen.keySet());
    }
}
