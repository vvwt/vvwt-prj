package de.vvwt.slotopt.standalone.bootstrap.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.bootstrap.BootstrapException;
import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithm;
import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithmsResponse;
import de.vvwt.slotopt.worker.runtime.DispatcherClient;
import de.vvwt.slotopt.worker.runtime.DispatcherException;
import de.vvwt.slotopt.worker.runtime.RegisterKeyResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Same-package white-box tests for {@link DefaultBootstrapService}.
 *
 * <p>Per DEC-36 same-package carve-out: test in {@code bootstrap.internal} may directly reference
 * {@link DefaultBootstrapService} (concrete impl). Cross-package collaborators (e.g., {@link
 * DispatcherClient}) are referenced via interface per DEC-36.
 *
 * <p>Story: E41S04 AC-OBSERVABILITY-EVENTS-BOOTSTRAP, AC-EXIT-CODE-BOOTSTRAP,
 * AC-DEC43-D3-ADMIN-WARNING-SURFACE, AC-ALGORITHM-VALIDATION. Fix: E41S07
 * AC-FIX-DETERMINISTIC-DEPRECATION-TESTS — replaced LocalDate.now()-relative fixture dates with
 * UTC-anchored fixed dates to eliminate timezone-fragility (DEC-48 UTC boundary).
 */
@ExtendWith(MockitoExtension.class)
class DefaultBootstrapServiceTest {

    /**
     * A deprecation date unambiguously in the future relative to any realistic test execution time.
     * DEC-48: 2099-12-31 + 1 day = 2100-01-01T00:00:00Z, always in the future → accepted with
     * warning. UTC-anchored; no LocalDate.now() dependency (E41S07 fix).
     */
    private static final LocalDate FUTURE_DATE = LocalDate.of(2099, 12, 31);

    /**
     * A deprecation date unambiguously in the past relative to any realistic test execution time.
     * DEC-48: 2020-01-01 + 1 day = 2020-01-02T00:00:00Z, always before any realistic execution
     * instant → rejected (ALGORITHM_DEPRECATED_PAST_DEADLINE). UTC-anchored; no LocalDate.now()
     * dependency (E41S07 fix).
     */
    private static final LocalDate PAST_DATE = LocalDate.of(2020, 1, 1);

    @Mock private DispatcherClient dispatcherClient;

    private List<String> capturedEvents;

    private WorkerConfig makeConfig(String algorithm) {
        return new WorkerConfig(
                URI.create("http://localhost:8080"),
                Path.of("/tmp/keys"),
                Duration.ofSeconds(30),
                algorithm,
                Duration.ofSeconds(30),
                50,
                "test-worker",
                "text");
    }

    private RegisterKeyResponse makeRegisterResponse(UUID workerId) {
        return new RegisterKeyResponse(workerId, "worker", "Ed25519", Instant.now());
    }

    @BeforeEach
    void setUp() {
        capturedEvents = new ArrayList<>();
    }

    // =========================================================================
    // Observability: TC-23 to TC-27
    // =========================================================================

    /**
     * TC-23: worker_started event emitted at INFO level. DefaultBootstrapService emits
     * worker_started on construction/run entry.
     */
    @Test
    void emits_worker_started_event() throws Exception {
        UUID workerId = UUID.randomUUID();
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(response);
        when(dispatcherClient.registerKey(any())).thenReturn(makeRegisterResponse(workerId));

        WorkerConfig config = makeConfig("Ed25519");
        DefaultBootstrapService service =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);

        service.run(config);

