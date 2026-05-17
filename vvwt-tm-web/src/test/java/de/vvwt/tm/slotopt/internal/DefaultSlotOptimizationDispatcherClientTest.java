package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import de.vvwt.tm.slotopt.DispatcherAlgorithmMismatchException;
import de.vvwt.tm.slotopt.SlotOptimizationDeprecationWarningEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit / mock-server tests for {@link DefaultSlotOptimizationDispatcherClient}.
 *
 * <p>TDD RED-first per DEC-22 Iron Law (E27S03). Uses JDK 21 built-in {@link
 * com.sun.net.httpserver.HttpServer} as the HTTP test-double (no third-party dependency — DEC-3
 * minimal-dependency; pattern established at E41S06 DispatcherStub).
 *
 * <p>Per DEC-36: this class is in the {@code slotopt.internal} package (same package as the
 * subject), so white-box reference to {@link DefaultSlotOptimizationDispatcherClient} is permitted.
 *
 * @see DefaultSlotOptimizationDispatcherClient
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S03.story.md">Story
 *     E27S03</a>
 */
class DefaultSlotOptimizationDispatcherClientTest {

    private HttpServer stubServer;
    private String baseUrl;
    private ApplicationEventPublisher eventPublisherMock;
    private WorkerKeyManager keyManagerMock;
    private DefaultSlotOptimizationDispatcherClient subject;
    private ObjectMapper objectMapper;

    /** Configurable response for /api/register-key */
    private final AtomicReference<StubResponse> registerKeyResponse = new AtomicReference<>();

    /** Configurable response for /api/submit-job */
    private final AtomicReference<StubResponse> submitJobResponse = new AtomicReference<>();

    /** Configurable response for /api/job-status/ */
    private final AtomicReference<StubResponse> jobStatusResponse = new AtomicReference<>();

    /** Last recorded request body for /api/register-key */
    private final AtomicReference<String> lastRegisterKeyBody = new AtomicReference<>("");

    /** Last recorded request body for /api/submit-job */
    private final AtomicReference<String> lastSubmitJobBody = new AtomicReference<>("");

    /** Last recorded request path for /api/job-status/ */
    private final AtomicReference<String> lastJobStatusPath = new AtomicReference<>("");

    private static final UUID WORKER_ID = UUID.randomUUID();
    private static final byte[] FAKE_PUBLIC_KEY_BYTES = new byte[32]; // Ed25519 = 32 bytes

    @BeforeEach
    void setUp() throws IOException {
        objectMapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        stubServer = HttpServer.create(new InetSocketAddress(0), 0);
        stubServer.setExecutor(Executors.newCachedThreadPool());
        stubServer.createContext("/api/register-key", this::handleRegisterKey);
        stubServer.createContext("/api/submit-job", this::handleSubmitJob);
        stubServer.createContext("/api/job-status/", this::handleJobStatus);
        stubServer.start();
        baseUrl = "http://localhost:" + stubServer.getAddress().getPort();

        eventPublisherMock = mock(ApplicationEventPublisher.class);
        keyManagerMock = mock(WorkerKeyManager.class);
        when(keyManagerMock.algorithmId()).thenReturn("Ed25519");
        when(keyManagerMock.getPublicKeyBytes()).thenReturn(FAKE_PUBLIC_KEY_BYTES);

        subject =
                new DefaultSlotOptimizationDispatcherClient(
                        baseUrl,
                        WORKER_ID,
                        keyManagerMock,
                        eventPublisherMock,
                        objectMapper,
                        30_000 // poll timeout ms
                        );
    }

    @AfterEach
    void tearDown() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    // =========================================================================
    // AC-REGISTER-KEY-WIRE-DEC43-D2: register() POST includes algorithm "Ed25519"
    // =========================================================================

    /**
     * AC-REGISTER-KEY-WIRE-DEC43-D2: register() POSTs /api/register-key with algorithm="Ed25519"
     * per DEC-43 D2.
     */
    @Test
    void register_postsAlgorithmField_Ed25519() {
        registerKeyResponse.set(
                new StubResponse(
                        201,
                        "{\"workerId\":\""
                                + WORKER_ID
                                + "\","
                                + "\"role\":\"tm-submitter\","
                                + "\"algorithm\":\"Ed25519\","
                                + "\"registeredAt\":\"2026-04-28T10:00:00Z\"}"));

        subject.register();

        String body = lastRegisterKeyBody.get();
        assertThat(body).contains("\"algorithm\"");
        assertThat(body).contains("Ed25519");
    }

