package de.vvwt.slotopt.standalone.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight HTTP test-double for the dispatcher, using JDK 21 built-in {@link
 * com.sun.net.httpserver.HttpServer} (no third-party dependency required — DEC-3 minimal-dependency
 * rationale; WireMock unavailable in the offline build environment).
 *
 * <p>Configures high-fidelity stubs for all four dispatcher HTTP endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/algorithms} — algorithm announcement (DEC-43 D1)
 *   <li>{@code POST /api/register-key} — key registration (DEC-6 + DEC-43 D2)
 *   <li>{@code POST /api/pull-packet} — packet acquisition
 *   <li>{@code POST /api/submit-result} — result submission
 * </ul>
 *
 * <p>Story: E41S06 — integration test infrastructure.
 */
class DispatcherStub implements AutoCloseable {

    /** Minimal valid packet payload JSON that {@code PacketSolver.solvePacket} can process. */
    static final String VALID_PACKET_PAYLOAD =
            "{\"jobId\":\""
                    + UUID.randomUUID()
                    + "\","
                    + "\"n\":2,"
                    + "\"rankFrom\":0,"
                    + "\"rankTo\":1,"
                    + "\"canonicalPhaseDef\":{\"rowCount\":1,\"avatarCount\":2,\"rows\":[[0,1]]}}";

    private final HttpServer server;
    private final UUID workerId;
    private final UUID packetId;
    private final UUID jobId;
    private final ObjectMapper objectMapper;

    // Recorded requests for later verification
    private final CopyOnWriteArrayList<RecordedRequest> recordedRequests =
            new CopyOnWriteArrayList<>();

    // Scenario state for pull-packet
    private final AtomicInteger pullPacketCallCount = new AtomicInteger(0);
    private int pullPacketPacketsAvailable = 0;

    // Configurable response bodies
    private final AtomicReference<String> algorithmsResponseBody = new AtomicReference<>("");
    private final AtomicReference<Integer> registerKeyStatusCode = new AtomicReference<>(200);
    private final AtomicReference<String> registerKeyResponseBody = new AtomicReference<>("");
    private final AtomicReference<String> submitResultResponseBody =
            new AtomicReference<>("{\"accepted\":true,\"reason\":null}");

