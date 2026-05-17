package de.vvwt.slotopt.worker.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.worker.runtime.ComputeStep;
import de.vvwt.slotopt.worker.runtime.ComputeStepException;
import de.vvwt.slotopt.worker.runtime.ComputeStepResult;
import de.vvwt.slotopt.worker.runtime.DispatcherClient;
import de.vvwt.slotopt.worker.runtime.DispatcherException;
import de.vvwt.slotopt.worker.runtime.PullPacketResponse;
import de.vvwt.slotopt.worker.runtime.ResultSigner;
import de.vvwt.slotopt.worker.runtime.SigningException;
import de.vvwt.slotopt.worker.runtime.SubmitResultResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Same-package white-box tests for {@link DefaultComputeStep}.
 *
 * <p>Per DEC-36 same-package carve-out: test is in {@code runtime.internal} — may reference {@link
 * DefaultComputeStep} directly. All cross-package collaborators ({@link DispatcherClient}, {@link
 * ResultSigner}) are mocked via their public interfaces per DEC-36.
 *
 * <p>Tests authored RED-first before {@link DefaultComputeStep} implementation existed, per DEC-22
 * Iron Law (Q-1a: new abstraction, no prior trustworthy oracle).
 *
 * <p>Story: E63S01 AC-TEST-RUNTIME-LIBRARY-UNIT-TESTS, AC-TEST-COMPUTE-PATH-BEHAVIOUR-PRESERVED,
 * AC-TEST-OUTAGE-SEAM-PLUGGABLE, AC-GOV-RED-FIRST.
 */
@ExtendWith(MockitoExtension.class)
class DefaultComputeStepTest {

    @Mock private DispatcherClient dispatcherClient;
    @Mock private ResultSigner resultSigner;

    private UUID workerId;
    private List<String> supportedAlgorithms;

    @BeforeEach
    void setUp() {
        workerId = UUID.randomUUID();
        supportedAlgorithms = List.of("Ed25519");
    }

    private DefaultComputeStep makeStep() {
        return new DefaultComputeStep(dispatcherClient, resultSigner, "Ed25519");
    }

