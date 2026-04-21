package de.vvwt.tm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for {@link SetPointsRule} (AC8 + AC12 null/negative guards). */
class SetPointsRuleTest {

    private SetPointsRule rule;

    @BeforeEach
    void setUp() {
        rule = new SetPointsRule();
    }

    // -----------------------------------------------------------------------
    // AC8 — parametrized happy-path cases
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "format={0} team1={1} team2={2} sets={3} → ({4},{5})")
    @CsvSource({
        // format,           t1, t2, sc, expected1, expected2
        "BEST_OF_5,          3,  0,  3,  3, 0", // AC8 row 1
        "BEST_OF_5,          3,  1,  4,  3, 1", // AC8 row 2
        "BEST_OF_5,          3,  2,  5,  3, 2", // AC8 row 3
        "BEST_OF_3,          2,  0,  2,  2, 0", // AC8 row 4
        "BEST_OF_1,          1,  0,  1,  1, 0", // AC8 row 5
        "FIXED_2_SETS,       2,  0,  2,  2, 0", // AC8 row 6
        "FIXED_2_SETS,       1,  1,  2,  1, 1", // AC8 row 7 (tie)
    })
    void calculatePoints_returnsSetCounts(
            MatchFormat format, int t1, int t2, int sc, int exp1, int exp2) {

        MatchOutcome outcome = new MatchOutcome(t1, t2, sc);
        ScoringResult result = rule.calculatePoints(outcome, format);

        assertThat(result.team1Points()).isEqualTo(exp1);
        assertThat(result.team2Points()).isEqualTo(exp2);
    }

    // -----------------------------------------------------------------------
    // AC12 — null and negative input guards
    // -----------------------------------------------------------------------

    @Test
    void calculatePoints_nullOutcome_throws() {
        assertThatThrownBy(() -> rule.calculatePoints(null, MatchFormat.BEST_OF_3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void calculatePoints_nullFormat_throws() {
        MatchOutcome outcome = new MatchOutcome(3, 0, 3);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void calculatePoints_negativeTeam1Sets_throws() {
        MatchOutcome outcome = new MatchOutcome(-1, 2, 1);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team1SetsWon");
    }

    @Test
    void calculatePoints_negativeTeam2Sets_throws() {
        MatchOutcome outcome = new MatchOutcome(2, -1, 1);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team2SetsWon");
    }

    @Test
    void calculatePoints_inconsistentSetCount_throws() {
        // setCount=10 but team1+team2=5
        MatchOutcome outcome = new MatchOutcome(3, 2, 10);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setCount");
    }

    // -----------------------------------------------------------------------
    // AC1 — getBeanId
    // -----------------------------------------------------------------------

    @Test
    void getBeanId_returnsSetPoints() {
        assertThat(rule.getBeanId()).isEqualTo("setPoints");
    }
}
