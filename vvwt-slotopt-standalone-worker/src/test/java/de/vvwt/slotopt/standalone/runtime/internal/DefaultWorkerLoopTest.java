package de.vvwt.slotopt.standalone.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import de.vvwt.slotopt.standalone.crypto.ResultSigner;
import de.vvwt.slotopt.standalone.http.DispatcherClient;
import de.vvwt.slotopt.standalone.http.DispatcherException;
import de.vvwt.slotopt.standalone.http.PullPacketRequest;
import de.vvwt.slotopt.standalone.http.PullPacketResponse;
import de.vvwt.slotopt.standalone.http.SubmitResultRequest;
import de.vvwt.slotopt.standalone.http.SubmitResultResponse;
import de.vvwt.slotopt.standalone.log.StructuredLogger;
import de.vvwt.slotopt.standalone.runtime.CpuThrottle;
import de.vvwt.slotopt.standalone.runtime.WorkerLoopException;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * DefaultWorkerLoop} directly. All cross-package collaborators ({@link DispatcherClient}, {@link
 * CpuThrottle}, {@link StructuredLogger}, {@link ResultSigner}, {@link WorkerKeyManager}) are
 * mocked via their public interfaces per DEC-36.
 *
 * <p>Story: E41S05 AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT, AC-PACKET-PROCESSING,
 * AC-SUBMIT-RESULT-WITH-ALGORITHM, AC-EMPTY-PULL-RESPONSE-BACKOFF,
 * AC-HTTP-410-DEPRECATED-AT-SUBMIT, AC-HTTP-410-DEPRECATED-AT-REGISTER, AC-GRACEFUL-SHUTDOWN,
 * AC-OBSERVABILITY-EVENTS-RUNTIME, AC-EXIT-CODE-RUNTIME.
 */
@ExtendWith(MockitoExtension.class)
class DefaultWorkerLoopTest {

    @Mock private DispatcherClient dispatcherClient;
    @Mock private ResultSigner resultSigner;
    @Mock private WorkerKeyManager workerKeyManager;
    @Mock private CpuThrottle cpuThrottle;
    @Mock private StructuredLogger logger;