    // =========================================================================
    // AC-REGISTER-RESPONSE-PARSED-DEC43-D1: algorithm list from registration response
    // =========================================================================

    /**
     * AC-REGISTER-RESPONSE-PARSED-DEC43-D1: when registration response includes algorithms list, it
     * is parsed into DispatcherRegistrationContext.
     */
    @Test
    void register_parsesAlgorithmListFromRegistrationResponse() {
        registerKeyResponse.set(
                new StubResponse(
                        201,
                        "{"
                                + "\"workerId\":\""
                                + WORKER_ID
                                + "\","
                                + "\"role\":\"tm-submitter\","
                                + "\"algorithm\":\"Ed25519\","
                                + "\"registeredAt\":\"2026-04-28T10:00:00Z\","
                                + "\"algorithms\":["
                                + "{\"algorithm_id\":\"Ed25519\","
                                + "\"display_name\":\"Ed25519\","
                                + "\"deprecation_date\":null,"
                                + "\"parameters\":null}"
                                + "]}"));

        var context = subject.register();

        assertThat(context.workerId()).isEqualTo(WORKER_ID);
        assertThat(context.algorithms()).hasSize(1);
        assertThat(context.algorithms().get(0).algorithmId()).isEqualTo("Ed25519");
        assertThat(context.algorithms().get(0).deprecationDate()).isNull();
    }

    /**
     * AC-REGISTER-RESPONSE-PARSED-DEC43-D1: when registration response does NOT include algorithms
     * list (current dispatcher state), context has empty algorithms list (defensive).
     */
    @Test
    void register_missingAlgorithmsList_returnsEmptyList() {
        registerKeyResponse.set(
                new StubResponse(
                        201,
                        "{\"workerId\":\""
                                + WORKER_ID
                                + "\","
                                + "\"role\":\"tm-submitter\","
                                + "\"algorithm\":\"Ed25519\","
                                + "\"registeredAt\":\"2026-04-28T10:00:00Z\"}"));

        var context = subject.register();

        assertThat(context.workerId()).isEqualTo(WORKER_ID);
        assertThat(context.algorithms()).isEmpty();
    }

    // =========================================================================
    // AC-DEC43-D3-WARNING-EVENT: event published when deprecation_date non-null
    // =========================================================================

    /**
     * AC-DEC43-D3-WARNING-EVENT: when registration response includes an algorithm with non-null
     * deprecation_date in the future, publishes SlotOptimizationDeprecationWarningEvent.
     */
    @Test
    void register_deprecatedAlgorithmInFuture_publishesWarningEvent() {
        LocalDate futureDate = LocalDate.now().plusDays(30);
        registerKeyResponse.set(
                new StubResponse(
                        201,
                        "{"
                                + "\"workerId\":\""
                                + WORKER_ID
                                + "\","
                                + "\"role\":\"tm-submitter\","
                                + "\"algorithm\":\"Ed25519\","
                                + "\"registeredAt\":\"2026-04-28T10:00:00Z\","
                                + "\"algorithms\":["
                                + "{\"algorithm_id\":\"Ed25519\","
                                + "\"display_name\":\"Ed25519\","
                                + "\"deprecation_date\":\""
                                + futureDate
                                + "\","
                                + "\"parameters\":null}"
                                + "]}"));

        subject.register();

        verify(eventPublisherMock, atLeastOnce())
                .publishEvent(any(SlotOptimizationDeprecationWarningEvent.class));
    }

    /**
     * AC-DEC43-D3-WARNING-EVENT (null case): when all entries have null deprecation_date, no event
     * is published (V1 universal case — never fires).
     */
    @Test
    void register_nullDeprecationDate_doesNotPublishEvent() {
        registerKeyResponse.set(
                new StubResponse(
                        201,
                        "{"
                                + "\"workerId\":\""
                                + WORKER_ID
                                + "\","
                                + "\"role\":\"tm-submitter\","
                                + "\"algorithm\":\"Ed25519\","
                                + "\"registeredAt\":\"2026-04-28T10:00:00Z\","
                                + "\"algorithms\":["
                                + "{\"algorithm_id\":\"Ed25519\","
                                + "\"display_name\":\"Ed25519\","
                                + "\"deprecation_date\":null,"
                                + "\"parameters\":null}"
                                + "]}"));

        subject.register();

        verify(eventPublisherMock, never()).publishEvent(any());
    }

