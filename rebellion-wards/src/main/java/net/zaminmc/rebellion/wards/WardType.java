package net.zaminmc.rebellion.wards;

/**
 * Ward flavor. {@code WARD} is a full watch ward; {@code DECOY} is a cheap
 * fake whose detection behaves identically but whose alerts are labeled for
 * the owning team and which never feeds nearest-ward placeholders or map
 * markers (misinformation must not leak into own-intel surfaces).
 */
public enum WardType {
    WARD,
    DECOY;

    /** Parses stored values leniently-by-case but rejects unknowns. */
    public static WardType parse(String value) {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