        assertThat(capturedEvents).anyMatch(e -> e.contains("worker_started"));
    }

    /** TC-24: algorithms_announced event emitted with count and algorithm_ids. */
    @Test
    void emits_algorithms_announced_event() throws Exception {
        UUID workerId = UUID.randomUUID();
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(response);
        when(dispatcherClient.registerKey(any())).thenReturn(makeRegisterResponse(workerId));

        DefaultBootstrapService service =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);

        service.run(makeConfig("Ed25519"));

        assertThat(capturedEvents)
                .anyMatch(e -> e.contains("algorithms_announced") && e.contains("Ed25519"));
    }

    /** TC-25: algorithm_picked event emitted with algorithm_id and deprecation_date. */
    @Test
    void emits_algorithm_picked_event() throws Exception {
        UUID workerId = UUID.randomUUID();
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(response);
        when(dispatcherClient.registerKey(any())).thenReturn(makeRegisterResponse(workerId));

        DefaultBootstrapService service =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);

        service.run(makeConfig("Ed25519"));

        assertThat(capturedEvents)
                .anyMatch(e -> e.contains("algorithm_picked") && e.contains("Ed25519"));
    }

    /** TC-26: algorithm_deprecation_warning event emitted ONLY when future deprecation. */
    @Test
    void emits_deprecation_warning_only_when_future_deprecation() throws Exception {
        UUID workerId = UUID.randomUUID();
        // Future deprecation → warning
        AnnouncedAlgorithmsResponse withWarning =
                new AnnouncedAlgorithmsResponse(
                        List.of(
                                new AnnouncedAlgorithm("Ed25519", "Ed25519", FUTURE_DATE, null),
                                new AnnouncedAlgorithm("ML-DSA-65", "ML-DSA-65", null, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(withWarning);
        when(dispatcherClient.registerKey(any())).thenReturn(makeRegisterResponse(workerId));

        DefaultBootstrapService service =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);
        service.run(makeConfig("Ed25519"));

        assertThat(capturedEvents).anyMatch(e -> e.contains("algorithm_deprecation_warning"));

        // Null deprecation → no warning
        capturedEvents.clear();
        AnnouncedAlgorithmsResponse noWarning =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(noWarning);

        DefaultBootstrapService service2 =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);
        service2.run(makeConfig("Ed25519"));

        assertThat(capturedEvents).noneMatch(e -> e.contains("algorithm_deprecation_warning"));
    }

    /** TC-27: key_registered event emitted with workerId and algorithm_id. */
    @Test
    void emits_key_registered_event() throws Exception {
        UUID workerId = UUID.randomUUID();
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(response);
        when(dispatcherClient.registerKey(any())).thenReturn(makeRegisterResponse(workerId));

        DefaultBootstrapService service =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);

        UUID result = service.run(makeConfig("Ed25519"));

        assertThat(capturedEvents)
                .anyMatch(
                        e ->
                                e.contains("key_registered")
                                        && e.contains(workerId.toString())
                                        && e.contains("Ed25519"));
        assertThat(result).isEqualTo(workerId);
    }

    // =========================================================================
    // Exit codes: TC-28 to TC-31
    // =========================================================================

    /**
     * TC-28: Dispatcher unreachable (DispatcherException from fetchAnnouncedAlgorithms) → exit 75.
     */
    @Test
    void exit_75_on_dispatcher_unreachable() throws Exception {
        when(dispatcherClient.fetchAnnouncedAlgorithms())
                .thenThrow(new DispatcherException(0, "connection refused", null));

        DefaultBootstrapService service =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);

        assertThatThrownBy(() -> service.run(makeConfig("Ed25519")))
                .isInstanceOf(BootstrapException.class)
                .satisfies(
                        ex ->
                                assertThat(((BootstrapException) ex).getExitCode())
                                        .isEqualTo(ExitCode.DISPATCHER_UNREACHABLE));
    }

    /** TC-29: Algorithm not announced → exit 78. */
    @Test
    void exit_78_on_algorithm_not_announced() throws Exception {
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("ML-DSA-65", "ML-DSA-65", null, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(response);

        DefaultBootstrapService service =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);

        assertThatThrownBy(() -> service.run(makeConfig("Ed25519")))
                .isInstanceOf(BootstrapException.class)
                .satisfies(
                        ex ->
                                assertThat(((BootstrapException) ex).getExitCode())
                                        .isEqualTo(ExitCode.ALGORITHM_NOT_ANNOUNCED));
    }

    /** TC-30: Algorithm deprecated past deadline → exit 78. */
    @Test
    void exit_78_on_algorithm_deprecated_past_deadline() throws Exception {
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", PAST_DATE, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(response);

        DefaultBootstrapService service =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);

        assertThatThrownBy(() -> service.run(makeConfig("Ed25519")))
                .isInstanceOf(BootstrapException.class)
                .satisfies(
                        ex ->
                                assertThat(((BootstrapException) ex).getExitCode())
                                        .isEqualTo(ExitCode.ALGORITHM_DEPRECATED_PAST_DEADLINE));
    }

    /** TC-31: register-key returns HTTP 410 → exit 78. */
    @Test
    void exit_78_on_register_key_410() throws Exception {
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(response);
        when(dispatcherClient.registerKey(any()))
                .thenThrow(new DispatcherException(410, "gone — algorithm deprecated", null));

        DefaultBootstrapService service =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);

        assertThatThrownBy(() -> service.run(makeConfig("Ed25519")))
                .isInstanceOf(BootstrapException.class)
                .satisfies(
                        ex ->
                                assertThat(((BootstrapException) ex).getExitCode())
                                        .isEqualTo(ExitCode.REGISTRATION_REJECTED_DEPRECATED));
    }

    // =========================================================================
    // D3 warning surface: TC-18, TC-19
    // =========================================================================

    /** TC-18: D3 warning emits stderr line when future deprecation. */
    @Test
    void d3_warning_emits_stderr_line() throws Exception {
        UUID workerId = UUID.randomUUID();
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(
                                new AnnouncedAlgorithm("Ed25519", "Ed25519", FUTURE_DATE, null),
                                new AnnouncedAlgorithm("ML-DSA-65", "ML-DSA-65", null, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(response);
        when(dispatcherClient.registerKey(any())).thenReturn(makeRegisterResponse(workerId));

        ByteArrayOutputStream stderrCapture = new ByteArrayOutputStream();
        PrintStream origStderr = System.err;
        System.setErr(new PrintStream(stderrCapture));
        try {
            DefaultBootstrapService service =
                    new DefaultBootstrapService(dispatcherClient, capturedEvents::add);
            service.run(makeConfig("Ed25519"));
        } finally {
            System.setErr(origStderr);
        }

        String stderrOutput = stderrCapture.toString();
        assertThat(stderrOutput).contains("WARNING:");
        assertThat(stderrOutput).contains("Ed25519");
        assertThat(stderrOutput).contains(FUTURE_DATE.toString());
        assertThat(stderrOutput).contains("ML-DSA-65");
    }

    /** TC-19: D3 warning includes WARN-level structured log event via events consumer. */
    @Test
    void d3_warning_emits_warn_log_event() throws Exception {
        UUID workerId = UUID.randomUUID();
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(
                                new AnnouncedAlgorithm("Ed25519", "Ed25519", FUTURE_DATE, null),
                                new AnnouncedAlgorithm("ML-DSA-65", "ML-DSA-65", null, null)));
        when(dispatcherClient.fetchAnnouncedAlgorithms()).thenReturn(response);
        when(dispatcherClient.registerKey(any())).thenReturn(makeRegisterResponse(workerId));

        DefaultBootstrapService service =
                new DefaultBootstrapService(dispatcherClient, capturedEvents::add);
        service.run(makeConfig("Ed25519"));

        assertThat(capturedEvents)
                .anyMatch(
                        e ->
                                e.contains("algorithm_deprecation_warning")
                                        && e.contains("Ed25519")
                                        && e.contains(FUTURE_DATE.toString())
                                        && e.contains("ML-DSA-65"));
    }
}