    // =========================================================================
    // AC-SUBMIT-JOB-WIRE-DEC9: submitJob sends structural payload (no UUIDs in rows)
    // =========================================================================

    /**
     * AC-SUBMIT-JOB-WIRE-DEC9: submitJob POSTs /api/submit-job with structural rows payload per
     * DEC-9 (no UUIDs in the rows).
     */
    @Test
    void submitJob_postsStructuralPayload() {
        UUID jobId = UUID.randomUUID();
        submitJobResponse.set(
                new StubResponse(
                        202,
                        "{\"jobId\":\""
                                + jobId
                                + "\","
                                + "\"submittedAt\":\"2026-04-28T10:00:00Z\","
                                + "\"cacheHit\":false}"));

        RawPhaseDef rawPhaseDef = buildMinimalRawPhaseDef();
        UUID returnedJobId = subject.submitJob(rawPhaseDef);

        String body = lastSubmitJobBody.get();
        assertThat(body).contains("rows");
        assertThat(body).contains("group");
        assertThat(body).contains("pos");
        assertThat(returnedJobId).isEqualTo(jobId);
    }

    /**
     * AC-ALGORITHM-MISMATCH-PROPAGATED: when submitJob receives HTTP 400, wraps in
     * DispatcherAlgorithmMismatchException (does not leak server-internal detail).
     */
    @Test
    void submitJob_http400_throwsDispatcherAlgorithmMismatchException() {
        submitJobResponse.set(
                new StubResponse(400, "{\"error\":\"algorithm mismatch for worker\"}"));

        RawPhaseDef rawPhaseDef = buildMinimalRawPhaseDef();
        assertThatThrownBy(() -> subject.submitJob(rawPhaseDef))
                .isInstanceOf(DispatcherAlgorithmMismatchException.class);
    }

    // =========================================================================
    // AC-POLL-RESULT-WIRE-FORMAT: pollResult terminal states
    // =========================================================================

    /**
     * AC-POLL-RESULT-WIRE-FORMAT: pollResult returns non-empty Optional when job status is
     * COMPLETED and finalResult is present.
     *
     * <p>E63S02 update: updated stub to include {@code finalResult} (the E60S04 result surface).
     * Without finalResult, the response is treated as a malformed result and returns empty.
     */
    @Test
    void pollResult_completedStatus_returnsNonEmpty() {
        UUID jobId = UUID.randomUUID();
        jobStatusResponse.set(
                new StubResponse(
                        200,
                        "{\"jobId\":\""
                                + jobId
                                + "\","
                                + "\"status\":\"COMPLETED\","
                                + "\"totalPackets\":1,"
                                + "\"completedPackets\":1,"
                                + "\"finalResult\":{\"bestRank\":0,\"bestScore\":1.0}}"));

        Optional<int[]> result = subject.pollResult(jobId);

        assertThat(result).isPresent();
    }

    /** AC-POLL-RESULT-WIRE-FORMAT: pollResult GETs /api/job-status/{id}. */
    @Test
    void pollResult_callsCorrectEndpoint() {
        UUID jobId = UUID.randomUUID();
        jobStatusResponse.set(
                new StubResponse(
                        200,
                        "{\"jobId\":\""
                                + jobId
                                + "\","
                                + "\"status\":\"COMPLETED\","
                                + "\"totalPackets\":1,"
                                + "\"completedPackets\":1,"
                                + "\"finalResult\":{\"bestRank\":0,\"bestScore\":1.0}}"));

        subject.pollResult(jobId);

        assertThat(lastJobStatusPath.get()).endsWith("/api/job-status/" + jobId);
    }

    // =========================================================================
    // AC-TEST-POLLRESULT-RETURNS-REAL-RESULT (E63S02 RED-first)
    // pollResult must return the actual bestRank from finalResult, not the empty-array sentinel
    // =========================================================================

