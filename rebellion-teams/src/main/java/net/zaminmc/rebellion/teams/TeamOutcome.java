package net.zaminmc.rebellion.teams;

/**
 * Typed outcome of a team management operation. Commands map these to
 * player-facing messages; the service never guesses a message string.
 */
public enum TeamOutcome {
    OK,
    // name handling
    NAME_INVALID,
    NAME_TAKEN,
    // lifecycle
    CREATE_COOLDOWN,
    RENAME_COOLDOWN,
    NOT_IN_TEAM,
    NO_OFFICER_RIGHTS,
    NO_LEADER_RIGHTS,
    // invites
    INVITE_SELF,
    INVITE_RATE_LIMITED,
    INVITE_TARGET_IN_TEAM,
    INVITE_QUEUE_FULL,
    NO_INVITE,
    TEAM_FULL,
    ALREADY_IN_TEAM,
    INVITE_NOT_FOUND,
    // membership
    TARGET_NOT_MEMBER,
    CANT_TARGET_SELF,
    CANT_TARGET_LEADER,
    ALREADY_OFFICER,
    TARGET_NOT_OFFICER,
    // diplomacy
    TEAM_NOT_FOUND,
    RELATION_OWN_TEAM,
    // infrastructure (fail-closed; nothing was applied)
    DB_FAILURE,
    OVERLOADED
}
