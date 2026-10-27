package net.zaminmc.rebellion.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Team name rules: shape, normalization, reserved words. */
final class TeamNamesTest {

    @Test
    void validNamesNormalizeToTheirLowercaseKey() {
        Optional<String> normalized = TeamNames.validateAndNormalize("AlphaRangers_9");
        assertTrue(normalized.isPresent());
        assertEquals("alpharangers_9", normalized.get());
    }

    @Test
    void invalidShapesAreRejected() {
        assertTrue(TeamNames.validateAndNormalize(null).isEmpty());
        assertTrue(TeamNames.validateAndNormalize("ab").isEmpty(), "too short");
        assertTrue(TeamNames.validateAndNormalize("a".repeat(25)).isEmpty(), "too long");
        assertTrue(TeamNames.validateAndNormalize("has space").isEmpty());
        assertTrue(TeamNames.validateAndNormalize("häs").isEmpty(), "non-ASCII");
        assertTrue(TeamNames.validateAndNormalize("-leading").isEmpty());
        assertTrue(TeamNames.validateAndNormalize("").isEmpty());
    }

    @Test
    void reservedWordsAreRejectedCaseInsensitively() {
        assertTrue(TeamNames.validateAndNormalize("Rebellion").isEmpty());
        assertTrue(TeamNames.validateAndNormalize("STAFF").isEmpty());
        assertTrue(TeamNames.validateAndNormalize("Team").isEmpty());
        assertTrue(TeamNames.validateAndNormalize("None").isEmpty());
        assertTrue(TeamNames.validateAndNormalize("Null").isEmpty());
    }
}
