package net.zaminmc.rebellion.wards;

/**
 * Ward name validation. Names are 3-24 characters of {@code [A-Za-z0-9_-]},
 * matched by the same policy as team names minus the reserved-word list
 * (ward names are owner-scoped, so collisions across owners are fine and
 * reserved vocabulary is unnecessary).
 *
 * <p>Names are display data, never authority: lookups go through UUIDs and
 * owner-scoped name uniqueness; the validated charset keeps MiniMessage
 * injection impossible at the source.</p>
 */
public final class WardName {

    private WardName() {
    }

    /** @return true when the candidate is a valid ward name. */
    public static boolean valid(String candidate) {
        return candidate != null && candidate.matches("[A-Za-z0-9_-]{3,24}");
    }

    /** Normalized comparison key (case-insensitive, owner-scoped). */
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