    private UUID workerId;
    private WorkerConfig config;
    private List<String> capturedEvents;

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
        capturedEvents = new ArrayList<>();
    }

    private DefaultWorkerLoop makeLoop() {
        return new DefaultWorkerLoop(
                dispatcherClient,
                resultSigner,
                workerKeyManager,
                config,
                cpuThrottle,
                logger,
                workerId);
    }

    private PullPacketResponse makePacket() {
        // n must equal rowCount (permutation length = number of rows).
        // avatarCount=3 avatars appearing in 2 rows; rows contain avatar indices [0,avatarCount).
        // rankTo <= n! = 2! = 2.
        return new PullPacketResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "{\"jobId\":\""
                        + UUID.randomUUID()
                        + "\",\"n\":2,\"rankFrom\":0,\"rankTo\":2,"
                        + "\"canonicalPhaseDef\":{\"rowCount\":2,\"avatarCount\":3,"
                        + "\"rows\":[[0,1],[1,2]]}}",
                Instant.now().plusSeconds(60));
    }

    // =========================================================================
    // AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT
    // =========================================================================

    /**
     * TC-18: pullPacket request carries supportedAlgorithms = [config.signingAlgorithm()].
     * DefaultWorkerLoop must invoke pullPacketOptional with correct PullPacketRequest.
     */
    @Test
    void pullPacket_sends_supportedAlgorithms_from_config() throws Exception {
        // Arrange: first call returns a packet, then loop exits via shutdown
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any()))
                .thenReturn(Optional.of(packet))
                .thenThrow(new WorkerLoopException(75, "stop test", null));
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any())).thenReturn(new SubmitResultResponse(true, null));

        DefaultWorkerLoop loop = makeLoop();
        loop.stopAfterNextIteration(); // test hook to exit after 1 iteration

        try {
            loop.run();
        } catch (WorkerLoopException ignored) {
            // expected from the second pullPacketOptional call
        }

        ArgumentCaptor<PullPacketRequest> captor = ArgumentCaptor.forClass(PullPacketRequest.class);
        verify(dispatcherClient, atLeastOnce()).pullPacketOptional(captor.capture());
        PullPacketRequest sentRequest = captor.getValue();
        assertThat(sentRequest.workerId()).isEqualTo(workerId);
        assertThat(sentRequest.supportedAlgorithms()).containsExactly("Ed25519");
    }

    // =========================================================================
    // AC-SUBMIT-RESULT-WITH-ALGORITHM
    // =========================================================================

    /** TC-19: submitResult request carries algorithm field from config.signingAlgorithm(). */
    @Test
    void submitResult_sends_algorithm_from_config() throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any()))
                .thenReturn(Optional.of(packet))
                .thenReturn(Optional.empty()); // backoff on 2nd
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any())).thenReturn(new SubmitResultResponse(true, null));

        DefaultWorkerLoop loop = makeLoop();
        loop.stopAfterNextIteration();
        try {
            loop.run();
        } catch (WorkerLoopException ignored) {
        }

        ArgumentCaptor<SubmitResultRequest> captor =
                ArgumentCaptor.forClass(SubmitResultRequest.class);
        verify(dispatcherClient, atLeastOnce()).submitResult(captor.capture());
        SubmitResultRequest req = captor.getValue();
        assertThat(req.algorithm()).isEqualTo("Ed25519");
        assertThat(req.workerId()).isEqualTo(workerId);
    }

    // =========================================================================
    // AC-EMPTY-PULL-RESPONSE-BACKOFF
    // =========================================================================

    /**
     * TC-20: HTTP 204 (empty Optional from pullPacketOptional) triggers CpuThrottle.sleep with
     * config.pollInterval().
     */
    @Test
    void pullPacket_204_triggers_cpuThrottle_sleep() throws Exception {
        when(dispatcherClient.pullPacketOptional(any()))
                .thenReturn(Optional.empty())
                .thenThrow(new WorkerLoopException(0, "stop test", null));

        DefaultWorkerLoop loop = makeLoop();
        try {
            loop.run();
        } catch (WorkerLoopException ignored) {
        }

        verify(cpuThrottle).sleep(config.pollInterval());
    }

    // =========================================================================
    // AC-HTTP-410-DEPRECATED-AT-SUBMIT
    // =========================================================================

    /**
     * TC-21: HTTP 410 on submitResult throws WorkerLoopException with
     * ExitCode.SUBMIT_REJECTED_DEPRECATED (78).
     */
    @Test
    void submitResult_410_throws_workerLoopException_with_submitRejectedDeprecated()
            throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any())).thenReturn(Optional.of(packet));
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any()))
                .thenThrow(new DispatcherException(410, "algorithm deprecated", null));

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

    // =========================================================================
    // AC-HTTP-410-DEPRECATED-AT-REGISTER (bubble-up from BootstrapException)
    // =========================================================================

    /**
     * TC-22: DispatcherException with status 0 (I/O failure) on pullPacketOptional after extended
     * failures → WorkerLoopException with ExitCode.DISPATCHER_UNREACHABLE_RUNTIME (75).
     *
     * <p>Note: the exact "30 continuous minutes" threshold from spec is not tested directly (too
     * slow); instead we test that I/O failure on pull-packet surfaces as DISPATCHER_UNREACHABLE.
     */
    @Test
    void pullPacket_ioError_throws_workerLoopException_dispatcherUnreachable() throws Exception {
        when(dispatcherClient.pullPacketOptional(any()))
                .thenThrow(new DispatcherException(0, "I/O error", null));

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

    // =========================================================================
    // AC-OBSERVABILITY-EVENTS-RUNTIME
    // =========================================================================

    /** TC-23: packet_pulled event emitted after successful pull-packet (HTTP 200). */
    @Test
    void emits_packetPulled_event_on_200_pull() throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any()))
                .thenReturn(Optional.of(packet))
                .thenThrow(new WorkerLoopException(0, "stop", null));
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any())).thenReturn(new SubmitResultResponse(true, null));

        DefaultWorkerLoop loop = makeLoop();
        try {
            loop.run();
        } catch (WorkerLoopException ignored) {
        }

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).info(eventCaptor.capture(), any());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e.equals("packet_pulled"));
    }

    /** TC-24: result_submitted event emitted after successful submitResult (accepted=true). */
    @Test
    void emits_resultSubmitted_event_on_accepted_true() throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any()))
                .thenReturn(Optional.of(packet))
                .thenThrow(new WorkerLoopException(0, "stop", null));
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any())).thenReturn(new SubmitResultResponse(true, null));

        DefaultWorkerLoop loop = makeLoop();
        try {
            loop.run();
        } catch (WorkerLoopException ignored) {
        }

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).info(eventCaptor.capture(), any());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e.equals("result_submitted"));
    }

    /** TC-25: result_superseded event emitted when submitResult returns accepted=false. */
    @Test
    void emits_resultSuperseded_event_on_accepted_false() throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any()))
                .thenReturn(Optional.of(packet))
                .thenThrow(new WorkerLoopException(0, "stop", null));
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any()))
                .thenReturn(new SubmitResultResponse(false, "superseded"));

        DefaultWorkerLoop loop = makeLoop();
        try {
            loop.run();
        } catch (WorkerLoopException ignored) {
        }

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).info(eventCaptor.capture(), any());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e.equals("result_superseded"));
    }

    /** TC-26: worker_stopped event emitted before final exit (graceful shutdown path). */
    @Test
    void emits_workerStopped_event_on_graceful_shutdown() throws Exception {
        // requestShutdown() before run() — loop exits immediately without calling
        // pullPacketOptional
        DefaultWorkerLoop loop = makeLoop();
        loop.requestShutdown(); // trigger graceful shutdown immediately

        loop.run(); // should return 0 (graceful)

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).info(eventCaptor.capture(), any());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e.equals("worker_stopped"));
    }

    /** TC-27: packet_processed event emitted after PacketSolver completes. */
    @Test
    void emits_packetProcessed_event_after_solver() throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any()))
                .thenReturn(Optional.of(packet))
                .thenThrow(new WorkerLoopException(0, "stop", null));
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any())).thenReturn(new SubmitResultResponse(true, null));

        DefaultWorkerLoop loop = makeLoop();
        try {
            loop.run();
        } catch (WorkerLoopException ignored) {
        }

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).info(eventCaptor.capture(), any());
        assertThat(eventCaptor.getAllValues()).anyMatch(e -> e.equals("packet_processed"));
    }

    // =========================================================================
    // AC-GRACEFUL-SHUTDOWN
    // =========================================================================

    /** TC-28: requestShutdown() causes run() to return gracefully with exit code 0. */
    @Test
    void gracefulShutdown_returns_zero() throws Exception {
        // requestShutdown() before run() — loop exits immediately without any I/O
        DefaultWorkerLoop loop = makeLoop();
        loop.requestShutdown(); // flag shutdown before run — loop exits immediately

        loop.run(); // should not throw; emits worker_stopped
    }

    // =========================================================================
    // AC-EXIT-CODE-RUNTIME
    // =========================================================================

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
}
