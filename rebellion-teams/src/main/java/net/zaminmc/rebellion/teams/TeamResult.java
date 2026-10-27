package net.zaminmc.rebellion.teams;

/**
 * Result of a team operation: outcome plus machine-readable detail (e.g.
 * remaining cooldown seconds, team name). Never carries player-facing text —
 * commands own presentation.
 */
public record TeamResult(TeamOutcome outcome, String detail) {

    public static TeamResult ok() {
        return new TeamResult(TeamOutcome.OK, null);
    }

    public static TeamResult ok(String detail) {
        return new TeamResult(TeamOutcome.OK, detail);
    }

    public static TeamResult fail(TeamOutcome outcome) {
        return new TeamResult(outcome, null);
    }

    public static TeamResult fail(TeamOutcome outcome, String detail) {
        return new TeamResult(outcome, detail);
    }

    public boolean success() {
        return outcome == TeamOutcome.OK;
    }
}
