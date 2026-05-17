package de.vvwt.slotopt.standalone.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test: deprecation warning path.
 *
 * <p>Verifies that when Ed25519 is announced with a future {@code deprecation_date}, the worker:
 *
 * <ul>
 *   <li>Emits a {@code algorithm_deprecation_warning} structured event
 *   <li>Writes the required stderr warning line
 *   <li>Continues running (does NOT exit non-zero)
 * </ul>
 *
 * <p>DEC-43 D3: "When a client successfully registers using an algorithm whose deprecation_date is
 * non-null and in the future, the client MUST surface a clear admin warning."
 *
 * <p>Story: E41S06 AC-INTEGRATION-TEST-DEPRECATION-WARNING, AC-OBSERVABILITY-EVENT-MATRIX
 * (algorithm_deprecation_warning event). Fix: E41S07 AC-FIX-DETERMINISTIC-DEPRECATION-TESTS —
 * replaced LocalDate.now().plusMonths(6) with UTC-anchored fixed date LocalDate.of(2099, 12, 31) to
 * eliminate timezone-fragility. The DEC-48 boundary for 2099-12-31 is 2100-01-01T00:00Z, always in
 * the future at any realistic test execution time, making the fixture unambiguously future-dated
 * regardless of JVM default timezone.
 */
class DeprecationWarningIT {

    /**
     * Future deprecation date — unambiguously after any realistic test execution instant. DEC-48:
     * 2099-12-31 + 1 day = 2100-01-01T00:00:00Z, always in the future → accepted with warning.
     * UTC-anchored; no LocalDate.now() dependency (E41S07 fix).
     */
    private static final LocalDate FUTURE_DATE = LocalDate.of(2099, 12, 31);

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
            "AC-INTEGRATION-TEST-DEPRECATION-WARNING: worker emits warning event + stderr line"
                    + " and continues running when algorithm has future deprecation_date")
    void deprecationWarning_futureDeprecationDate_warningEmittedAndWorkerContinues() {
        // Future date: 2099-12-31 — unambiguously before its UTC end-of-day boundary
        // (2100-01-01T00:00Z)
        // at any realistic test execution instant, regardless of JVM default timezone (E41S07 fix).
        stub.stubAlgorithmWithFutureDeprecation(FUTURE_DATE);
        launcher = new WorkerLauncher(stub.baseUri());

        WorkerRunResult result = launchWithShutdownAfterDelay();

        // Worker should exit gracefully (not fail)
        assertThat(result.exitCode())
                .as(
                        "Worker should exit 0 (future deprecation is a warning, not a failure;"
                                + " stderr: "
                                + result.stderr()
                                + ")")
                .isEqualTo(0);

        // Structured event emitted
        assertThat(result.capturedEvents())
                .as("algorithm_deprecation_warning event must be emitted")
                .contains("algorithm_deprecation_warning");

        // Stderr warning line
        assertThat(result.stderr())
                .as("Stderr must contain the deprecation warning line per DEC-43 D3")
                .contains("WARNING: Signature algorithm 'Ed25519' will be deprecated on");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Launches the worker, waits briefly for startup, then requests graceful shutdown.
     *
     * <p>The dispatcher stub returns 204 on pull-packet (no work), so the worker will enter the
     * idle polling loop. We trigger shutdown after a short delay.
     */
    private WorkerRunResult launchWithShutdownAfterDelay() {
        WorkerRunResult[] resultRef = new WorkerRunResult[1];
        Thread workerThread =
                new Thread(() -> resultRef[0] = launcher.launch(), "worker-launch-thread");
        workerThread.start();

        // Give the worker time to complete bootstrap (including the deprecation warning)
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Request graceful shutdown — worker will exit on next loop iteration check
        launcher.requestShutdown();

        try {
            workerThread.join(15_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return resultRef[0] != null ? resultRef[0] : new WorkerRunResult(-1, "TIMEOUT", List.of());
    }
}
