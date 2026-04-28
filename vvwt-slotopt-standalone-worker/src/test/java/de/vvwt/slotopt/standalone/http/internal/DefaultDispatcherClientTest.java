package de.vvwt.slotopt.standalone.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import de.vvwt.slotopt.standalone.http.AnnouncedAlgorithmsResponse;
import de.vvwt.slotopt.standalone.http.DispatcherException;
import de.vvwt.slotopt.standalone.http.PullPacketRequest;
import de.vvwt.slotopt.standalone.http.PullPacketResponse;
import de.vvwt.slotopt.standalone.http.RegisterKeyRequest;
import de.vvwt.slotopt.standalone.http.RegisterKeyResponse;
import de.vvwt.slotopt.standalone.http.SubmitResultRequest;
import de.vvwt.slotopt.standalone.http.SubmitResultResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Same-package white-box tests for {@link DefaultDispatcherClient}.
 *
 * <p>Uses JDK's built-in {@code com.sun.net.httpserver.HttpServer} as a lightweight test double for
 * HTTP wire-level tests. No third-party HTTP mocking library required (DEC-3 minimal-dependency).
 *
 * <p>Per DEC-36 same-package carve-out: this test is in {@code http.internal} (same package as
 * {@link DefaultDispatcherClient}), so direct impl reference is permitted.
 *
 * <p>Story: E41S04 AC-DEFAULT-DISPATCHER-CLIENT, AC-FETCH-ALGORITHMS-WIRE,
 * AC-REGISTER-KEY-WITH-ALGORITHM, AC-FAIL-FAST-DISPATCHER-UNREACHABLE.
 */
class DefaultDispatcherClientTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private HttpServer httpServer;
    private int serverPort;
    private DefaultDispatcherClient client;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = httpServer.getAddress().getPort();
        httpServer.start();
        URI dispatcherUrl = URI.create("http://localhost:" + serverPort);
        client = new DefaultDispatcherClient(dispatcherUrl, Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /** TC-6: Constructor accepts URI + Duration; creates a valid client instance. */
    @Test
    void constructor_accepts_uri_and_duration() {
        URI url = URI.create("http://localhost:8080");
        DefaultDispatcherClient c = new DefaultDispatcherClient(url, Duration.ofSeconds(10));
        assertThat(c).isNotNull();
    }

    /** TC-7: fetchAnnouncedAlgorithms() sends GET /api/algorithms and deserializes JSON array. */
    @Test
    void fetchAnnouncedAlgorithms_deserializes_json_array() throws Exception {
        String json =
                "[{\"algorithm_id\":\"Ed25519\",\"display_name\":\"Ed25519\","
                        + "\"deprecation_date\":null,\"parameters\":null}]";
        httpServer.createContext(
                "/api/algorithms",
                exchange -> {
                    byte[] body = json.getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });

        AnnouncedAlgorithmsResponse response = client.fetchAnnouncedAlgorithms();

        assertThat(response.algorithms()).hasSize(1);
        assertThat(response.algorithms().get(0).algorithmId()).isEqualTo("Ed25519");
        assertThat(response.algorithms().get(0).deprecationDate()).isNull();
    }

    /** TC-7b: fetchAnnouncedAlgorithms() correctly deserializes deprecation_date. */
    @Test
    void fetchAnnouncedAlgorithms_deserializes_deprecation_date() throws Exception {
        String json =
                "[{\"algorithm_id\":\"Ed25519\",\"display_name\":\"Ed25519\","
                        + "\"deprecation_date\":\"2030-12-31\",\"parameters\":null}]";
        httpServer.createContext(
                "/api/algorithms-with-date",
                exchange -> {
                    byte[] body = json.getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        URI url = URI.create("http://localhost:" + serverPort);
        DefaultDispatcherClient c = new DefaultDispatcherClient(url, Duration.ofSeconds(5));
        // Use a fresh server context path
        httpServer.removeContext("/api/algorithms-with-date");
        httpServer.createContext(
                "/api/algorithms",
                exchange -> {
                    byte[] body = json.getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });

        AnnouncedAlgorithmsResponse response = c.fetchAnnouncedAlgorithms();

        assertThat(response.algorithms().get(0).deprecationDate())
                .isEqualTo(LocalDate.of(2030, 12, 31));
    }

    /** TC-8: fetchAnnouncedAlgorithms() throws DispatcherException on non-200 HTTP status. */
    @Test
    void fetchAnnouncedAlgorithms_throws_on_non200() {
        httpServer.createContext(
                "/api/algorithms",
                exchange -> {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.getResponseBody().close();
                });

        assertThatThrownBy(() -> client.fetchAnnouncedAlgorithms())
                .isInstanceOf(DispatcherException.class)
                .satisfies(
                        ex ->
                                assertThat(((DispatcherException) ex).getHttpStatus())
                                        .isEqualTo(503));
    }

    /**
     * TC-9: fetchAnnouncedAlgorithms() throws DispatcherException when dispatcher is unreachable.
     */
    @Test
    void fetchAnnouncedAlgorithms_throws_on_io_error() {
        // Point to a port with no listener
        DefaultDispatcherClient unreachableClient =
                new DefaultDispatcherClient(
                        URI.create("http://localhost:19999"), Duration.ofMillis(200));

        assertThatThrownBy(() -> unreachableClient.fetchAnnouncedAlgorithms())
                .isInstanceOf(DispatcherException.class)
                .satisfies(
                        ex -> assertThat(((DispatcherException) ex).getHttpStatus()).isEqualTo(0));
    }

    /** TC-10: registerKey() sends POST /api/register-key and returns RegisterKeyResponse. */
    @Test
    void registerKey_sends_post_and_returns_response() throws Exception {
        UUID workerId = UUID.randomUUID();
        String responseJson =
                String.format(
                        "{\"workerId\":\"%s\",\"role\":\"worker\",\"algorithm\":\"Ed25519\",\"registeredAt\":\"2026-04-28T12:00:00Z\"}",
                        workerId);
        httpServer.createContext(
                "/api/register-key",
                exchange -> {
                    // read request body to avoid connection reset
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = responseJson.getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });

        RegisterKeyRequest request =
                new RegisterKeyRequest(workerId, "worker", "Ed25519", new byte[32]);
        RegisterKeyResponse response = client.registerKey(request);

        assertThat(response.workerId()).isEqualTo(workerId);
        assertThat(response.algorithm()).isEqualTo("Ed25519");
        assertThat(response.registeredAt()).isEqualTo(Instant.parse("2026-04-28T12:00:00Z"));
    }

    /** TC-11: registerKey() throws DispatcherException(410) on HTTP 410. */
    @Test
    void registerKey_throws_dispatcher_exception_on_410() {
        httpServer.createContext(
                "/api/register-key",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(410, -1);
                    exchange.getResponseBody().close();
                });

        RegisterKeyRequest request =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", new byte[32]);

        assertThatThrownBy(() -> client.registerKey(request))
                .isInstanceOf(DispatcherException.class)
                .satisfies(
                        ex ->
                                assertThat(((DispatcherException) ex).getHttpStatus())
                                        .isEqualTo(410));
    }

    /** TC-12: registerKey() throws DispatcherException on IOException. */
    @Test
    void registerKey_throws_on_io_error() {
        DefaultDispatcherClient unreachableClient =
                new DefaultDispatcherClient(
                        URI.create("http://localhost:19999"), Duration.ofMillis(200));
        RegisterKeyRequest request =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", new byte[32]);

        assertThatThrownBy(() -> unreachableClient.registerKey(request))
                .isInstanceOf(DispatcherException.class)
                .satisfies(
                        ex -> assertThat(((DispatcherException) ex).getHttpStatus()).isEqualTo(0));
    }

    // =========================================================================
    // E41S05: pullPacket + submitResult
    // =========================================================================

    /**
     * TC-13 (RED): pullPacket() returns PullPacketResponse on HTTP 200 with valid JSON body.
     *
     * <p>Story: E41S05 AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT.
     */
    @Test
    void pullPacket_returns_response_on_200() throws Exception {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String json =
                "{\"packetId\":\""
                        + packetId
                        + "\",\"jobId\":\""
                        + jobId
                        + "\",\"packetPayloadJson\":\"{}\",\"timeoutAt\":\"2026-01-01T00:00:00Z\"}";
        httpServer.createContext(
                "/api/pull-packet",
                exchange -> {
                    byte[] body = json.getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });

        UUID workerId = UUID.randomUUID();
        PullPacketRequest request = new PullPacketRequest(workerId, java.util.List.of("Ed25519"));
        PullPacketResponse response = client.pullPacket(request);

        assertThat(response).isNotNull();
        assertThat(response.packetId()).isEqualTo(packetId);
        assertThat(response.jobId()).isEqualTo(jobId);
        assertThat(response.packetPayloadJson()).isEqualTo("{}");
    }

    /**
     * TC-14 (RED): pullPacket() returns empty Optional on HTTP 204 (no packets available).
     *
     * <p>Story: E41S05 AC-EMPTY-PULL-RESPONSE-BACKOFF.
     */
    @Test
    void pullPacket_returns_empty_on_204() throws Exception {
        httpServer.createContext(
                "/api/pull-packet",
                exchange -> {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.getResponseBody().close();
                });

        UUID workerId = UUID.randomUUID();
        PullPacketRequest request = new PullPacketRequest(workerId, java.util.List.of("Ed25519"));
        java.util.Optional<PullPacketResponse> response = client.pullPacketOptional(request);

        assertThat(response).isEmpty();
    }

    /**
     * TC-15 (RED): pullPacket() throws DispatcherException on HTTP 410.
     *
     * <p>Story: E41S05 AC-HTTP-410-DEPRECATED-AT-SUBMIT.
     */
    @Test
    void pullPacket_throws_on_410() throws Exception {
        httpServer.createContext(
                "/api/pull-packet",
                exchange -> {
                    exchange.sendResponseHeaders(410, -1);
                    exchange.getResponseBody().close();
                });

        UUID workerId = UUID.randomUUID();
        PullPacketRequest request = new PullPacketRequest(workerId, java.util.List.of("Ed25519"));

        assertThatThrownBy(() -> client.pullPacket(request))
                .isInstanceOf(DispatcherException.class)
                .satisfies(
                        ex ->
                                assertThat(((DispatcherException) ex).getHttpStatus())
                                        .isEqualTo(410));
    }

    /**
     * TC-16 (RED): submitResult() returns SubmitResultResponse with accepted=true on HTTP 200.
     *
     * <p>Story: E41S05 AC-SUBMIT-RESULT-WITH-ALGORITHM.
     */
    @Test
    void submitResult_returns_accepted_true_on_200() throws Exception {
        String json = "{\"accepted\":true,\"reason\":null}";
        httpServer.createContext(
                "/api/submit-result",
                exchange -> {
                    byte[] body = json.getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });

        SubmitResultRequest request =
                new SubmitResultRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Ed25519",
                        new byte[64],
                        "{\"bestRank\":0}");
        SubmitResultResponse response = client.submitResult(request);

        assertThat(response).isNotNull();
        assertThat(response.accepted()).isTrue();
    }

    /**
     * TC-17 (RED): submitResult() returns accepted=false (superseded) on HTTP 200.
     *
     * <p>Story: E41S05 AC-OBSERVABILITY-EVENTS-RUNTIME (result_superseded event).
     */
    @Test
    void submitResult_returns_accepted_false_when_superseded() throws Exception {
        String json = "{\"accepted\":false,\"reason\":\"superseded\"}";
        httpServer.createContext(
                "/api/submit-result",
                exchange -> {
                    byte[] body = json.getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });

        SubmitResultRequest request =
                new SubmitResultRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Ed25519",
                        new byte[64],
                        "{\"bestRank\":0}");
        SubmitResultResponse response = client.submitResult(request);

        assertThat(response.accepted()).isFalse();
        assertThat(response.reason()).isEqualTo("superseded");
    }

    /**
     * TC-18 (RED): submitResult() throws DispatcherException with httpStatus=410 on HTTP 410.
     *
     * <p>Story: E41S05 AC-HTTP-410-DEPRECATED-AT-SUBMIT, AC-EXIT-CODE-RUNTIME.
     */
    @Test
    void submitResult_throws_DispatcherException_on_410() throws Exception {
        httpServer.createContext(
                "/api/submit-result",
                exchange -> {
                    exchange.sendResponseHeaders(410, -1);
                    exchange.getResponseBody().close();
                });

        SubmitResultRequest request =
                new SubmitResultRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Ed25519",
                        new byte[64],
                        "{\"bestRank\":0}");

        assertThatThrownBy(() -> client.submitResult(request))
                .isInstanceOf(DispatcherException.class)
                .satisfies(
                        ex ->
                                assertThat(((DispatcherException) ex).getHttpStatus())
                                        .isEqualTo(410));
    }
}
