package de.vvwt.tm.domain.rules;

import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scoring rule: winner gets 2 points, loser gets 0; ties award 1/1 (D-15).
 *
 * <p>Tie semantics: ties are only valid when {@code format.isAllowsTies() == true} (currently only
 * {@code FIXED_2_SETS} with a 1:1 result). A tie in a {@code BEST_OF_N} format indicates a bug in
 * the upstream cascade — the rule surfaces this defensively by throwing {@link
 * IllegalStateException}.
 *
 * <p>This rule is stateless and thread-safe. Spring registers it as a singleton bean named {@code
 * "twoPoint"}.
 */
@Component("twoPoint")
public class TwoPointMatchRule implements ScoringRule {

    private static final Logger log = LoggerFactory.getLogger(TwoPointMatchRule.class);

    @Override
    public ScoringResult calculatePoints(MatchOutcome outcome, MatchFormat format) {
        validateInputs(outcome, format);

        int team1Sets = outcome.getTeam1SetsWon();
        int team2Sets = outcome.getTeam2SetsWon();

        final int team1Points;
        final int team2Points;

        if (team1Sets > team2Sets) {
            team1Points = 2;
            team2Points = 0;
        } else if (team2Sets > team1Sets) {
            team1Points = 0;
            team2Points = 2;
        } else {
            // Equal set count — tie
            if (!format.isAllowsTies()) {
                throw new IllegalStateException(
                        "Impossible tie detected in format "
                                + format.name()
                                + " (allowsTies=false). Outcome: "
                                + outcome
                                + ". This indicates a bug in the cascade service — "
                                + "a BEST_OF_N match cannot end in a draw.");
            }
            team1Points = 1;
            team2Points = 1;
        }

        ScoringResult result = new ScoringResult(team1Points, team2Points);

        log.debug(
                "twoPoint: outcome={}, format={} → result=({}, {})",
                outcome,
                format,
                team1Points,
                team2Points);

        return result;
    }

    @Override
    public String getBeanId() {
        return "twoPoint";
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
