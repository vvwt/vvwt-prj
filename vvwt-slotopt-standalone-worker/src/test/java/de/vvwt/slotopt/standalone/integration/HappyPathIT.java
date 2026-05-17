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
 * AC-FINAL-ASSEMBLY-SMOKE. E41S08: {@link #launchWithStopAfterNextIteration()} replaced fixed
 * {@code Thread.sleep(500)} readiness gate with deterministic {@code CountDownLatch} on {@code
 * packet_pulled} event to eliminate timing-race flake under {@code mvn verify} parallel load (AC2,
 * AC4).
 */
class HappyPathIT {

    /**
     * Maximum time (ms) to wait for the worker to observe {@code packet_pulled} before triggering
     * shutdown. Generous (25 s) to accommodate high-load CI environments while still failing fast
     * if the worker genuinely fails to start — well under the 30 s join timeout.
     */
    private static final long PACKET_PULLED_READINESS_TIMEOUT_MS = 25_000L;

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
     * Launches the worker in a background thread and requests graceful shutdown only after the
     * worker has emitted {@code packet_pulled} — signalling that a packet cycle has begun and the
     * shutdown signal will arrive after (not before) the packet is processed.
     *
     * <p>This replaces the previous fixed {@code Thread.sleep(500)} readiness gate that caused an
     * intermittent 30 s timeout under {@code mvn verify} parallel load: under high CPU contention
     * 500 ms was insufficient for the worker subprocess to start and begin a packet cycle, so
     * {@code requestShutdown()} arrived before {@code packet_pulled}, the worker stopped early, and
     * the event-sequence assertion failed on an empty list (E41S08 root cause).
     *
     * <p>AC4 — fail-fast diagnostic: if {@code packet_pulled} is not observed within {@value
     * #PACKET_PULLED_READINESS_TIMEOUT_MS} ms, {@code requestShutdown()} is called anyway (to avoid
     * a perpetual hang) and a diagnostic message is printed. The subsequent assertion on the empty
     * event list then fails with a descriptive message rather than silently timing out at the full
     * 30 s join limit.
     */
    private WorkerRunResult launchWithStopAfterNextIteration() {
        WorkerRunResult[] resultRef = new WorkerRunResult[1];
        Thread workerThread =
                new Thread(() -> resultRef[0] = launcher.launch(), "worker-launch-thread");
        workerThread.start();

        // Deterministic readiness gate: wait until packet_pulled is observed (or timeout).
        // launcher.launch() populates runtimeLoggerRef before submitting the worker thread,
        // so awaitFirstRuntimeEvent is safe to call from this thread without a data race.
        boolean packetPulledObserved;
        try {
            packetPulledObserved =
                    launcher.awaitFirstRuntimeEvent(
                            "packet_pulled", PACKET_PULLED_READINESS_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            packetPulledObserved = false;
        }

        if (!packetPulledObserved) {
            // AC4: fail-fast diagnostic — the worker did not reach packet_pulled within the
            // readiness timeout. This indicates a genuine worker startup failure, not a race.
            // Trigger shutdown anyway to unblock the join; the assertion will then fail with
            // a clear diagnostic message rather than a silent 30 s timeout.
            System.err.println(
                    "DIAGNOSTIC [E41S08]: packet_pulled not observed within "
                            + PACKET_PULLED_READINESS_TIMEOUT_MS
                            + " ms — worker may have failed to start."
                            + " Triggering shutdown to unblock join.");
        }

        // Signal graceful shutdown — arrives after packet is being processed (or on diagnostic
        // timeout).
        launcher.requestShutdown();

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
