package de.vvwt.slotopt.dispatcher.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeprecatedAlgorithmException}.
 *
 * <p>TDD RED-first per DEC-22 / AC-DEPRECATED-ALGORITHM-EXCEPTION: authored before the exception
 * class exists. Tests assert message format, constructor contract, and accessor methods.
 *
 * <p>DEC-36: test is in the same {@code identity} package as the subject — white-box access
 * permitted.
 *
 * <p>Story: E40S03 / AC-DEPRECATED-ALGORITHM-EXCEPTION
 */
class DeprecatedAlgorithmExceptionTest {

    @Test
    void messageFormatMatchesAcContract() {
        LocalDate date = LocalDate.of(2026, 12, 31);
        DeprecatedAlgorithmException ex = new DeprecatedAlgorithmException("Ed25519", date);

        assertThat(ex.getMessage())
                .isEqualTo(
                        "Algorithm 'Ed25519' deprecated as of 2026-12-31"
                                + " (UTC end-of-day); new registrations rejected per DEC-43 D3.");
    }

    @Test
    void algorithmIdAccessorReturnsValue() {
        LocalDate date = LocalDate.of(2027, 6, 1);
        DeprecatedAlgorithmException ex = new DeprecatedAlgorithmException("ML-DSA-65", date);

        assertThat(ex.algorithmId()).isEqualTo("ML-DSA-65");
    }

    @Test
    void deprecationDateAccessorReturnsValue() {
        LocalDate date = LocalDate.of(2027, 6, 1);
        DeprecatedAlgorithmException ex = new DeprecatedAlgorithmException("ML-DSA-65", date);

        assertThat(ex.deprecationDate()).isEqualTo(date);
    }

    @Test
    void exceptionIsRuntimeException() {
        DeprecatedAlgorithmException ex =
                new DeprecatedAlgorithmException("Ed25519", LocalDate.of(2026, 12, 31));

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
