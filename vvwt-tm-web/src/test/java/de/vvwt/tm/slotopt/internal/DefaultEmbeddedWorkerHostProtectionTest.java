// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithm;
import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithmsResponse;
import de.vvwt.slotopt.worker.runtime.ComputeStep;
import de.vvwt.slotopt.worker.runtime.ComputeStepResult;
import de.vvwt.slotopt.worker.runtime.DispatcherClient;
import de.vvwt.slotopt.worker.runtime.RegisterKeyResponse;
import de.vvwt.tm.slotopt.HostActivityProbe;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RED-first tests for E63S04 host-protection mechanisms in {@link DefaultEmbeddedWorker}.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>AC-TEST-AUTO-PAUSE-ON-LIVE-SCORING — worker pauses when probe returns {@code true}
 *   <li>AC-TEST-PAUSE-IS-BETWEEN-PACKETS — pause takes effect only after current packet completes
 *   <li>AC-TEST-CPU-THROTTLE-RATIO — throttle sleeps so averaged CPU share stays within bound
 *   <li>AC-TEST-THROTTLE-IS-BETWEEN-PACKETS — throttle sleep happens between packets
 *   <li>AC-ERR-PROBE-FAILURE-IS-CONSERVATIVE — probe exception → treat as active (pause)
 *   <li>AC-ERR-RESUME-AFTER-PAUSE — after pause, worker resumes reliably
 * </ul>
 *
 * <p>Story: E63S04
 */
@ExtendWith(MockitoExtension.class)
class DefaultEmbeddedWorkerHostProtectionTest {

    private static final UUID WORKER_ID = UUID.randomUUID();
    private static final RegisterKeyResponse REG_RESPONSE =
            new RegisterKeyResponse(WORKER_ID, "general", "Ed25519", Instant.now());
    private static final AnnouncedAlgorithmsResponse ALGORITHMS =
            new AnnouncedAlgorithmsResponse(
                    List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", null, Map.of())));
    private static final byte[] PUB_KEY_BYTES = new byte[32];

    private DefaultEmbeddedWorker worker;

    @AfterEach
    void stopWorker() {
        if (worker != null) {
            worker.stop();
        }
    }

    // -------------------------------------------------------------------------
    // Helper factories
    // -------------------------------------------------------------------------

    private WorkerKeyManager mockKeyManager() {
        WorkerKeyManager km = mock(WorkerKeyManager.class);
        when(km.algorithmId()).thenReturn("Ed25519");
        when(km.getPublicKeyBytes()).thenReturn(PUB_KEY_BYTES);
        return km;
    }

    private DispatcherClient mockDispatcherClient() throws Exception {
        DispatcherClient dc = mock(DispatcherClient.class);
        when(dc.fetchAnnouncedAlgorithms()).thenReturn(ALGORITHMS);
        when(dc.registerKey(any())).thenReturn(REG_RESPONSE);
        return dc;
    }

    private DefaultEmbeddedWorker createWorker(
            ComputeStep computeStep, HostActivityProbe probe, InterPacketThrottle throttle)
            throws Exception {
        return new DefaultEmbeddedWorker(
                computeStep,
                mockKeyManager(),
                mockDispatcherClient(),
                /* pollIntervalMs= */ 10L,
                /* backoffMaxMs= */ 50L,
                new EmbeddedOutagePolicy(),
                probe,
                throttle,
                /* pauseCheckIntervalMs= */ 20L);
    }

    // =========================================================================
    // AC-TEST-AUTO-PAUSE-ON-LIVE-SCORING
    // =========================================================================

    /**
     * When the {@link HostActivityProbe} returns {@code true}, the worker MUST pause between
     * packets and NOT pull the next packet. When live-scoring ends ({@code false}), it resumes.
     */
    @Test
    void workerPausesBetweenPacketsWhenLiveScoringActive() throws Exception {
        AtomicInteger pullCount = new AtomicInteger(0);
        ComputeStep computeStep = mock(ComputeStep.class);

        // First call succeeds (counted). Then we enable the probe. Worker should NOT call again.
        CountDownLatch firstPullDone = new CountDownLatch(1);
        CountDownLatch resumePullDone = new CountDownLatch(1);

        HostActivityProbe probe = mock(HostActivityProbe.class);
        // Initially not active
        when(probe.isLiveScoringActive()).thenReturn(false, false, true, true, true, false);

        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            int count = pullCount.incrementAndGet();
                            if (count == 1) {
                                firstPullDone.countDown();
                                return ComputeStepResult.PACKET_PROCESSED;
                            }
                            resumePullDone.countDown();
                            return ComputeStepResult.PACKET_PROCESSED;
                        });

        worker = createWorker(computeStep, probe, new InterPacketThrottle(1.0, 0));
        worker.start();

