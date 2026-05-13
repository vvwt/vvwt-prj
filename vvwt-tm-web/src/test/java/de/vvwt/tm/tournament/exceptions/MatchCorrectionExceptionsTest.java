package de.vvwt.tm.tournament.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests for the four correction-guard exception classes at {@code
 * de.vvwt.tm.tournament.exceptions.*} (E48S25, AC-EXCEPTION-CONTRACT-CORRECTION).
 *
 * <h2>RED state (DEC-22)</h2>
 *
 * <p>This test was committed RED: the four exception classes did not exist at commit time, causing
 * a compile error. Proves tests written before implementation (DEC-22 Iron Law).
 *
 * <h2>Contract verified</h2>
 *
 * <ul>
 *   <li>Each exception extends {@link RuntimeException} (unchecked)
 *   <li>Constructor populates {@code getMessage()} with a non-null, non-empty string
 *   <li>Types are distinguishable (different classes; not collapsed into one)
 * </ul>
 *
 * <h2>AC-SEC-EXCEPTION-NO-LEAK</h2>
 *
 * <p>None of the exception constructors accept credentials, session tokens, or raw SQL — verified
 * by constructor signatures in this test file.
 */
@DisplayName("MatchCorrectionExceptionsTest — E48S25 AC-EXCEPTION-CONTRACT-CORRECTION")
class MatchCorrectionExceptionsTest {

    // -----------------------------------------------------------------------
    // PhaseStateGuardException — thrown when the phase is not ACTIVE (HTTP 409)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PhaseStateGuardException: extends RuntimeException, message not null")
    void phaseStateGuardException_isRuntimeException() {
        PhaseStateGuardException ex = new PhaseStateGuardException("phase is not ACTIVE: PENDING");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("PhaseStateGuardException: message contains supplied text")
    void phaseStateGuardException_messageContainsText() {
        PhaseStateGuardException ex =
                new PhaseStateGuardException("phase is not ACTIVE: COMPLETED");
        assertThat(ex.getMessage()).contains("COMPLETED");
    }

    // -----------------------------------------------------------------------
    // MatchStateGuardException — thrown when the match is INPROGRESS/ONCHECK (HTTP 409)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("MatchStateGuardException: extends RuntimeException, message not null")
    void matchStateGuardException_isRuntimeException() {
        MatchStateGuardException ex =
                new MatchStateGuardException("match is INPROGRESS: live scoring active");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("MatchStateGuardException: message contains supplied text")
    void matchStateGuardException_messageContainsText() {
        MatchStateGuardException ex = new MatchStateGuardException("match is ONCHECK");
        assertThat(ex.getMessage()).contains("ONCHECK");
    }

    // -----------------------------------------------------------------------
    // StandoffFormatMismatchException — STANDOFF on non-FIXED_2_SETS (HTTP 422)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("StandoffFormatMismatchException: extends RuntimeException, message not null")
    void standoffFormatMismatchException_isRuntimeException() {
        StandoffFormatMismatchException ex =
                new StandoffFormatMismatchException("STANDOFF not allowed for format BEST_OF_3");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("StandoffFormatMismatchException: message contains supplied text")
    void standoffFormatMismatchException_messageContainsText() {
        StandoffFormatMismatchException ex =
                new StandoffFormatMismatchException("STANDOFF not allowed for format BEST_OF_5");
        assertThat(ex.getMessage()).contains("BEST_OF_5");
    }

    // -----------------------------------------------------------------------
    // TournamentNotActiveException — tournament status not ACTIVE (HTTP 409)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TournamentNotActiveException: extends RuntimeException, message not null")
    void tournamentNotActiveException_isRuntimeException() {
        TournamentNotActiveException ex =
                new TournamentNotActiveException("tournament is not ACTIVE: DRAFT");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("TournamentNotActiveException: message contains supplied text")
    void tournamentNotActiveException_messageContainsText() {
        TournamentNotActiveException ex =
                new TournamentNotActiveException("tournament is not ACTIVE: PLANNED");
        assertThat(ex.getMessage()).contains("PLANNED");
    }

    // -----------------------------------------------------------------------
    // Type-distinctness: all four are different classes (not collapsed)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("All four exception types are distinct classes")
    void allFourExceptions_areDistinctClasses() {
        PhaseStateGuardException e1 = new PhaseStateGuardException("p");
        MatchStateGuardException e2 = new MatchStateGuardException("m");
        StandoffFormatMismatchException e3 = new StandoffFormatMismatchException("s");
        TournamentNotActiveException e4 = new TournamentNotActiveException("t");

        assertThat(e1.getClass()).isNotEqualTo(e2.getClass());
        assertThat(e1.getClass()).isNotEqualTo(e3.getClass());
        assertThat(e1.getClass()).isNotEqualTo(e4.getClass());
        assertThat(e2.getClass()).isNotEqualTo(e3.getClass());
        assertThat(e2.getClass()).isNotEqualTo(e4.getClass());
        assertThat(e3.getClass()).isNotEqualTo(e4.getClass());
    }
}
