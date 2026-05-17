package de.vvwt.slotopt.standalone.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import de.vvwt.slotopt.standalone.log.StructuredLogger;
import de.vvwt.slotopt.standalone.runtime.CpuThrottle;
import de.vvwt.slotopt.standalone.runtime.WorkerLoopException;
import de.vvwt.slotopt.worker.runtime.ComputeStep;
import de.vvwt.slotopt.worker.runtime.ComputeStepException;
import de.vvwt.slotopt.worker.runtime.ComputeStepResult;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Same-package white-box tests for {@link DefaultWorkerLoop}.
 *
 * <p>Per DEC-36 same-package carve-out: test is in {@code runtime.internal} — may reference {@link
 * DefaultWorkerLoop} directly. All cross-package collaborators ({@link ComputeStep}, {@link
 * CpuThrottle}, {@link StructuredLogger}) are mocked via their public interfaces per DEC-36.
 *
 * <p>Re-wired in E63S01: the loop now delegates compute logic to {@link ComputeStep} (extracted to
 * the shared runtime library). Tests of pull/solve/sign/submit behaviour live in {@code
 * de.vvwt.slotopt.worker.runtime.internal.DefaultComputeStepTest} in the runtime module.
 *
 * <p>These tests cover only the loop's own orchestration responsibilities:
 *
 * <ul>
 *   <li>AC-EMPTY-PULL-RESPONSE-BACKOFF: NO_PACKET → CpuThrottle.sleep(pollInterval)
 *   <li>AC-GRACEFUL-SHUTDOWN: requestShutdown() → run() returns normally (exit 0)
 *   <li>AC-OBSERVABILITY-EVENTS-RUNTIME: worker_stopped event on exit
 *   <li>AC-EXIT-CODE-RUNTIME: ComputeStepException exit code bubbles up as WorkerLoopException
 *   <li>AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT: execute() is invoked with correct workerId
 *       and supportedAlgorithms
 * </ul>
 *
 * <p>Story: E41S05 AC-DEFAULT-WORKER-LOOP; E63S01 AC-GOV-NO-TEST-ONLY-MEMBERS-IN-EXTRACTED-CODE
 * (stopAfterNextIteration removed), AC-TEST-OUTAGE-SEAM-PLUGGABLE.
 */
@ExtendWith(MockitoExtension.class)
class DefaultWorkerLoopTest {

    @Mock private ComputeStep computeStep;
    @Mock private CpuThrottle cpuThrottle;
    @Mock private StructuredLogger logger;

    private UUID workerId;
    private WorkerConfig config;

    @BeforeEach
    void setUp() {
        workerId = UUID.randomUUID();
        config =
                new WorkerConfig(
                        URI.create("http://localhost:8080"),
                        Path.of("/tmp/keys"),
                        Duration.ofSeconds(30),
                        "Ed25519",
                        Duration.ofSeconds(30),
                        50,
                        "test-worker",
                        "text");
    }

    private DefaultWorkerLoop makeLoop() {
        return new DefaultWorkerLoop(computeStep, config, cpuThrottle, logger, workerId);
    }

    // =========================================================================
    // AC-EMPTY-PULL-RESPONSE-BACKOFF
    // =========================================================================

    /**
     * TC-20: NO_PACKET from ComputeStep triggers CpuThrottle.sleep with config.pollInterval().
     *
     * <p>The second execute() call throws WorkerLoopException to terminate the loop (simulating an
     * unrecoverable error that stops the loop — the only way to terminate without requestShutdown).
     */
    @Test
    void noPacket_triggers_cpuThrottle_sleep() throws Exception {
        when(computeStep.execute(any(), any()))
                .thenReturn(ComputeStepResult.NO_PACKET)
                .thenThrow(new ComputeStepException(75, "stop test", null));

        DefaultWorkerLoop loop = makeLoop();
        try {
            loop.run();
        } catch (WorkerLoopException ignored) {
            // Expected — ComputeStepException converted to WorkerLoopException
        }

        verify(cpuThrottle).sleep(config.pollInterval());
    }

    // =========================================================================
    // AC-GRACEFUL-SHUTDOWN
    // =========================================================================

