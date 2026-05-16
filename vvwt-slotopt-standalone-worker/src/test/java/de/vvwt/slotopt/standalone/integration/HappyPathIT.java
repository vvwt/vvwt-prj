// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test: happy-path end-to-end round-trip.
 *
 * <p>Verifies that the worker:
 *
 * <ul>
 *   <li>Queries the algorithm announcement endpoint
 *   <li>Registers its key with algorithm="Ed25519"
 *   <li>Pulls a packet with supportedAlgorithms=["Ed25519"]
 *   <li>Submits the result with algorithm="Ed25519"
 *   <li>Exits with code 0 on graceful shutdown
 * </ul>
 *
 * <p>Also covers AC-OBSERVABILITY-EVENT-MATRIX for the events observable in a happy-path
 * single-packet run, and AC-FINAL-ASSEMBLY-SMOKE for the full round-trip within 30 s.
 *
 * <p>DEC-36: only public interfaces referenced from this cross-package test.
 *
 * <p>Story: E41S06 AC-INTEGRATION-TEST-HAPPY-PATH, AC-OBSERVABILITY-EVENT-MATRIX,
 * AC-FINAL-ASSEMBLY-SMOKE.
 */
class HappyPathIT {

    private DispatcherStub stub;
    private WorkerLauncher launcher;

    @BeforeEach
    void setUp() throws IOException {
        stub = new DispatcherStub();
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    @DisplayName(
            "AC-INTEGRATION-TEST-HAPPY-PATH: full round-trip completes with exit code 0 and"
                    + " correct HTTP interactions")
    void happyPath_fullRoundTrip_exitCode0() {
        stub.stubHappyPath();
        launcher = new WorkerLauncher(stub.baseUri());

        WorkerRunResult result = launchWithStopAfterNextIteration();

        // Exit code 0 — graceful shutdown
        assertThat(result.exitCode())
                .as(
                        "Worker should exit 0 (graceful shutdown); stderr: "
                                + result.stderr()
                                + "; events: "
                                + result.capturedEvents())
                .isEqualTo(0);

        // Verify HTTP interactions recorded
        assertThat(stub.requestsFor("/api/algorithms"))
                .as("GET /api/algorithms must be called once")
                .hasSize(1);

        assertThat(stub.requestsFor("/api/register-key"))
                .as("POST /api/register-key must be called once")
                .hasSize(1);

        assertThat(stub.requestsFor("/api/register-key").get(0).body())
                .as("POST /api/register-key must include algorithm Ed25519")
                .contains("Ed25519");

        assertThat(stub.requestsFor("/api/pull-packet"))
                .as("POST /api/pull-packet must be called at least once")
                .isNotEmpty();

        assertThat(stub.requestsFor("/api/pull-packet").get(0).body())
                .as("POST /api/pull-packet must include supportedAlgorithms Ed25519")
                .contains("Ed25519");

        assertThat(stub.requestsFor("/api/submit-result"))
                .as("POST /api/submit-result must be called once")
                .hasSize(1);

        assertThat(stub.requestsFor("/api/submit-result").get(0).body())
                .as("POST /api/submit-result must include algorithm Ed25519")
                .contains("Ed25519");
    }

    @Test
    @DisplayName(
            "AC-OBSERVABILITY-EVENT-MATRIX: bootstrap events worker_started, algorithms_announced,"
                    + " algorithm_picked, key_registered are emitted on happy path")
    void happyPath_bootstrapObservabilityEvents_emitted() {
        stub.stubHappyPath();
        launcher = new WorkerLauncher(stub.baseUri());

        WorkerRunResult result = launchWithStopAfterNextIteration();

        assertThat(result.capturedEvents())
                .as(
                        "Bootstrap events must include worker_started, algorithms_announced,"
                                + " algorithm_picked, key_registered")
                .contains(
                        "worker_started",
                        "algorithms_announced",
                        "algorithm_picked",
                        "key_registered");
    }

    @Test
    @DisplayName(
            "AC-OBSERVABILITY-EVENT-MATRIX: runtime events packet_pulled, packet_processed,"
                    + " result_submitted, worker_stopped are emitted on happy path")
    void happyPath_runtimeObservabilityEvents_emitted() {
        stub.stubHappyPath();
        launcher = new WorkerLauncher(stub.baseUri());

        WorkerRunResult result = launchWithStopAfterNextIteration();

        assertThat(result.capturedEvents())
                .as(
                        "Runtime events must include packet_pulled, packet_processed,"
                                + " result_submitted, worker_stopped")
                .contains(
                        "packet_pulled", "packet_processed", "result_submitted", "worker_stopped");
    }

    @Test
    @DisplayName(
            "AC-OBSERVABILITY-EVENT-MATRIX: result_superseded event emitted when submit-result"
                    + " returns accepted=false")
    void happyPath_resultSuperseded_eventEmitted() {
        stub.stubHappyPathSuperseded();
        launcher = new WorkerLauncher(stub.baseUri());

        WorkerRunResult result = launchWithStopAfterNextIteration();

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.capturedEvents())
                .as(
                        "result_superseded event must be emitted when submit-result returns"
                                + " accepted=false")
                .contains("result_superseded");
    }

    @Test
    @DisplayName(
            "AC-FINAL-ASSEMBLY-SMOKE: complete round-trip announcement→register→pull→process"
                    + "→submit→shutdown within 30 seconds")
    void finalAssemblySmoke_roundTrip_completesWithinTimeLimit() {
        stub.stubHappyPath();
        launcher = new WorkerLauncher(stub.baseUri());

        long startMs = System.currentTimeMillis();
        WorkerRunResult result = launchWithStopAfterNextIteration();
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(result.exitCode())
                .as(
                        "Smoke test: worker should exit 0 within time budget; stderr: "
                                + result.stderr())
                .isEqualTo(0);
        assertThat(elapsedMs)
                .as("Smoke test: round-trip must complete within 30 seconds")
                .isLessThan(30_000L);

        // Verify all four endpoints were exercised
        assertThat(stub.requestsFor("/api/algorithms")).isNotEmpty();
        assertThat(stub.requestsFor("/api/register-key")).isNotEmpty();
        assertThat(stub.requestsFor("/api/pull-packet")).isNotEmpty();
        assertThat(stub.requestsFor("/api/submit-result")).isNotEmpty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Launches the worker in a background thread and signals stopAfterNextIteration after the
     * worker has started. Waits for completion within 30 seconds.
     */
    private WorkerRunResult launchWithStopAfterNextIteration() {
        WorkerRunResult[] resultRef = new WorkerRunResult[1];
        Thread workerThread =
                new Thread(() -> resultRef[0] = launcher.launch(), "worker-launch-thread");
        workerThread.start();

        // Give the worker time to start up and reach the loop
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Signal stop-after-next-iteration (loop stops after processing the one stubbed packet)
        launcher.stopAfterNextIteration();

        try {
            workerThread.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return resultRef[0] != null
                ? resultRef[0]
                : new WorkerRunResult(-1, "TIMEOUT — worker did not complete", List.of());
    }
}
