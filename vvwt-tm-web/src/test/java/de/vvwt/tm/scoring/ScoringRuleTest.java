package de.vvwt.tm.scoring;

import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link ScoringRule} interface (DEC-22 Iron Law RED-first,
 * E22S03 AC-RED-FIRST-EVIDENCE).
 *
 * <p>These tests serve as the type-exists sentinel and interface-contract gate.
 * Concrete rule behavior (SetPointsRule, ThreePointMatchRule, TwoPointMatchRule)
 * is tested in E22S04 scope.
 *
 * <p>Per DEC-36: this test class is in the same package as {@link ScoringRule}
 * (de.vvwt.tm.scoring), so white-box same-package access is permitted.
 * AC-NO-MOCKITO-VERIFY-ON-QUERIES: calculatePoints is a query — no verify() on it.
 */
class ScoringRuleTest {

    /**
     * Sentinel: ScoringRule type exists in the correct package and is an interface.
     * This test fails to compile before the production type exists (RED evidence).
     */
    @Test
    void scoringRule_typeExistsInScoringPackage() {
        // If ScoringRule does not exist in de.vvwt.tm.scoring, this file fails to compile.
        // The import resolves once the production type is created (GREEN).
        Class<?> clazz = ScoringRule.class;
        assertThat(clazz).isInterface();
    }

    /**
     * getBeanId() signature: returns non-null String.
     * Uses an anonymous implementation to verify the interface contract.
     * No Mockito verify() — getBeanId is a query method (AC-NO-MOCKITO-VERIFY-ON-QUERIES).
     */
    @Test
    void getBeanId_returnsNonNullString() {
        ScoringRule stub = new ScoringRule() {
            @Override
            public ScoringResult calculatePoints(MatchOutcome outcome, MatchFormat format) {
                return new ScoringResult(1, 0);
            }

            @Override
            public String getBeanId() {
                return "testRule";
            }
        };

        String id = stub.getBeanId();

        assertThat(id).isNotNull().isNotBlank();
        assertThat(id).isEqualTo("testRule");
    }

    /**
     * calculatePoints() returns ScoringResult (verifies return type via assignment).
     * No Mockito verify() on calculatePoints — it is a query method
     * (AC-NO-MOCKITO-VERIFY-ON-QUERIES).
     */
    @Test
    void calculatePoints_returnsNonNullScoringResult() {
        MatchOutcome outcome = new MatchOutcome(2, 1, 3);
        MatchFormat format = MatchFormat.BEST_OF_3;

        ScoringRule stub = new ScoringRule() {
            @Override
            public ScoringResult calculatePoints(MatchOutcome outcome, MatchFormat format) {
                return new ScoringResult(3, 0);
            }

            @Override
            public String getBeanId() {
                return "testRule";
            }
        };

        ScoringResult result = stub.calculatePoints(outcome, format);

        assertThat(result).isNotNull();
        assertThat(result.team1Points()).isEqualTo(3);
        assertThat(result.team2Points()).isEqualTo(0);
    }
}
