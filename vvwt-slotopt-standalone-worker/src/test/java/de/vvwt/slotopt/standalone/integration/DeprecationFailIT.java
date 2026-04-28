package de.vvwt.slotopt.standalone.integration;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test: past-deprecation fail-fast path.
 *
 * <p>Verifies that when Ed25519 is announced with a past {@code deprecation_date} (already
 * expired), the worker fails fast at startup with a non-zero exit code.
 *
 * <p>This test is anchored to E41S04 AC-DEC43-PAST-DEPRECATION-FAIL-FAST, which implements the
 * DEC-43 D3 / DEC-48 boundary: a deprecation_date in the past means the algorithm is past its UTC
 * end-of-day boundary and new registrations MUST be rejected by the client.
 *
 * <p>Story: E41S06 AC-INTEGRATION-TEST-DEPRECATION-FAIL.
 */
class DeprecationFailIT {

    private DispatcherStub stub;
    private WorkerLauncher launcher;

    @BeforeEach
    void setUp() throws java.io.IOException {
        stub = new DispatcherStub();
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    @DisplayName(
            "AC-INTEGRATION-TEST-DEPRECATION-FAIL: worker fails fast with non-zero exit code when"
                    + " algorithm has past deprecation_date")
    void deprecationFail_pastDeprecationDate_failsFastWithNonZeroExit() {
        // Past date: yesterday — algorithm is past its deprecation deadline
        LocalDate pastDate = LocalDate.now().minusDays(1);
        stub.stubAlgorithmWithPastDeprecation(pastDate);
        launcher = new WorkerLauncher(stub.baseUri());

        WorkerRunResult result = launcher.launch();

        // Worker must exit non-zero (EX_CONFIG = 78 per
        // ExitCode.ALGORITHM_DEPRECATED_PAST_DEADLINE)
        assertThat(result.exitCode())
                .as(
                        "Worker must exit non-zero when algorithm is past deprecation deadline;"
                                + " stderr: "
                                + result.stderr())
                .isNotEqualTo(0);

        // Should be EX_CONFIG (78)
        assertThat(result.exitCode())
                .as("Exit code must be EX_CONFIG (78) for past-deprecation fail-fast")
                .isEqualTo(ExitCode.ALGORITHM_DEPRECATED_PAST_DEADLINE);
    }
}
