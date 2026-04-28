package de.vvwt.slotopt.standalone.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import de.vvwt.slotopt.standalone.http.AnnouncedAlgorithmsResponse;
import de.vvwt.slotopt.standalone.http.DispatcherException;
import de.vvwt.slotopt.standalone.http.RegisterKeyRequest;
import de.vvwt.slotopt.standalone.http.RegisterKeyResponse;
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
}
