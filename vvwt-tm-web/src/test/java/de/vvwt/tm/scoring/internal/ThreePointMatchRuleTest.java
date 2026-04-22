package de.vvwt.tm.scoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.scoring.ScoringResult;
import de.vvwt.tm.scoring.ScoringRule;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link ThreePointMatchRule} — same-package white-box tests per DEC-36.
 *
 * <p>Reconstructed TDD coverage from {@code de.vvwt.tm.domain.rules.ThreePointMatchRuleTest}
 * method-by-method per AC-TEST-METHOD-COVERAGE. RED-first per DEC-22 Iron Law.
 *
 * @see de.vvwt.tm.scoring.ScoringRule
 */
class ThreePointMatchRuleTest {

    private ThreePointMatchRule rule;

    @BeforeEach
    void setUp() {
        rule = new ThreePointMatchRule();
    }

    @Test
    void classImplementsScoringRule() {
        assertThat(rule).isInstanceOf(ScoringRule.class);
    }

    // -----------------------------------------------------------------------
    // AC-TEST-METHOD-COVERAGE: calculatePoints_threePointLogic (legacy: same name)
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "format={0} team1={1} team2={2} sets={3} → ({4},{5})")
    @CsvSource({
        "BEST_OF_5,          3,  0,  3,  3, 0",
        "BEST_OF_5,          3,  1,  4,  3, 0",
        "BEST_OF_5,          3,  2,  5,  2, 1",
        "BEST_OF_3,          2,  0,  2,  3, 0",
        "BEST_OF_3,          2,  1,  3,  2, 1",
        "BEST_OF_1,          1,  0,  1,  3, 0",
        "FIXED_2_SETS,       2,  0,  2,  3, 0",
        "FIXED_2_SETS,       1,  1,  2,  1, 1",
    })
    void calculatePoints_threePointLogic(
            MatchFormat format, int t1, int t2, int sc, int exp1, int exp2) {
        MatchOutcome outcome = new MatchOutcome(t1, t2, sc);
        ScoringResult result = rule.calculatePoints(outcome, format);
        assertThat(result.team1Points()).isEqualTo(exp1);
        assertThat(result.team2Points()).isEqualTo(exp2);
    }

    // Symmetry: team2 wins tie-break
    @Test
    void calculatePoints_team2WinsTieBreak_symmetricResult() {
        MatchOutcome outcome = new MatchOutcome(1, 2, 3);
        ScoringResult result = rule.calculatePoints(outcome, MatchFormat.BEST_OF_3);
        assertThat(result.team1Points()).isEqualTo(1);
        assertThat(result.team2Points()).isEqualTo(2);
    }

    // Symmetry: team2 wins normal
    @Test
    void calculatePoints_team2WinsNormal_symmetricResult() {
        MatchOutcome outcome = new MatchOutcome(0, 3, 3);
        ScoringResult result = rule.calculatePoints(outcome, MatchFormat.BEST_OF_5);
        assertThat(result.team1Points()).isEqualTo(0);
        assertThat(result.team2Points()).isEqualTo(3);
    }

    // Impossible tie in BEST_OF_N → IllegalStateException
    @Test
    void calculatePoints_impossibleTieInBestOfN_throwsIllegalState() {
        MatchOutcome outcome = new MatchOutcome(2, 2, 4);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowsTies=false");
    }

    // AC-NULL-GUARDS
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

    // AC-RANGE-CHECKS: negative team2SetsWon
    @Test
    void calculatePoints_negativeTeam2Sets_throws() {
        MatchOutcome outcome = new MatchOutcome(3, -1, 2);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team2SetsWon");
    }

    // AC-RANGE-CHECKS: inconsistent setCount
    @Test
    void calculatePoints_inconsistentSetCount_throws() {
        MatchOutcome outcome = new MatchOutcome(3, 2, 99);
        assertThatThrownBy(() -> rule.calculatePoints(outcome, MatchFormat.BEST_OF_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setCount");
    }

    // AC-BEAN-NAME-PRESERVED
    @Test
    void getBeanId_returnsThreePoint() {
        assertThat(rule.getBeanId()).isEqualTo("threePoint");
    }
}
