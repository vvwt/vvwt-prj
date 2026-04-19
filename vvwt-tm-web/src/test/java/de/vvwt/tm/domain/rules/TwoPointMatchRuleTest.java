package de.vvwt.tm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for {@link TwoPointMatchRule} (AC9 + AC12 null/negative guards). */
class TwoPointMatchRuleTest {

    private TwoPointMatchRule rule;

    @BeforeEach
    void setUp() {
        rule = new TwoPointMatchRule();
    }

    // -----------------------------------------------------------------------
    // AC9 — parametrized happy-path cases
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "format={0} team1={1} team2={2} sets={3} → ({4},{5})")
    @CsvSource({
        // format,           t1, t2, sc, expected1, expected2
        "BEST_OF_5,          3,  0,  3,  2, 0", // AC9 row 1: team1 wins → 2/0
        "BEST_OF_5,          3,  1,  4,  2, 0", // AC9 row 2: team1 wins → 2/0
        "BEST_OF_5,          3,  2,  5,  2, 0", // AC9 row 3: team1 wins tie-break → still 2/0
        "BEST_OF_5,          0,  3,  3,  0, 2", // AC9 row 4: team2 wins → 0/2
        "FIXED_2_SETS,       2,  0,  2,  2, 0", // AC9 row 5: team1 wins → 2/0
        "FIXED_2_SETS,       1,  1,  2,  1, 1", // AC9 row 6: tie allowed → 1/1
    })
    void calculatePoints_winner2Loser0_tie11(
            MatchFormat format, int t1, int t2, int sc, int exp1, int exp2) {

        MatchOutcome outcome = new MatchOutcome(t1, t2, sc);
        ScoringResult result = rule.calculatePoints(outcome, format);

        assertThat(result.team1Points()).isEqualTo(exp1);
        assertThat(result.team2Points()).isEqualTo(exp2);
    }

    // -----------------------------------------------------------------------
    // AC9 row 7 — impossible tie in BEST_OF_N throws IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    void calculatePoints_impossibleTieInBestOfN_throwsIllegalState() {
        // BEST_OF_5 with 2:2 is structurally impossible — surfaces cascade bug
        MatchOutcome outcome = new MatchOutcome(2, 2, 4);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BEST_OF_5")
                .hasMessageContaining("allowsTies=false");
    }

    @Test
    void calculatePoints_impossibleTieInBestOf1_throwsIllegalState() {
        MatchOutcome outcome = new MatchOutcome(0, 0, 0);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_1))
                .isInstanceOf(IllegalStateException.class);
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
        MatchOutcome outcome = new MatchOutcome(-1, 3, 2);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team1SetsWon");
    }

    @Test
    void calculatePoints_negativeSetCount_throws() {
        MatchOutcome outcome = new MatchOutcome(3, 0, -3);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setCount");
    }

    @Test
    void calculatePoints_inconsistentSetCount_throws() {
        MatchOutcome outcome = new MatchOutcome(3, 2, 99);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setCount");
    }

    // -----------------------------------------------------------------------
    // AC1 — getBeanId
    // -----------------------------------------------------------------------

    @Test
    void getBeanId_returnsTwoPoint() {
        assertThat(rule.getBeanId()).isEqualTo("twoPoint");
    }
}
