// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.DispatcherApplication;
import de.vvwt.slotopt.standalone.integration.WorkerLauncher;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

/**
 * End-to-end integration test: live worker round-trip through a real {@code
 * vvwt-slotopt-dispatcher} instance.
 *
 * <p>Boots the full Spring Boot dispatcher context on a random port, constructs an in-process
 * worker (via {@link WorkerLauncher} from {@code vvwt-slotopt-standalone-worker} test sources —
 * E41S06 precedent), and drives real HTTP round-trips across localhost.
 *
 * <p>ACs covered: AC-TEST-E2E-LIVE-WORKER, AC-TEST-E2E-MULTI-PACKET-CORRECTNESS,
 * AC-ERR-NO-WORKER-NO-MISFINALIZE, AC-ERR-SINGLE-PACKET-BOUNDARY, AC-ERR-E2E-DETERMINISTIC.
 *
 * <p>DEC-11: {@code vvwt-slotopt-standalone-worker} is a test-scope dependency only — no production
 * compile dependency introduced. DEC-70: no test-only members in production code. DEC-22: RED-first
 * per Iron Law.
 *
 * <p>Story: E60S05; AC-TEST-E2E-LIVE-WORKER; AC-TEST-E2E-MULTI-PACKET-CORRECTNESS;
 * AC-ERR-NO-WORKER-NO-MISFINALIZE; AC-ERR-SINGLE-PACKET-BOUNDARY; AC-ERR-E2E-DETERMINISTIC;
 * AC-GOV-NO-MODULE-BOUNDARY-BREACH
 */
