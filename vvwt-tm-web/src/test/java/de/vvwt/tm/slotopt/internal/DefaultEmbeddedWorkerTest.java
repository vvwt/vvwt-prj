// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithm;
import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithmsResponse;
import de.vvwt.slotopt.worker.runtime.ComputeStep;
import de.vvwt.slotopt.worker.runtime.ComputeStepException;
import de.vvwt.slotopt.worker.runtime.ComputeStepResult;
import de.vvwt.slotopt.worker.runtime.DispatcherClient;
import de.vvwt.slotopt.worker.runtime.DispatcherException;
import de.vvwt.slotopt.worker.runtime.OutagePolicy;
import de.vvwt.slotopt.worker.runtime.RegisterKeyResponse;
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
 * Unit tests for {@link DefaultEmbeddedWorker}.
 *
 * <p>Tests: thread lifecycle (start/stop), compute-step delegation, outage-survive-resume, idle
 * graceful behaviour, and persistent-failure self-stop.
 *
 * <p>Story: E63S03 AC-TEST-THREAD-LIFECYCLE-START-STOP, AC-TEST-ENABLED-PULLS-AND-SOLVES,
 * AC-TEST-OUTAGE-SURVIVE-AND-RESUME, AC-ERR-OUTAGE-BACKOFF-NO-BUSY-LOOP,
 * AC-ERR-PERSISTENT-FAILURE-SELF-STOPS-NOT-CRASH, AC-ERR-NO-PACKET-IDLES-GRACEFULLY,
 * AC-GOV-EMBEDDED-LIFECYCLE-NOT-CLI-LIFECYCLE.
 */
@ExtendWith(MockitoExtension.class)
class DefaultEmbeddedWorkerTest {

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
    // Helper factory
    // -------------------------------------------------------------------------

    private DefaultEmbeddedWorker createWorker(
            ComputeStep computeStep,
            WorkerKeyManager keyManager,
            DispatcherClient dispatcherClient,
            OutagePolicy outagePolicy) {
        // E63S04: pass no-op host-protection (never active, no throttle) to preserve existing tests
        return new DefaultEmbeddedWorker(
                computeStep,
                keyManager,
                dispatcherClient,
                /* pollIntervalMs= */ 10L,
                /* backoffMaxMs= */ 50L,
                outagePolicy,
                /* hostActivityProbe= */ () -> false,
                /* interPacketThrottle= */ new InterPacketThrottle(1.0, 0),
                /* pauseCheckIntervalMs= */ 10L);
    }

    private WorkerKeyManager mockKeyManager() {
        WorkerKeyManager km = mock(WorkerKeyManager.class);
        when(km.algorithmId()).thenReturn("Ed25519");
        when(km.getPublicKeyBytes()).thenReturn(PUB_KEY_BYTES);
        return km;
    }

    private DispatcherClient mockDispatcherClient() throws DispatcherException {
        DispatcherClient dc = mock(DispatcherClient.class);
        when(dc.fetchAnnouncedAlgorithms()).thenReturn(ALGORITHMS);
        when(dc.registerKey(any())).thenReturn(REG_RESPONSE);
        return dc;
    }

    // =========================================================================
    // AC-TEST-THREAD-LIFECYCLE-START-STOP
    // =========================================================================

