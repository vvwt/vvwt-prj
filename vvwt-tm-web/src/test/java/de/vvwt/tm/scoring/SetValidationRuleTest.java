package de.vvwt.tm.scoring;

import de.vvwt.tm.tournament.MatchFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link SetValidationRule} interface (DEC-22 Iron Law RED-first,
 * E22S03 AC-RED-FIRST-EVIDENCE).
 *
 * <p>These tests serve as the type-exists sentinel and interface-contract gate.
 * Concrete set-validation behavior (StandardVolleyballSet, TimeBoundedSet) is
 * tested in E22S04 scope.
 *
 * <p>Per DEC-36: this test class is in the same package as {@link SetValidationRule}
 * (de.vvwt.tm.scoring). White-box same-package access permitted.
 * AC-NO-MOCKITO-VERIFY-ON-QUERIES: isSetClosed is a query — no verify() on it.
 */
class SetValidationRuleTest {

    /**
     * Sentinel: SetValidationRule type exists in the correct package and is an interface.
     * This test fails to compile before the production type exists (RED evidence).
     */
    @Test
    void setValidationRule_typeExistsInScoringPackage() {
        Class<?> clazz = SetValidationRule.class;
        assertThat(clazz).isInterface();
    }

    /**
     * getBeanId() signature: returns non-null, non-blank String.
     * No Mockito verify() — getBeanId is a query method (AC-NO-MOCKITO-VERIFY-ON-QUERIES).
     */
    @Test
    void getBeanId_returnsNonNullString() {
        SetValidationRule stub = new SetValidationRule() {
            @Override
            public ValidationResult isSetClosed(
                    int team1Pts, int team2Pts, int setIndex, MatchFormat format) {
                return ValidationResult.winner1();
            }

            @Override
            public String getBeanId() {
                return "testSetRule";
            }
        };

        String id = stub.getBeanId();

        assertThat(id).isNotNull().isNotBlank();
        assertThat(id).isEqualTo("testSetRule");
    }

    /**
     * isSetClosed() returns ValidationResult (verifies return type via assignment).
     * No Mockito verify() on isSetClosed — it is a query method
     * (AC-NO-MOCKITO-VERIFY-ON-QUERIES).
     */
    @Test
    void isSetClosed_returnsNonNullValidationResult() {
        MatchFormat format = MatchFormat.BEST_OF_3;

        SetValidationRule stub = new SetValidationRule() {
            @Override
            public ValidationResult isSetClosed(
                    int team1Pts, int team2Pts, int setIndex, MatchFormat format) {
                return ValidationResult.winner1();
            }

            @Override
            public String getBeanId() {
                return "testSetRule";
            }
        };

        ValidationResult result = stub.isSetClosed(25, 20, 0, format);

        assertThat(result).isNotNull();
        assertThat(result.isClosed()).isTrue();
    }
}