    DispatcherStub() throws IOException {
        this.objectMapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.workerId = UUID.randomUUID();
        this.packetId = UUID.randomUUID();
        this.jobId = UUID.randomUUID();

        this.server = HttpServer.create(new InetSocketAddress(0), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/api/algorithms", this::handleAlgorithms);
        server.createContext("/api/register-key", this::handleRegisterKey);
        server.createContext("/api/pull-packet", this::handlePullPacket);
        server.createContext("/api/submit-result", this::handleSubmitResult);

        server.start();
    }

    /**
     * Returns the base URI for this stub dispatcher.
     *
     * @return dispatcher base URI (e.g., {@code http://localhost:54321})
     */
    URI baseUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    /**
     * Returns all requests recorded by this stub.
     *
     * @return unmodifiable list of recorded requests
     */
    List<RecordedRequest> recordedRequests() {
        return List.copyOf(recordedRequests);
    }

    /**
     * Returns recorded requests for a specific path.
     *
     * @param path URL path (e.g., "/api/register-key")
     * @return list of matching requests
     */
    List<RecordedRequest> requestsFor(String path) {
        return recordedRequests.stream().filter(r -> r.path().equals(path)).toList();
    }

    // =========================================================================
    // Scenario configuration methods
    // =========================================================================

    /**
     * Configures the happy-path scenario: non-deprecated Ed25519 algorithm, register-key success,
     * one packet available then 204, submit-result accepted.
     */
    void stubHappyPath() {
        stubAlgorithms(
                "[{\"algorithm_id\":\"Ed25519\",\"display_name\":\"Ed25519\","
                        + "\"deprecation_date\":null,\"parameters\":null}]");
        stubRegisterKeyOk();
        pullPacketPacketsAvailable = 1;
        stubSubmitResultAccepted();
    }

    /** Configures the happy-path scenario with submit-result returning superseded. */
    void stubHappyPathSuperseded() {
        stubAlgorithms(
                "[{\"algorithm_id\":\"Ed25519\",\"display_name\":\"Ed25519\","
                        + "\"deprecation_date\":null,\"parameters\":null}]");
        stubRegisterKeyOk();
        pullPacketPacketsAvailable = 1;
        stubSubmitResultSuperseded();
    }

    /**
     * Configures Ed25519 announced with a future deprecation date, register-key success, no
     * packets.
     *
     * @param futureDate a date in the future
     */
    void stubAlgorithmWithFutureDeprecation(LocalDate futureDate) {
        stubAlgorithms(
                "[{\"algorithm_id\":\"Ed25519\",\"display_name\":\"Ed25519\","
                        + "\"deprecation_date\":\""
                        + futureDate
                        + "\",\"parameters\":null}]");
        stubRegisterKeyOk();
        pullPacketPacketsAvailable = 0; // immediate 204
    }

    /**
     * Configures Ed25519 announced with a past deprecation date. Worker fails fast before
     * register-key.
     *
     * @param pastDate a date in the past
     */
    void stubAlgorithmWithPastDeprecation(LocalDate pastDate) {
        stubAlgorithms(
                "[{\"algorithm_id\":\"Ed25519\",\"display_name\":\"Ed25519\","
                        + "\"deprecation_date\":\""
                        + pastDate
                        + "\",\"parameters\":null}]");
        // No register-key config — worker fails fast
    }

    /**
     * Configures Ed25519 with future deprecation (race-condition), register-key returns HTTP 410.
     *
     * @param futureDate a date in the future (algorithm appears valid at announcement)
     */
    void stubRegisterKeyReturns410(LocalDate futureDate) {
        stubAlgorithms(
                "[{\"algorithm_id\":\"Ed25519\",\"display_name\":\"Ed25519\","
                        + "\"deprecation_date\":\""
                        + futureDate
                        + "\",\"parameters\":null}]");
        registerKeyStatusCode.set(410);
        registerKeyResponseBody.set("Deprecated");
    }

    void reset() {
        recordedRequests.clear();
        pullPacketCallCount.set(0);
        pullPacketPacketsAvailable = 0;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // =========================================================================
    // Private stub configuration helpers
    // =========================================================================

    private void stubAlgorithms(String json) {
        algorithmsResponseBody.set(json);
    }

    private void stubRegisterKeyOk() {
        registerKeyStatusCode.set(200);
        String body =
                "{\"workerId\":\""
                        + workerId
                        + "\","
                        + "\"role\":\"worker\","
                        + "\"algorithm\":\"Ed25519\","
                        + "\"registeredAt\":\""
                        + Instant.now()
                        + "\"}";
        registerKeyResponseBody.set(body);
    }

    private void stubSubmitResultAccepted() {
        submitResultResponseBody.set("{\"accepted\":true,\"reason\":null}");
    }

    private void stubSubmitResultSuperseded() {
        submitResultResponseBody.set("{\"accepted\":false,\"reason\":\"superseded\"}");
    }

    // =========================================================================
    // HTTP handlers
    // =========================================================================

    private void handleAlgorithms(HttpExchange exchange) throws IOException {
        try {
            String body = recordAndReadBody(exchange);
            String responseBody = algorithmsResponseBody.get();
            sendJsonResponse(exchange, 200, responseBody);
        } catch (Exception e) {
            sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleRegisterKey(HttpExchange exchange) throws IOException {
        try {
            String body = recordAndReadBody(exchange);
            int status = registerKeyStatusCode.get();
            String responseBody = registerKeyResponseBody.get();
            sendJsonResponse(exchange, status, responseBody);
        } catch (Exception e) {
            sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handlePullPacket(HttpExchange exchange) throws IOException {
        try {
            String body = recordAndReadBody(exchange);
            int callNum = pullPacketCallCount.getAndIncrement();
            if (callNum < pullPacketPacketsAvailable) {
                // Return a packet
                String packetResponseJson =
                        "{\"packetId\":\""
                                + packetId
                                + "\","
                                + "\"jobId\":\""
                                + jobId
                                + "\","
                                + "\"packetPayloadJson\":"
                                + escapeJson(VALID_PACKET_PAYLOAD)
                                + ","
                                + "\"timeoutAt\":\""
                                + Instant.now().plusSeconds(60)
                                + "\"}";
                sendJsonResponse(exchange, 200, packetResponseJson);
            } else {
                // No packets — 204 No Content
                exchange.sendResponseHeaders(204, -1);
            }
        } catch (Exception e) {
            sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        } finally {
            exchange.close();
        }
    }

    private void handleSubmitResult(HttpExchange exchange) throws IOException {
        try {
            String body = recordAndReadBody(exchange);
            sendJsonResponse(exchange, 200, submitResultResponseBody.get());
        } catch (Exception e) {
            sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private String recordAndReadBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bytes, StandardCharsets.UTF_8);
        recordedRequests.add(
                new RecordedRequest(
                        exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
        return body;
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // =========================================================================
    // RecordedRequest
    // =========================================================================

    /**
     * A recorded HTTP request for later assertion.
     *
     * @param method HTTP method
     * @param path URL path
     * @param body request body (may be empty for GET requests)
     */
    record RecordedRequest(String method, String path, String body) {}
}