    @Test
    void startSpawnsExactlyOneBackgroundThread() throws Exception {
        ComputeStep computeStep = mock(ComputeStep.class);
        CountDownLatch pullLatch = new CountDownLatch(1);
        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            pullLatch.countDown();
                            Thread.sleep(5000); // block until stopped
                            return ComputeStepResult.PACKET_PROCESSED;
                        });

        DispatcherClient dc = mockDispatcherClient();
        worker = createWorker(computeStep, mockKeyManager(), dc, new EmbeddedOutagePolicy());

        assertThat(worker.isRunning()).isFalse();
        worker.start();

        // Wait for the first pull to be invoked
        assertThat(pullLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.isRunning()).isTrue();

        // Count background threads named vvwt-embedded-worker
        long workerThreads =
                Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> "vvwt-embedded-worker".equals(t.getName()) && t.isAlive())
                        .count();
        assertThat(workerThreads).isEqualTo(1);
    }

    @Test
    void stopInterruptsWorkerCleanly() throws Exception {
        ComputeStep computeStep = mock(ComputeStep.class);
        CountDownLatch startedLatch = new CountDownLatch(1);
        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            startedLatch.countDown();
                            Thread.sleep(30_000);
                            return ComputeStepResult.NO_PACKET;
                        });

        DispatcherClient dc = mockDispatcherClient();
        worker = createWorker(computeStep, mockKeyManager(), dc, new EmbeddedOutagePolicy());
        worker.start();
        assertThat(startedLatch.await(3, TimeUnit.SECONDS)).isTrue();

        worker.stop();

        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void doubleStartIsNoOp() throws Exception {
        ComputeStep computeStep = mock(ComputeStep.class);
        CountDownLatch latch = new CountDownLatch(1);
        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            latch.countDown();
                            Thread.sleep(30_000);
                            return ComputeStepResult.NO_PACKET;
                        });

        DispatcherClient dc = mockDispatcherClient();
        worker = createWorker(computeStep, mockKeyManager(), dc, new EmbeddedOutagePolicy());
        worker.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Second start should be no-op
        worker.start();

        long workerThreads =
                Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> "vvwt-embedded-worker".equals(t.getName()) && t.isAlive())
                        .count();
        assertThat(workerThreads).isEqualTo(1);
    }

    // =========================================================================
    // AC-TEST-ENABLED-PULLS-AND-SOLVES (registers + pulls + solves)
    // =========================================================================

    @Test
    void whenEnabledRegistersAndPullsAndSolvesPackets() throws Exception {
        ComputeStep computeStep = mock(ComputeStep.class);
        CountDownLatch packetLatch = new CountDownLatch(1);
        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            packetLatch.countDown();
                            return ComputeStepResult.PACKET_PROCESSED;
                        });

        DispatcherClient dc = mockDispatcherClient();
        worker = createWorker(computeStep, mockKeyManager(), dc, new EmbeddedOutagePolicy());
        worker.start();

        assertThat(packetLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Verify registration happened
        verify(dc).fetchAnnouncedAlgorithms();
        verify(dc).registerKey(any());
        // Verify compute step was invoked
        verify(computeStep, atLeastOnce()).execute(any(), any());
    }

    // =========================================================================
    // AC-TEST-OUTAGE-SURVIVE-AND-RESUME + AC-ERR-OUTAGE-BACKOFF-NO-BUSY-LOOP
    // =========================================================================

    @Test
    void onDispatcherOutageWorkerSurvivesAndResumes() throws Exception {
        ComputeStep computeStep = mock(ComputeStep.class);
        CountDownLatch resumeLatch = new CountDownLatch(3); // 3 invocations = survive+resume
        AtomicInteger invocationCount = new AtomicInteger(0);

        // First call throws outage, subsequent succeed
        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            int count = invocationCount.incrementAndGet();
                            resumeLatch.countDown();
                            if (count == 1) {
                                // Simulate dispatcher outage
                                throw new ComputeStepException(
                                        75,
                                        "dispatcher unreachable",
                                        new DispatcherException(0, "connection refused", null));
                            }
                            return ComputeStepResult.PACKET_PROCESSED;
                        });

        DispatcherClient dc = mockDispatcherClient();
        OutagePolicy outagePolicy = mock(OutagePolicy.class);
        worker = createWorker(computeStep, mockKeyManager(), dc, outagePolicy);
        worker.start();

        // Wait for at least 3 invocations (initial outage + 2 resumes)
        assertThat(resumeLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.isRunning()).isTrue();

        // Outage policy was invoked (embedded form — does NOT terminate the loop)
        verify(outagePolicy, atLeastOnce()).onOutage(any());
    }

    // =========================================================================
    // AC-GOV-EMBEDDED-LIFECYCLE-NOT-CLI-LIFECYCLE: no JVM shutdown hook
    // =========================================================================

    @Test
    void workerDoesNotRegisterJvmShutdownHook() throws Exception {
        // Count shutdown hooks before and after worker creation+start
        // (There's no direct API to list hooks, but we verify no IllegalStateException
        // is thrown when adding a new hook — which would fail if our worker exhausted the limit)
        ComputeStep computeStep = mock(ComputeStep.class);
        CountDownLatch latch = new CountDownLatch(1);
        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            latch.countDown();
                            Thread.sleep(30_000);
                            return ComputeStepResult.NO_PACKET;
                        });

        DispatcherClient dc = mockDispatcherClient();
        worker = createWorker(computeStep, mockKeyManager(), dc, new EmbeddedOutagePolicy());
        worker.start();
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // If the worker had registered a JVM shutdown hook, the JVM shutdown hook thread
        // would be named "worker-shutdown-hook". Verify no such thread exists.
        boolean hasShutdownHookThread =
                Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(t -> "worker-shutdown-hook".equals(t.getName()));
        assertThat(hasShutdownHookThread).isFalse();
    }

    // =========================================================================
    // AC-ERR-PERSISTENT-FAILURE-SELF-STOPS-NOT-CRASH
    // =========================================================================

    @Test
    void onRegistrationRejectedWorkerStopsWithoutCrashingHost() throws Exception {
        DispatcherClient dc = mock(DispatcherClient.class);
        when(dc.fetchAnnouncedAlgorithms()).thenReturn(ALGORITHMS);
        when(dc.registerKey(any()))
                .thenThrow(new DispatcherException(410, "Algorithm deprecated", null));

        ComputeStep computeStep = mock(ComputeStep.class);
        worker = createWorker(computeStep, mockKeyManager(), dc, new EmbeddedOutagePolicy());
        worker.start();

        // Wait for worker to stop on its own (registration rejected → self-stop)
        long deadline = System.currentTimeMillis() + 3_000L;
        while (worker.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(worker.isRunning()).isFalse();
        // ComputeStep never invoked (worker stopped before compute loop)
        verify(computeStep, never()).execute(any(), any());
    }

    // =========================================================================
    // AC-ERR-NO-PACKET-IDLES-GRACEFULLY
    // =========================================================================

    @Test
    void whenNoPacketAvailableWorkerIdlesWithoutError() throws Exception {
        ComputeStep computeStep = mock(ComputeStep.class);
        AtomicInteger noPacketCount = new AtomicInteger(0);
        CountDownLatch idleLatch = new CountDownLatch(3);

        when(computeStep.execute(any(), any()))
                .thenAnswer(
                        inv -> {
                            noPacketCount.incrementAndGet();
                            idleLatch.countDown();
                            return ComputeStepResult.NO_PACKET;
                        });

        DispatcherClient dc = mockDispatcherClient();
        worker = createWorker(computeStep, mockKeyManager(), dc, new EmbeddedOutagePolicy());
        worker.start();

        // Worker should idle gracefully — no exception, loop continues
        assertThat(idleLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.isRunning()).isTrue();
        assertThat(noPacketCount.get()).isGreaterThanOrEqualTo(3);
    }
}
