package de.vvwt.tm.domain.rules;

import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ThreePointMatchRule} (AC10 + AC12 null/negative guards).
 */
class ThreePointMatchRuleTest {

    private ThreePointMatchRule rule;

    @BeforeEach
    void setUp() {
        rule = new ThreePointMatchRule();
    }

    // -----------------------------------------------------------------------
    // AC10 — parametrized happy-path cases
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "format={0} team1={1} team2={2} sets={3} → ({4},{5})")
    @CsvSource({
        // format,           t1, t2, sc, expected1, expected2
        "BEST_OF_5,          3,  0,  3,  3, 0",  // AC10 row 1: 3-0 → NOT tie-break → 3/0
        "BEST_OF_5,          3,  1,  4,  3, 0",  // AC10 row 2: 3-1 → NOT tie-break (loser=1 ≠ 2) → 3/0
        "BEST_OF_5,          3,  2,  5,  2, 1",  // AC10 row 3: 3-2 → TIE-BREAK (loser=2=req-1) → 2/1
        "BEST_OF_3,          2,  0,  2,  3, 0",  // AC10 row 4: 2-0 → NOT tie-break → 3/0
        "BEST_OF_3,          2,  1,  3,  2, 1",  // AC10 row 5: 2-1 → TIE-BREAK (loser=1=req-1) → 2/1
        "BEST_OF_1,          1,  0,  1,  3, 0",  // AC10 row 6: BEST_OF_1 never tie-break → 3/0
        "FIXED_2_SETS,       2,  0,  2,  3, 0",  // AC10 row 7: FIXED_2_SETS team1 wins → 3/0 (no tie)
        "FIXED_2_SETS,       1,  1,  2,  1, 1",  // AC10 row 8: FIXED_2_SETS 1:1 tie → 1/1
    })
    void calculatePoints_threePointLogic(
            MatchFormat format, int t1, int t2, int sc, int exp1, int exp2) {

        MatchOutcome outcome = new MatchOutcome(t1, t2, sc);
        ScoringResult result = rule.calculatePoints(outcome, format);

        assertThat(result.team1Points()).isEqualTo(exp1);
        assertThat(result.team2Points()).isEqualTo(exp2);
    }

    // -----------------------------------------------------------------------
    // Symmetry test: team2 wins should mirror team1 results
    // -----------------------------------------------------------------------

    @Test
    void calculatePoints_team2WinsTieBreak_symmetricResult() {
        // BEST_OF_3 2:1 but team2 wins (2=team2, 1=team1)
        MatchOutcome outcome = new MatchOutcome(1, 2, 3);
        ScoringResult result = rule.calculatePoints(outcome, MatchFormat.BEST_OF_3);
        assertThat(result.team1Points()).isEqualTo(1);
        assertThat(result.team2Points()).isEqualTo(2);
    }

    @Test
    void calculatePoints_team2WinsNormal_symmetricResult() {
        // BEST_OF_5 team2 wins 3:0
        MatchOutcome outcome = new MatchOutcome(0, 3, 3);
        ScoringResult result = rule.calculatePoints(outcome, MatchFormat.BEST_OF_5);
        assertThat(result.team1Points()).isEqualTo(0);
        assertThat(result.team2Points()).isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // Impossible tie in BEST_OF_N throws IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    void calculatePoints_impossibleTieInBestOfN_throwsIllegalState() {
        MatchOutcome outcome = new MatchOutcome(2, 2, 4);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowsTies=false");
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
    void calculatePoints_negativeTeam2Sets_throws() {
        MatchOutcome outcome = new MatchOutcome(3, -1, 2);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team2SetsWon");
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
    void getBeanId_returnsThreePoint() {
        assertThat(rule.getBeanId()).isEqualTo("threePoint");
    }
}
