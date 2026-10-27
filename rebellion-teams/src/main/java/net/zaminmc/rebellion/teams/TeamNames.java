package net.zaminmc.rebellion.teams;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Team name rules (ADR-0006): display names are 3–24 chars of {@code
 * [A-Za-z0-9_]}; the uniqueness key is the lowercase normalization. Reserved
 * words are rejected at both validation and service level so cosmetic names
 * can never be confused with system words in menus and placeholders.
 */
public final class TeamNames {

    /** Display name: 3–24 chars of ASCII letters, digits or underscore. */
    private static final Pattern DISPLAY = Pattern.compile("[A-Za-z0-9_]{3,24}");

    /** Names that carry meaning in commands, menus and placeholders. */
    private static final Set<String> RESERVED =
            Set.of("rebellion", "admin", "staff", "team", "teams", "none", "null");

    private TeamNames() {
    }

    /** @return the lowercase uniqueness key for a valid display name. */
    public static String normalize(String displayName) {
        return displayName.toLowerCase(Locale.ROOT);
    }

    /**
     * @return the normalized name when {@code candidate} is a valid,
     *         unreserved team name; empty otherwise. Callers must not guess:
     *         an invalid name never reaches storage.
     */
    public static Optional<String> validateAndNormalize(String candidate) {
        if (candidate == null || !DISPLAY.matcher(candidate).matches()) {
            return Optional.empty();
        }
        String normalized = normalize(candidate);
        if (RESERVED.contains(normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }
}