    /** TC-26: worker_stopped event emitted before final exit (graceful shutdown path). */
    @Test
    void emits_workerStopped_event_on_graceful_shutdown() throws Exception {
        DefaultWorkerLoop loop = makeLoop();
        loop.requestShutdown(); // shutdown flag set before run() — loop exits immediately

        loop.run(); // should return normally (exit 0)

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).info(eventCaptor.capture(), any());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e.equals("worker_stopped"));
    }

    /** TC-28: requestShutdown() causes run() to return gracefully without throwing. */
    @Test
    void gracefulShutdown_returns_zero() throws Exception {
        DefaultWorkerLoop loop = makeLoop();
        loop.requestShutdown(); // flag shutdown before run — loop exits immediately

        loop.run(); // must not throw
    }

    // =========================================================================
    // AC-EXIT-CODE-RUNTIME
    // =========================================================================

    /**
     * TC-21: ComputeStepException with exit code 78 is re-thrown as WorkerLoopException with exit
     * code 78 (SUBMIT_REJECTED_DEPRECATED).
     */
    @Test
    void computeStepException_exit78_wraps_as_workerLoopException_exit78() throws Exception {
        when(computeStep.execute(any(), any()))
                .thenThrow(new ComputeStepException(78, "algorithm deprecated at submit", null));

        DefaultWorkerLoop loop = makeLoop();

        assertThatThrownBy(loop::run)
                .isInstanceOf(WorkerLoopException.class)
                .satisfies(
                        e -> {
                            WorkerLoopException wle = (WorkerLoopException) e;
                            assertThat(wle.getExitCode())
                                    .isEqualTo(ExitCode.SUBMIT_REJECTED_DEPRECATED);
                        });
    }

    /**
     * TC-22: ComputeStepException with exit code 75 is re-thrown as WorkerLoopException with exit
     * code 75 (DISPATCHER_UNREACHABLE_RUNTIME).
     */
    @Test
    void computeStepException_exit75_wraps_as_workerLoopException_exit75() throws Exception {
        when(computeStep.execute(any(), any()))
                .thenThrow(new ComputeStepException(75, "I/O error", null));

        DefaultWorkerLoop loop = makeLoop();

        assertThatThrownBy(loop::run)
                .isInstanceOf(WorkerLoopException.class)
                .satisfies(
                        e -> {
                            WorkerLoopException wle = (WorkerLoopException) e;
                            assertThat(wle.getExitCode())
                                    .isEqualTo(ExitCode.DISPATCHER_UNREACHABLE_RUNTIME);
                        });
    }

    /** TC-29: submit_rejected_deprecated exit code = 78. */
    @Test
    void exitCode_submitRejectedDeprecated_is_78() {
        assertThat(ExitCode.SUBMIT_REJECTED_DEPRECATED).isEqualTo(78);
    }

    /** TC-30: dispatcher_unreachable_runtime exit code = 75. */
    @Test
    void exitCode_dispatcherUnreachableRuntime_is_75() {
        assertThat(ExitCode.DISPATCHER_UNREACHABLE_RUNTIME).isEqualTo(75);
    }

    /** TC-31: interrupted exit code = 130. */
    @Test
    void exitCode_interrupted_is_130() {
        assertThat(ExitCode.INTERRUPTED).isEqualTo(130);
    }

    // =========================================================================
    // AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT
    // =========================================================================

    /**
     * TC-18: execute() is called with the correct workerId and supportedAlgorithms =
     * [config.signingAlgorithm()].
     */
    @Test
    void execute_called_with_correct_workerId_and_supportedAlgorithms() throws Exception {
        // First call processes a packet; second call throws to stop the loop
        when(computeStep.execute(any(), any()))
                .thenReturn(ComputeStepResult.PACKET_PROCESSED)
                .thenThrow(new ComputeStepException(75, "stop test", null));

        DefaultWorkerLoop loop = makeLoop();
        try {
            loop.run();
        } catch (WorkerLoopException ignored) {
        }

        ArgumentCaptor<UUID> workerIdCaptor = ArgumentCaptor.forClass(UUID.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> algoCaptor = ArgumentCaptor.forClass(List.class);
        verify(computeStep, atLeastOnce()).execute(workerIdCaptor.capture(), algoCaptor.capture());

        assertThat(workerIdCaptor.getValue()).isEqualTo(workerId);
        assertThat(algoCaptor.getValue()).containsExactly("Ed25519");
    }

    // =========================================================================
    // AC-OBSERVABILITY-EVENTS-RUNTIME
    // =========================================================================

    /**
     * TC-27: packet_processed_cycle event emitted after PACKET_PROCESSED result from ComputeStep.
     */
    @Test
    void emits_packetProcessedCycle_event_after_packet_processed() throws Exception {
        when(computeStep.execute(any(), any()))
                .thenReturn(ComputeStepResult.PACKET_PROCESSED)
                .thenThrow(new ComputeStepException(75, "stop test", null));

        DefaultWorkerLoop loop = makeLoop();
        try {
            loop.run();
        } catch (WorkerLoopException ignored) {
        }

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).info(eventCaptor.capture(), any());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e.equals("packet_processed_cycle"));
    }
}
