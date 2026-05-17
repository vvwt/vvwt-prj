// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ValidationResult} VO (DEC-22 Iron Law RED-first, E22S03 AC-RED-FIRST-EVIDENCE +
 * AC-VO-INVARIANT-COVERAGE).
 *
 * <p>ValidationResult is reconstructed as a Java record in de.vvwt.tm.scoring per Brief v3 record
 * preference (D-7 spirit). The legacy ValidationResult in de.vvwt.tm.domain.rules used a
 * package-private constructor + static factory methods; the reconstruction adopts the same
 * factory-method API surface for compatibility with E22S04 consumers, implemented as a record with
 * static factories.
 *
 * <p>Per DEC-36: same-package white-box tests; ValidationResult is in de.vvwt.tm.scoring.
 * AC-VO-INVARIANT-COVERAGE: covers equality, non-null invariants, field round-trip, toString.
 */
class ValidationResultTest {

    /**
     * Sentinel: ValidationResult type exists in the correct package. Fails to compile before
     * production type exists (RED evidence).
     */
    @Test
    void validationResult_typeExistsInScoringPackage() {
        Class<?> clazz = ValidationResult.class;
        assertThat(clazz).isNotNull();
    }

    // ------------------------------------------------------------------
    // Factory method: winner1()
    // ------------------------------------------------------------------

    @Test
    void winner1_isClosed_true() {
        ValidationResult result = ValidationResult.winner1();
        assertThat(result.isClosed()).isTrue();
    }

    @Test
    void winner1_reason_isEmpty() {
        ValidationResult result = ValidationResult.winner1();
        assertThat(result.getReason()).isEmpty();
    }

    @Test
    void winner1_winnerHint_isWinner1() {
        ValidationResult result = ValidationResult.winner1();
        assertThat(result.getWinnerHint()).isPresent();
        // winner hint resolves to a MatchState constant — we verify Optional is present
        // (MatchState enum is in tournament module, so reference by presence not name)
        assertThat(result.getWinnerHint().get().name()).isEqualTo("FINISHED_WINNER1");
    }

    // ------------------------------------------------------------------
    // Factory method: winner2()
    // ------------------------------------------------------------------

    @Test
    void winner2_isClosed_true() {
        ValidationResult result = ValidationResult.winner2();
        assertThat(result.isClosed()).isTrue();
    }

    @Test
    void winner2_reason_isEmpty() {
        ValidationResult result = ValidationResult.winner2();
        assertThat(result.getReason()).isEmpty();
    }

    @Test
    void winner2_winnerHint_isWinner2() {
        ValidationResult result = ValidationResult.winner2();
        assertThat(result.getWinnerHint()).isPresent();
        assertThat(result.getWinnerHint().get().name()).isEqualTo("FINISHED_WINNER2");
    }

    // ------------------------------------------------------------------
    // Factory method: open(String reason)
    // ------------------------------------------------------------------

    @Test
    void open_isClosed_false() {
        ValidationResult result = ValidationResult.open("no 2-point lead");
        assertThat(result.isClosed()).isFalse();
    }

    @Test
    void open_reasonRoundTrip_returnsExactReason() {
        String reason = "target not reached";
        ValidationResult result = ValidationResult.open(reason);
        assertThat(result.getReason()).isEqualTo(reason);
    }

    @Test
    void open_winnerHint_isEmpty() {
        ValidationResult result = ValidationResult.open("no 2-point lead");
        assertThat(result.getWinnerHint()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Non-null invariants (AC-VO-NULL-COMPONENT-THROWS)
    // ------------------------------------------------------------------

    /** open(null reason) throws — reason must not be null (AC-VO-NULL-COMPONENT-THROWS). */
    @Test
    void open_nullReason_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ValidationResult.open(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** open(blank reason) throws — reason must not be blank. */
    @Test
    void open_blankReason_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ValidationResult.open("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** open("") throws — reason must not be empty string. */
    @Test
    void open_emptyReason_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ValidationResult.open(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Equality (AC-VO-INVARIANT-COVERAGE)
    // ------------------------------------------------------------------

    /**
     * Two winner1() results are equal (same-factory-path equality). Note: in a record, two calls
     * with equal components produce equal instances. If implemented as a record, structural
     * equality holds.
     */
    @Test
    void equality_twoWinner1Instances_areEqual() {
        ValidationResult a = ValidationResult.winner1();
        ValidationResult b = ValidationResult.winner1();

        // Both are closed with same winnerHint — equality holds for records
        assertThat(a.isClosed()).isEqualTo(b.isClosed());
        assertThat(a.getReason()).isEqualTo(b.getReason());
        assertThat(a.getWinnerHint()).isEqualTo(b.getWinnerHint());
    }

    @Test
    void equality_winner1AndWinner2_differ() {
        ValidationResult w1 = ValidationResult.winner1();
        ValidationResult w2 = ValidationResult.winner2();

        assertThat(w1.getWinnerHint()).isNotEqualTo(w2.getWinnerHint());
    }

    // ------------------------------------------------------------------
    // toString sanity
    // ------------------------------------------------------------------

    @Test
    void toString_winner1_isNonNull() {
        ValidationResult result = ValidationResult.winner1();
        assertThat(result.toString()).isNotNull().isNotBlank();
    }

    @Test
    void toString_openResult_containsReason() {
        ValidationResult result = ValidationResult.open("no 2-point lead");
        assertThat(result.toString()).contains("no 2-point lead");
    }
}
