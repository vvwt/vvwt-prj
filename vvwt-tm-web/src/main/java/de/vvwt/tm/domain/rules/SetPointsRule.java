package de.vvwt.tm.domain.rules;

import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scoring rule: 1 point per set won by each team (legacy simplification, D-15).
 *
 * <p>A 3:2 match awards 3 points to the winner and 2 to the loser. A 1:1 tie in {@code
 * FIXED_2_SETS} awards 1 point each. Works for all V1 {@link MatchFormat} values.
 *
 * <p>This rule is stateless and thread-safe. Spring registers it as a singleton bean named {@code
 * "setPoints"}.
 */
@Component("setPoints")
public class SetPointsRule implements ScoringRule {

    private static final Logger log = LoggerFactory.getLogger(SetPointsRule.class);

    @Override
    public ScoringResult calculatePoints(MatchOutcome outcome, MatchFormat format) {
        validateInputs(outcome, format);

        int team1Points = outcome.getTeam1SetsWon();
        int team2Points = outcome.getTeam2SetsWon();

        ScoringResult result = new ScoringResult(team1Points, team2Points);

        log.debug(
                "setPoints: outcome={}, format={} → result=({}, {})",
                outcome,
                format,
                team1Points,
                team2Points);

        return result;
    }

    @Override
    public String getBeanId() {
        return "setPoints";
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static void validateInputs(MatchOutcome outcome, MatchFormat format) {
        if (outcome == null) {
            throw new IllegalArgumentException("MatchOutcome must not be null");
        }
        if (format == null) {
            throw new IllegalArgumentException("MatchFormat must not be null");
        }
        if (outcome.getTeam1SetsWon() < 0) {
            throw new IllegalArgumentException(
                    "team1SetsWon must be >= 0, got: " + outcome.getTeam1SetsWon());
        }
        if (outcome.getTeam2SetsWon() < 0) {
            throw new IllegalArgumentException(
                    "team2SetsWon must be >= 0, got: " + outcome.getTeam2SetsWon());
        }
        if (outcome.getSetCount() < 0) {
            throw new IllegalArgumentException(
                    "setCount must be >= 0, got: " + outcome.getSetCount());
        }
        if (outcome.getSetCount() != outcome.getTeam1SetsWon() + outcome.getTeam2SetsWon()) {
            throw new IllegalArgumentException(
                    "setCount ("
                            + outcome.getSetCount()
                            + ") must equal team1SetsWon ("
                            + outcome.getTeam1SetsWon()
                            + ") + team2SetsWon ("
                            + outcome.getTeam2SetsWon()
                            + ")");
        }
    }
}