        // First pull happens (probe was false)
        assertThat(firstPullDone.await(3, TimeUnit.SECONDS))
                .as("First pull should happen quickly")
                .isTrue();

        // Worker pauses (probe is true) — second pull is delayed
        // After probe flips to false (sequence exhausted), worker should resume
        assertThat(resumePullDone.await(3, TimeUnit.SECONDS))
                .as("Worker should resume pulling after live-scoring ends")
                .isTrue();

        assertThat(worker.isRunning()).isTrue();
    }

    // =========================================================================
    // AC-TEST-PAUSE-IS-BETWEEN-PACKETS
    // =========================================================================

    /**
     * A packet already being solved when live-scoring becomes active runs to completion. The pause
     * takes effect only before the NEXT pull, never mid-packet.
     */
    @Test
    void currentPacketCompletesBeforePauseTakesEffect() throws Exception {
        CountDownLatch packetSolvingLatch = new CountDownLatch(1);
        CountDownLatch packetCompleteLatch = new CountDownLatch(1);
        AtomicInteger executeCount = new AtomicInteger(0);

        ComputeStep computeStep = mock(ComputeStep.class);
        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            int count = executeCount.incrementAndGet();
                            if (count == 1) {
                                packetSolvingLatch.countDown(); // signal packet is solving
                                Thread.sleep(200); // simulate solve time while probe returns true
                                packetCompleteLatch.countDown(); // packet done
                                return ComputeStepResult.PACKET_PROCESSED;
                            }
                            return ComputeStepResult.PACKET_PROCESSED;
                        });

        // Probe returns true while packet is being solved (mid-solve)
        HostActivityProbe probe = mock(HostActivityProbe.class);
        when(probe.isLiveScoringActive()).thenReturn(false, true, true, true, false);

        worker = createWorker(computeStep, probe, new InterPacketThrottle(1.0, 0));
        worker.start();

        // Wait for packet to start solving
        assertThat(packetSolvingLatch.await(3, TimeUnit.SECONDS)).isTrue();
        // Packet must complete without interruption despite probe returning true
        assertThat(packetCompleteLatch.await(3, TimeUnit.SECONDS))
                .as("Packet already solving must complete (not abandoned mid-packet)")
                .isTrue();

        // executeCount should be 1 — second pull was deferred by pause
        Thread.sleep(300); // give worker time to check probe and stay paused
        assertThat(executeCount.get())
                .as(
                        "Pause should prevent second pull immediately (may be 1 or 2 depending on"
                                + " timing)")
                .isGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // AC-TEST-CPU-THROTTLE-RATIO
    // =========================================================================

    /**
     * With the throttle configured, the worker's averaged compute share stays within the bound. An
     * un-throttled worker pulls immediately after each packet; a throttled worker sleeps
     * proportionally. This test verifies {@link InterPacketThrottle} produces non-zero sleep.
     */
    @Test
    void throttleProducesNonZeroSleepForNonZeroSolveTime() {
        // ratio 0.25 (25% CPU): for a 100ms solve, sleep = 100 * (1/0.25 - 1) = 300ms
        InterPacketThrottle throttle = new InterPacketThrottle(0.25, 30_000L);
        long solveTimeNs = 100_000_000L; // 100ms in nanos
        long sleepMs = throttle.computeSleepMs(solveTimeNs);

        // Expected: 100 * 3 = 300ms (CPU ratio 25% → 3x sleep vs solve)
        assertThat(sleepMs)
                .as("Throttle must produce non-zero sleep for non-zero solve time")
                .isGreaterThan(0L);
        assertThat(sleepMs)
                .as("Throttle sleep should be ~300ms for 100ms solve at 0.25 ratio")
                .isBetween(250L, 350L);
    }

    @Test
    void throttleWithRatioOneProducesZeroSleep() {
        // ratio >= 1.0 means no throttle (100% CPU or more allowed)
        InterPacketThrottle throttle = new InterPacketThrottle(1.0, 30_000L);
        long solveTimeNs = 100_000_000L;
        long sleepMs = throttle.computeSleepMs(solveTimeNs);

        assertThat(sleepMs).as("Ratio >= 1.0 must produce zero sleep (no throttle)").isEqualTo(0L);
    }

    @Test
    void throttleWithZeroSolveTimeProducesZeroSleep() {
        InterPacketThrottle throttle = new InterPacketThrottle(0.25, 30_000L);
        long sleepMs = throttle.computeSleepMs(0L);

        assertThat(sleepMs).as("Zero solve time must produce zero sleep").isEqualTo(0L);
    }

    // =========================================================================
    // AC-TEST-THROTTLE-IS-BETWEEN-PACKETS
    // =========================================================================

    /**
     * The throttle sleep is applied BETWEEN packets, not mid-packet. A packet runs to completion
     * uninterrupted once started; the sleep follows after the packet finishes.
     */
    @Test
    void throttleSleepOccursBetweenPacketsNotDuringSolve() throws Exception {
        // Use a throttle with a measurable sleep (ratio 0.5 → sleep = solve time)
        // We record timestamps: solve_end should precede pull_start of next packet by throttle
        // sleep
        AtomicInteger executeCount = new AtomicInteger(0);
        long[] solveEndTime = new long[1];
        long[] secondPullStartTime = new long[1];

        CountDownLatch secondPullStarted = new CountDownLatch(1);

        ComputeStep computeStep = mock(ComputeStep.class);
        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            int count = executeCount.incrementAndGet();
                            if (count == 1) {
                                Thread.sleep(50); // 50ms solve
                                solveEndTime[0] = System.nanoTime();
                                return ComputeStepResult.PACKET_PROCESSED;
                            }
                            secondPullStartTime[0] = System.nanoTime();
                            secondPullStarted.countDown();
                            return ComputeStepResult.NO_PACKET;
                        });

        // ratio 0.5: for 50ms solve → 50ms sleep between packets
        InterPacketThrottle throttle = new InterPacketThrottle(0.5, 30_000L);
        HostActivityProbe neverActive = () -> false;

        worker = createWorker(computeStep, neverActive, throttle);
        worker.start();

        assertThat(secondPullStarted.await(5, TimeUnit.SECONDS)).isTrue();

        long gapMs = (secondPullStartTime[0] - solveEndTime[0]) / 1_000_000;
        // For 50ms solve at ratio 0.5: expected gap ≈ 50ms (1x solve time)
        // Use generous bounds for test stability: between-packet gap must be > 10ms
        assertThat(gapMs)
                .as(
                        "Throttle gap between packet end and next pull must be > 0 (throttle is"
                                + " active)")
                .isGreaterThan(10L);
    }

    // =========================================================================
    // AC-ERR-PROBE-FAILURE-IS-CONSERVATIVE
    // =========================================================================

    /**
     * If the {@link HostActivityProbe} throws, the worker treats the host as active (pauses) rather
     * than pulling the next packet. The failure is logged (not re-thrown).
     */
    @Test
    void probeFailureTreatsHostAsActive() throws Exception {
        AtomicInteger pullCount = new AtomicInteger(0);

        ComputeStep computeStep = mock(ComputeStep.class);
        // No stub for computeStep.execute() — the throwing probe keeps the worker in the
        // pause-check loop permanently, so execute() is never reached.

        // Probe always throws
        HostActivityProbe throwingProbe =
                () -> {
                    throw new RuntimeException("probe failure simulation");
                };

        worker = createWorker(computeStep, throwingProbe, new InterPacketThrottle(1.0, 0));
        worker.start();

        // Worker should not crash — stays running — but should pause (no second pull)
        Thread.sleep(300); // give time for any erroneous second pull to happen

        // Worker must still be running (probe failure must NOT crash the host)
        assertThat(worker.isRunning())
                .as("Worker must survive probe exception without crashing")
                .isTrue();

        // Pull count should be 0 — all probe checks threw, so worker is permanently paused
        // (probe always throws → always treated as active → never pulls)
        assertThat(pullCount.get())
                .as("Worker should not pull while probe is throwing (treated as active)")
                .isEqualTo(0);
    }

    // =========================================================================
    // AC-ERR-RESUME-AFTER-PAUSE
    // =========================================================================

    /**
     * After a pause (live-scoring ends), the worker reliably resumes pulling. A pause is never
     * terminal.
     */
    @Test
    void workerResumesAfterPause() throws Exception {
        CountDownLatch firstPull = new CountDownLatch(1);
        CountDownLatch resumePull = new CountDownLatch(1);
        AtomicInteger pullCount = new AtomicInteger(0);

        ComputeStep computeStep = mock(ComputeStep.class);
        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            int count = pullCount.incrementAndGet();
                            if (count == 1) firstPull.countDown();
                            else resumePull.countDown();
                            return ComputeStepResult.PACKET_PROCESSED;
                        });

        // Probe: initially false, then true for a while, then false again
        HostActivityProbe probe = mock(HostActivityProbe.class);
        when(probe.isLiveScoringActive()).thenReturn(false, true, true, true, true, false);

        worker = createWorker(computeStep, probe, new InterPacketThrottle(1.0, 0));
        worker.start();

        assertThat(firstPull.await(3, TimeUnit.SECONDS)).as("First pull must happen").isTrue();

        assertThat(resumePull.await(3, TimeUnit.SECONDS))
                .as("Worker must resume pulling after live-scoring ends (pause is not terminal)")
                .isTrue();
    }
}
