// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link ScoringResult} record VO (DEC-22 Iron Law RED-first, E22S03
 * AC-RED-FIRST-EVIDENCE + AC-VO-INVARIANT-COVERAGE).
 *
 * <p>Per DEC-36: same-package white-box tests; ScoringResult is in de.vvwt.tm.scoring.
 * AC-VO-INVARIANT-COVERAGE: covers record equality, non-null-component invariants, field
 * round-trip, toString sanity, range invariants.
 */
class ScoringResultTest {

    /**
     * Sentinel: ScoringResult type exists in the correct package. Fails to compile before
     * production type exists (RED evidence).
     */
    @Test
    void scoringResult_typeExistsInScoringPackage() {
        Class<?> clazz = ScoringResult.class;
        assertThat(clazz).isNotNull();
    }

    // ------------------------------------------------------------------
    // Record equality (AC-VO-INVARIANT-COVERAGE)
    // ------------------------------------------------------------------

    /** Two ScoringResult instances with equal components are equals() (Java record equality). */
    @Test
    void equality_twoInstancesWithEqualComponents_areEqual() {
        ScoringResult a = new ScoringResult(3, 0);
        ScoringResult b = new ScoringResult(3, 0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    /** Two ScoringResult instances with different components are not equal. */
    @Test
    void equality_instancesWithDifferentComponents_areNotEqual() {
        ScoringResult a = new ScoringResult(3, 0);
        ScoringResult b = new ScoringResult(2, 1);

        assertThat(a).isNotEqualTo(b);
    }

    // ------------------------------------------------------------------
    // Field round-trip (AC-VO-INVARIANT-COVERAGE)
    // ------------------------------------------------------------------

    /** Components read back exactly as written — record accessor round-trip. */
    @Test
    void fieldRoundTrip_componentsReadBackAsWritten() {
        ScoringResult result = new ScoringResult(2, 1);

        assertThat(result.team1Points()).isEqualTo(2);
        assertThat(result.team2Points()).isEqualTo(1);
    }

    /** Draw result round-trip (both teams equal points). */
    @Test
    void fieldRoundTrip_drawResult_bothPointsEqualToStored() {
        ScoringResult result = new ScoringResult(1, 1);

        assertThat(result.team1Points()).isEqualTo(1);
        assertThat(result.team2Points()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Range invariants (AC-VO-RANGE-INVARIANTS + AC-VO-NULL-COMPONENT-THROWS)
    // ------------------------------------------------------------------

    /**
     * Negative team1Points throws IllegalArgumentException (range invariant).
     * AC-VO-RANGE-INVARIANTS: non-negative points count on ScoringResult.
     */
    @Test
    void rangeInvariant_negativeTeam1Points_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new ScoringResult(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team1Points");
    }

    /** Negative team2Points throws IllegalArgumentException (range invariant). */
    @Test
    void rangeInvariant_negativeTeam2Points_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new ScoringResult(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team2Points");
    }

    /** Zero is the valid minimum for both point components. */
    @Test
    void rangeInvariant_zeroIsValidMinimum() {
        ScoringResult result = new ScoringResult(0, 0);

        assertThat(result.team1Points()).isEqualTo(0);
        assertThat(result.team2Points()).isEqualTo(0);
    }

    /** Parameterized test covering valid point combinations. */
    @ParameterizedTest
    @CsvSource({"3, 0", "2, 1", "1, 2", "0, 3", "1, 1"})
    void fieldRoundTrip_variousValidCombinations_roundTripCorrectly(
            int team1Points, int team2Points) {
        ScoringResult result = new ScoringResult(team1Points, team2Points);

        assertThat(result.team1Points()).isEqualTo(team1Points);
        assertThat(result.team2Points()).isEqualTo(team2Points);
    }

    // ------------------------------------------------------------------
    // toString sanity (AC-TEST-FILES-CREATED implied)
    // ------------------------------------------------------------------

    /** toString() output is non-null and contains both field values. */
    @Test
    void toString_containsFieldValues() {
        ScoringResult result = new ScoringResult(3, 0);
        String str = result.toString();

        assertThat(str).isNotNull();
        assertThat(str).contains("3");
        assertThat(str).contains("0");
    }
}
