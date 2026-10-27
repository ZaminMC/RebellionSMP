package net.zaminmc.rebellion.wards;

/** Typed ward operation outcomes; commands map these to player messages. */
public enum WardOutcome {
    OK,
    NAME_INVALID,
    NAME_TAKEN,
    RADIUS_INVALID,
    PLAYER_CAP,
    TEAM_CAP,
    GLOBAL_CAP,
    ANCHOR_TAKEN,
    ANCHOR_INVALID,
    DECOYS_DISABLED,
    NOT_FOUND,
    NO_RIGHTS,
    DB_ERROR
}