    private PullPacketResponse makePacket() {
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
    // AC-TEST-COMPUTE-PATH-BEHAVIOUR-PRESERVED: HTTP 204 → NO_PACKET
    // =========================================================================

    /** TC-CS-01: HTTP 204 from pull-packet → execute() returns NO_PACKET. */
    @Test
    void execute_returns_noPacket_on_http204() throws Exception {
        when(dispatcherClient.pullPacketOptional(any())).thenReturn(Optional.empty());

        ComputeStep step = makeStep();
        ComputeStepResult result = step.execute(workerId, supportedAlgorithms);

        assertThat(result).isEqualTo(ComputeStepResult.NO_PACKET);
    }

    // =========================================================================
    // AC-TEST-COMPUTE-PATH-BEHAVIOUR-PRESERVED: HTTP 200 → PACKET_PROCESSED
    // =========================================================================

    /** TC-CS-02: HTTP 200 from pull-packet + successful sign + submit → PACKET_PROCESSED. */
    @Test
    void execute_returns_packetProcessed_on_success() throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any())).thenReturn(Optional.of(packet));
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any())).thenReturn(new SubmitResultResponse(true, null));

        ComputeStep step = makeStep();
        ComputeStepResult result = step.execute(workerId, supportedAlgorithms);

        assertThat(result).isEqualTo(ComputeStepResult.PACKET_PROCESSED);
    }

    // =========================================================================
    // AC-TEST-COMPUTE-PATH-BEHAVIOUR-PRESERVED: HTTP 200, accepted=false → PACKET_SUPERSEDED
    // =========================================================================

    /** TC-CS-02b: HTTP 200 + accepted=false → PACKET_SUPERSEDED. */
    @Test
    void execute_returns_packetSuperseded_when_accepted_false() throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any())).thenReturn(Optional.of(packet));
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any()))
                .thenReturn(new SubmitResultResponse(false, "superseded"));

        ComputeStep step = makeStep();
        ComputeStepResult result = step.execute(workerId, supportedAlgorithms);

        assertThat(result).isEqualTo(ComputeStepResult.PACKET_SUPERSEDED);
    }

    // =========================================================================
    // AC-ERR-SOLVE-FAILURE-PROPAGATION: dispatcher I/O error on pull-packet
    // =========================================================================

    /**
     * TC-CS-03: DispatcherException during pull-packet → ComputeStepException with exit code 75.
     */
    @Test
    void execute_throws_computeStepException_on_pull_ioError() {
        when(dispatcherClient.pullPacketOptional(any()))
                .thenThrow(new DispatcherException(0, "I/O error", null));

        ComputeStep step = makeStep();

        assertThatThrownBy(() -> step.execute(workerId, supportedAlgorithms))
                .isInstanceOf(ComputeStepException.class)
                .satisfies(e -> assertThat(((ComputeStepException) e).getExitCode()).isEqualTo(75));
    }

    // =========================================================================
    // AC-ERR-SOLVE-FAILURE-PROPAGATION: signing failure
    // =========================================================================

    /** TC-CS-04: SigningException during sign → ComputeStepException with exit code 75. */
    @Test
    void execute_throws_computeStepException_on_signing_failure() throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any())).thenReturn(Optional.of(packet));
        when(resultSigner.signResult(any()))
                .thenThrow(new SigningException("key error", new RuntimeException("jce")));

        ComputeStep step = makeStep();

        assertThatThrownBy(() -> step.execute(workerId, supportedAlgorithms))
                .isInstanceOf(ComputeStepException.class)
                .satisfies(e -> assertThat(((ComputeStepException) e).getExitCode()).isEqualTo(75));
    }

    // =========================================================================
    // AC-ERR-SOLVE-FAILURE-PROPAGATION: dispatcher I/O error on submit
    // =========================================================================

    /**
     * TC-CS-05: DispatcherException during submit-result (non-410) → ComputeStepException with exit
     * code 75.
     */
    @Test
    void execute_throws_computeStepException_on_submit_ioError() throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any())).thenReturn(Optional.of(packet));
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any()))
                .thenThrow(new DispatcherException(0, "submit I/O error", null));

        ComputeStep step = makeStep();

        assertThatThrownBy(() -> step.execute(workerId, supportedAlgorithms))
                .isInstanceOf(ComputeStepException.class)
                .satisfies(e -> assertThat(((ComputeStepException) e).getExitCode()).isEqualTo(75));
    }

    // =========================================================================
    // HTTP 410 at submit → exit code 78 (SUBMIT_REJECTED_DEPRECATED)
    // =========================================================================

    /** TC-CS-06: HTTP 410 on submit-result → ComputeStepException with exit code 78. */
    @Test
    void execute_throws_computeStepException_exit78_on_submit_410() throws Exception {
        PullPacketResponse packet = makePacket();
        when(dispatcherClient.pullPacketOptional(any())).thenReturn(Optional.of(packet));
        when(resultSigner.signResult(any())).thenReturn(new byte[64]);
        when(dispatcherClient.submitResult(any()))
                .thenThrow(new DispatcherException(410, "algorithm deprecated", null));

        ComputeStep step = makeStep();

        assertThatThrownBy(() -> step.execute(workerId, supportedAlgorithms))
                .isInstanceOf(ComputeStepException.class)
                .satisfies(e -> assertThat(((ComputeStepException) e).getExitCode()).isEqualTo(78));
    }

    // =========================================================================
    // verify pull-packet request carries workerId + supportedAlgorithms
    // =========================================================================

    /**
     * TC-CS-07: pull-packet request carries workerId and supportedAlgorithms from execute() params.
     */
    @Test
    void execute_sends_correct_pullPacket_request() throws Exception {
        when(dispatcherClient.pullPacketOptional(any())).thenReturn(Optional.empty());

        ComputeStep step = makeStep();
        step.execute(workerId, supportedAlgorithms);

        var captor =
                org.mockito.ArgumentCaptor.forClass(
                        de.vvwt.slotopt.worker.runtime.PullPacketRequest.class);
        verify(dispatcherClient).pullPacketOptional(captor.capture());
        assertThat(captor.getValue().workerId()).isEqualTo(workerId);
        assertThat(captor.getValue().supportedAlgorithms()).containsExactly("Ed25519");
    }

    // =========================================================================
    // AC-ERR-SOLVE-FAILURE-PROPAGATION: malformed packet payload
    // =========================================================================

    /** TC-CS-08: malformed packetPayloadJson → ComputeStepException (deserialization failure). */
    @Test
    void execute_throws_computeStepException_on_malformed_payload() throws Exception {
        PullPacketResponse badPacket =
                new PullPacketResponse(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "INVALID_JSON",
                        Instant.now().plusSeconds(60));
        when(dispatcherClient.pullPacketOptional(any())).thenReturn(Optional.of(badPacket));

        ComputeStep step = makeStep();

        assertThatThrownBy(() -> step.execute(workerId, supportedAlgorithms))
                .isInstanceOf(ComputeStepException.class);
    }
}