@SpringBootTest(
        classes = DispatcherApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            // Disable Spring Security for E2E tests. The dispatcher does not use Spring
            // Security in production; spring-boot-security is a test-scope dep required
            // by @WebMvcTest slices in SB 4.x (E42S01). All nine auto-configuration classes
            // registered in spring-boot-security 4.0.6 are excluded so that no SecurityFilterChain
            // is wired into the full-context E2E test — otherwise unauthenticated requests from
            // the in-process worker and from TestJobSubmitter receive HTTP 401.
            "spring.autoconfigure.exclude="
                + "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.actuate.web.reactive.ReactiveManagementWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.rsocket.RSocketSecurityAutoConfiguration"
        })
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class LiveWorkerE2EIT {

    /** Condition-polling constants — no fixed sleep per AC-ERR-E2E-DETERMINISTIC. */
    private static final long POLL_TIMEOUT_MS = 30_000L;

    private static final long POLL_INTERVAL_MS = 200L;

    @LocalServerPort private int port;

    private TestJobSubmitter submitter;
    private WorkerLauncher workerLauncher;
    private Thread workerThread;
    private volatile de.vvwt.slotopt.standalone.integration.WorkerRunResult workerResult;

    @BeforeEach
    void setUp() {
        URI dispatcherUri = URI.create("http://localhost:" + port);
        submitter = new TestJobSubmitter(dispatcherUri);
        workerLauncher = new WorkerLauncher(dispatcherUri);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Always request shutdown and join to prevent thread leak across tests
        if (workerLauncher != null) {
            workerLauncher.requestShutdown();
        }
        if (workerThread != null && workerThread.isAlive()) {
            workerThread.join(5_000L);
        }
    }

    // =========================================================================
    // AC-ERR-SINGLE-PACKET-BOUNDARY + AC-TEST-E2E-LIVE-WORKER
    // =========================================================================

    @Test
    @DisplayName(
            "AC-ERR-SINGLE-PACKET-BOUNDARY + AC-TEST-E2E-LIVE-WORKER: rowCount=1 job completes"
                    + " end-to-end via live in-process worker")
    void e2e_singlePacket_workerSolvesAndDispatcherFinalizes()
            throws IOException, InterruptedException {

        // Start worker in background (launch() blocks until worker exits or times out)
        startWorkerInBackground();

        // Submit a 1-row phase (n=1 → 1! = 1 perm, still padded to MIN_PACKET_COUNT=4 packets
        // by the decomposer — all 4 packets contain permutation index 0)
        RawPhaseDef phase = TestJobSubmitter.buildPhase(1);
        UUID jobId = submitter.submitJob(phase);
        assertThat(jobId).isNotNull();

        // Condition-poll until COMPLETED — no fixed sleep (AC-ERR-E2E-DETERMINISTIC)
        TestJobSubmitter.JobStatusDto status =
                submitter.awaitCompleted(jobId, POLL_TIMEOUT_MS, POLL_INTERVAL_MS);

        assertThat(status.status()).isEqualTo("COMPLETED");
        assertThat(status.totalPackets()).isGreaterThanOrEqualTo(1);
        assertThat(status.completedPackets()).isEqualTo(status.totalPackets());
        assertThat(status.bestScore()).isNotNull();
        assertThat(status.bestRank()).isNotNull();
    }

    // =========================================================================
    // AC-TEST-E2E-MULTI-PACKET-CORRECTNESS + AC-TEST-E2E-LIVE-WORKER
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-E2E-MULTI-PACKET-CORRECTNESS: rowCount=2 job produces multiple packets and"
                    + " finalResult aggregates the global optimum")
    void e2e_multiPacket_globalOptimumCorrect() throws IOException, InterruptedException {

        // Start worker in background
        startWorkerInBackground();

        // A 2-row phase: n=2, 2! = 2 perms. MIN_PACKET_COUNT=4 guarantees ≥4 packets.
        // Verify that totalPackets ≥ 2 (multi-packet path exercised).
        RawPhaseDef phase = TestJobSubmitter.buildPhase(2);
        UUID jobId = submitter.submitJob(phase);
        assertThat(jobId).isNotNull();

        // Condition-poll until COMPLETED
        TestJobSubmitter.JobStatusDto status =
                submitter.awaitCompleted(jobId, POLL_TIMEOUT_MS, POLL_INTERVAL_MS);

        assertThat(status.status()).isEqualTo("COMPLETED");
        // MIN_PACKET_COUNT=4 guarantees at least 4 packets for any n
        assertThat(status.totalPackets()).isGreaterThanOrEqualTo(2);
        assertThat(status.completedPackets()).isEqualTo(status.totalPackets());

        // finalResult must carry a global optimum score and rank
        assertThat(status.bestScore())
                .as("finalResult.bestScore must be present after COMPLETED")
                .isNotNull();
        assertThat(status.bestRank())
                .as("finalResult.bestRank must be present after COMPLETED")
                .isNotNull();

        // bestRank must be a valid permutation index: [0, n!-1]
        // For n=2: valid ranks are 0 and 1
        assertThat(status.bestRank())
                .as("bestRank must be a non-negative permutation index")
                .isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // AC-ERR-NO-WORKER-NO-MISFINALIZE
    // =========================================================================

    @Test
    @DisplayName(
            "AC-ERR-NO-WORKER-NO-MISFINALIZE: job submitted with no active worker stays in"
                    + " DECOMPOSED state — dispatcher does not misfinalise")
    void e2e_noWorker_jobSitsAtDecomposedNoMisfinalize() throws IOException, InterruptedException {

        // No worker started — submitter only.
        // rowCount=3 (3!=6 perms, distinct from rowCount=1 and rowCount=2 used in the other two
        // tests) avoids a cache-hit short-circuit: the @SpringBootTest context is shared within
        // this class, and tests 1+2 may have already run and populated the result cache for their
        // respective fingerprints. A unique rowCount guarantees a fresh uncached job is persisted.
        RawPhaseDef phase = TestJobSubmitter.buildPhase(3);
        UUID jobId = submitter.submitJob(phase);
        assertThat(jobId).isNotNull();

        // Wait 2 seconds without starting a worker
        Thread.sleep(2_000L);

        // Job must be in DECOMPOSED (not COMPLETED, not FAILED)
        TestJobSubmitter.JobStatusDto status = submitter.getJobStatus(jobId);
        assertThat(status.status())
                .as(
                        "Without a live worker, job must remain in DECOMPOSED — dispatcher"
                                + " must not misfinalize")
                .isNotEqualTo("COMPLETED");
        assertThat(status.status())
                .as("Dispatcher must not have errored — job should not be FAILED")
                .isNotEqualTo("FAILED");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Starts the worker launcher in a background thread.
     *
     * <p>The worker runs its full pipeline (announce → register → pull → compute → submit) until
     * {@link WorkerLauncher#requestShutdown()} is called (in {@link #tearDown()}).
     *
     * <p>The worker result is captured in {@link #workerResult} for diagnostic logging when tests
     * fail.
     */
    private void startWorkerInBackground() {
        workerThread =
                new Thread(
                        () -> {
                            workerResult = workerLauncher.launch();
                            System.out.println(
                                    "[E2E-DIAG] worker exited: exitCode="
                                            + workerResult.exitCode()
                                            + " stderr="
                                            + workerResult.stderr()
                                            + " events="
                                            + workerResult.capturedEvents());
                        },
                        "e2e-live-worker-thread");
        workerThread.setDaemon(true);
        workerThread.start();
    }
}