    /**
     * AC-TEST-POLLRESULT-RETURNS-REAL-RESULT (E63S02): when the dispatcher reports COMPLETED with a
     * finalResult payload, pollResult returns an Optional containing the bestRank from finalResult
     * — NOT an empty-array sentinel.
     *
     * <p>RED against current code: current code returns {@code Optional.of(new int[0])} (empty
     * array) regardless of finalResult. This test asserts that the returned array has length 1 and
     * contains the bestRank value.
     *
     * <p>DEC-56/DEC-61: bestRank is the lap-permutation rank (an integer in [0, lapCount!)) that
     * {@link de.vvwt.tm.slotopt.SlotResultApplicator#applyResult} consumes directly — no
     * translation step needed (rank semantics verified in impl-report per AC-GOV-RANK-SEMANTICS).
     */
    @Test
    void pollResult_completedWithFinalResult_returnsActualBestRank() {
        UUID jobId = UUID.randomUUID();
        int expectedRank = 5; // rank 5 = a non-identity lap permutation
        jobStatusResponse.set(
                new StubResponse(
                        200,
                        "{\"jobId\":\""
                                + jobId
                                + "\","
                                + "\"status\":\"COMPLETED\","
                                + "\"totalPackets\":4,"
                                + "\"completedPackets\":4,"
                                + "\"finalResult\":{\"bestRank\":"
                                + expectedRank
                                + ",\"bestScore\":0.42}}"));

        Optional<int[]> result = subject.pollResult(jobId);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get()[0]).isEqualTo(expectedRank);
    }

    /**
     * AC-TEST-CACHE-COMPLETION-PATH (E63S02): when the dispatcher reports COMPLETED_FROM_CACHE with
     * a finalResult payload, pollResult returns the actual bestRank — the cache-hit path is treated
     * identically to a freshly finalized job.
     *
     * <p>RED against current code: current code does not parse finalResult for either completion
     * variant; it returns the empty-array sentinel.
     */
    @Test
    void pollResult_completedFromCacheWithFinalResult_returnsActualBestRank() {
        UUID jobId = UUID.randomUUID();
        int expectedRank = 2;
        jobStatusResponse.set(
                new StubResponse(
                        200,
                        "{\"jobId\":\""
                                + jobId
                                + "\","
                                + "\"status\":\"COMPLETED_FROM_CACHE\","
                                + "\"totalPackets\":4,"
                                + "\"completedPackets\":4,"
                                + "\"finalResult\":{\"bestRank\":"
                                + expectedRank
                                + ",\"bestScore\":0.35}}"));

        Optional<int[]> result = subject.pollResult(jobId);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get()[0]).isEqualTo(expectedRank);
    }

    /**
     * AC-ERR-MALFORMED-RESULT-REJECTED (E63S02): when the job is COMPLETED but finalResult is
     * missing/null (malformed response), pollResult returns empty — treated as a fetch failure so
     * the caller falls through to Leg 3.
     *
     * <p>RED against current code: current code returns the empty-array sentinel regardless; the
     * new code must treat a missing/null finalResult as an empty Optional.
     */
    @Test
    void pollResult_completedButNullFinalResult_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        jobStatusResponse.set(
                new StubResponse(
                        200,
                        "{\"jobId\":\""
                                + jobId
                                + "\","
                                + "\"status\":\"COMPLETED\","
                                + "\"totalPackets\":1,"
                                + "\"completedPackets\":1,"
                                + "\"finalResult\":null}"));

        Optional<int[]> result = subject.pollResult(jobId);

        // Malformed: COMPLETED but no extractable rank → treat as fetch failure → empty
        assertThat(result).isEmpty();
    }

    // =========================================================================
    // E63S06 RED-first tests — cancellation-aware pollResult + fetchBestSoFar
    // =========================================================================

    /**
     * AC-TEST-CANCEL-INTERRUPTS-POLL (E63S06 RED-first): when the CancellationToken is cancelled,
     * {@code pollResult(jobId, token)} returns {@link Optional#empty()} immediately without waiting
     * for the dispatcher.
     *
     * <p>RED against current code: no cancellation-aware {@code pollResult} overload exists. After
     * the fix, the loop checks the token at each iteration and returns empty on cancel.
     */
    @Test
    void pollResult_cancelledToken_returnsEmptyImmediately_E63S06() {
        UUID jobId = UUID.randomUUID();
        // Dispatcher says RUNNING — would normally keep polling
        jobStatusResponse.set(
                new StubResponse(
                        200,
                        "{\"jobId\":\""
                                + jobId
                                + "\","
                                + "\"status\":\"RUNNING\","
                                + "\"totalPackets\":4,"
                                + "\"completedPackets\":0,"
                                + "\"finalResult\":null,"
                                + "\"bestSoFar\":null}"));

        de.vvwt.tm.slotopt.CancellationToken token = de.vvwt.tm.slotopt.CancellationToken.create();
        token.cancel(); // pre-cancelled

        Optional<int[]> result = subject.pollResult(jobId, token);

        // Must return empty immediately (cancelled before first poll check)
        assertThat(result).isEmpty();
    }

    /**
     * AC-TEST-CANCEL-CASE-PARTIAL-BEST-SO-FAR (E63S06 RED-first): {@code fetchBestSoFar(jobId)}
     * returns the bestSoFar rank when the dispatcher reports a partial result.
     *
     * <p>RED against current code: no {@code fetchBestSoFar} method exists.
     */
    @Test
    void fetchBestSoFar_partialResult_returnsRank_E63S06() {
        UUID jobId = UUID.randomUUID();
        int expectedRank = 3;
        // Dispatcher reports in-progress with bestSoFar populated
        jobStatusResponse.set(
                new StubResponse(
                        200,
                        "{\"jobId\":\""
                                + jobId
                                + "\","
                                + "\"status\":\"RUNNING\","
                                + "\"totalPackets\":4,"
                                + "\"completedPackets\":1,"
                                + "\"finalResult\":null,"
                                + "\"bestSoFar\":{\"bestRank\":"
                                + expectedRank
                                + ",\"bestScore\":0.42}}"));

        Optional<int[]> result = subject.fetchBestSoFar(jobId);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get()[0]).isEqualTo(expectedRank);
    }

    /**
     * AC-ERR-BEST-SO-FAR-FETCH-FAILURE (E63S06): when the dispatcher reports no bestSoFar (no
     * packet completed), {@code fetchBestSoFar(jobId)} returns {@link Optional#empty()}. The phase
     * is resolved by case (3) — retain existing L1+L2 assignment.
     */
    @Test
    void fetchBestSoFar_noCompletedPackets_returnsEmpty_E63S06() {
        UUID jobId = UUID.randomUUID();
        jobStatusResponse.set(
                new StubResponse(
                        200,
                        "{\"jobId\":\""
                                + jobId
                                + "\","
                                + "\"status\":\"RUNNING\","
                                + "\"totalPackets\":4,"
                                + "\"completedPackets\":0,"
                                + "\"finalResult\":null,"
                                + "\"bestSoFar\":null}"));

        Optional<int[]> result = subject.fetchBestSoFar(jobId);

        assertThat(result).isEmpty();
    }

    /**
     * AC-ERR-BEST-SO-FAR-FETCH-FAILURE (E63S06): when fetchBestSoFar fails with a network error, it
     * returns {@link Optional#empty()} (never throws — phase falls back to case 3).
     */
    @Test
    void fetchBestSoFar_networkError_returnsEmpty_E63S06() {
        UUID jobId = UUID.randomUUID();
        // Stub returns 500 — should be treated as failure → empty
        jobStatusResponse.set(new StubResponse(500, "{\"error\":\"internal\"}"));

        Optional<int[]> result = subject.fetchBestSoFar(jobId);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static RawPhaseDef buildMinimalRawPhaseDef() {
        var pt1 = new PositionTuple(0, 0);
        var pt2 = new PositionTuple(0, 1);
        var row = new RawRow(List.of(pt1, pt2));
        return new RawPhaseDef(1, 1, List.of(row));
    }

    // =========================================================================
    // Stub HTTP server handlers
    // =========================================================================

    private void handleRegisterKey(HttpExchange exchange) throws IOException {
        try {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            lastRegisterKeyBody.set(new String(bytes, StandardCharsets.UTF_8));
            StubResponse stub = registerKeyResponse.get();
            sendJson(exchange, stub.status(), stub.body());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"stub error\"}");
        }
    }

    private void handleSubmitJob(HttpExchange exchange) throws IOException {
        try {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            lastSubmitJobBody.set(new String(bytes, StandardCharsets.UTF_8));
            StubResponse stub = submitJobResponse.get();
            sendJson(exchange, stub.status(), stub.body());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"stub error\"}");
        }
    }

    private void handleJobStatus(HttpExchange exchange) throws IOException {
        try {
            lastJobStatusPath.set(exchange.getRequestURI().getPath());
            StubResponse stub = jobStatusResponse.get();
            sendJson(exchange, stub.status(), stub.body());
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"stub error\"}");
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private record StubResponse(int status, String body) {}
}
