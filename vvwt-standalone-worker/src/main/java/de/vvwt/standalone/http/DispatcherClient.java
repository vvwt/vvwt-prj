package de.vvwt.standalone.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.vvwt.worker.types.CanonicalPhaseDef;
import de.vvwt.worker.types.PacketResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * HTTP client for the three dispatcher endpoints used by the worker:
 * <ol>
 *   <li>{@code POST /register-key} — E01S06 AC1</li>
 *   <li>{@code POST /pull-packet} — E01S07 AC3</li>
 *   <li>{@code POST /submit-result} — E01S08 AC1</li>
 * </ol>
 *
 * <p>This class is a thin adapter. It does not implement retry or back-off logic —
 * that is the responsibility of {@code WorkerLoop}.
 *
 * <p>Implements Story E01S05 AC2, AC3, AC4, AC5, AC6 (HTTP layer only).
 */
public final class DispatcherClient {

    /** Timeout for individual HTTP requests. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String dispatcherBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a {@code DispatcherClient}.
     *
     * @param dispatcherBaseUrl base URL of the dispatcher (no trailing slash)
     */
    public DispatcherClient(String dispatcherBaseUrl) {
        this(dispatcherBaseUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /**
     * Package-visible constructor for testing — allows injecting a custom {@link HttpClient}.
     */
    DispatcherClient(String dispatcherBaseUrl, HttpClient httpClient) {
        if (dispatcherBaseUrl == null || dispatcherBaseUrl.isBlank()) {
            throw new IllegalArgumentException("dispatcherBaseUrl must not be null or blank");
        }
        this.dispatcherBaseUrl = dispatcherBaseUrl;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // -------------------------------------------------------------------------
    // register-key (AC2)
    // -------------------------------------------------------------------------

    /**
     * Result type for register-key calls.
     *
     * @param keyId the UUID assigned by the dispatcher to this registration
     * @param alreadyRegistered true when the dispatcher returned 200 for an already-known key
     */
    public record RegisterResult(UUID keyId, boolean alreadyRegistered) {}

    /**
     * Calls {@code POST /register-key} with role=worker.
     *
     * @param publicKeyBytes raw 32-byte Ed25519 public key
     * @param name           optional human-readable label (may be null)
     * @return {@link RegisterResult} on success
     * @throws DispatcherException on 4xx / 5xx / network error
     */
    public RegisterResult registerKey(byte[] publicKeyBytes, String name) throws DispatcherException {
        String base64Key = Base64.getEncoder().encodeToString(publicKeyBytes);
        RegisterKeyRequestBody body = new RegisterKeyRequestBody("worker", base64Key, null, name);
        String bodyJson = toJson(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(dispatcherBaseUrl + "/register-key"))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> response = sendRequest(request);

        if (response.statusCode() == 200) {
            RegisterKeyResponseBody resp = fromJson(response.body(), RegisterKeyResponseBody.class);
            return new RegisterResult(resp.keyId(), false);
        }
        throw new DispatcherException(response.statusCode(), response.body(), "register-key");
    }

    // -------------------------------------------------------------------------
    // pull-packet (AC3)
    // -------------------------------------------------------------------------

    /**
     * Result type for pull-packet calls.
     */
    public sealed interface PullResult permits PullResult.PacketAssigned, PullResult.NoWork {

        /** A packet was assigned. */
        record PacketAssigned(
                UUID packetId,
                UUID jobId,
                CanonicalPhaseDef canonicalPhaseDef,
                int n,
                long rankFrom,
                long rankTo,
                Instant deadline
        ) implements PullResult {}

        /** No packets are currently available (dispatcher returned 204). */
        record NoWork() implements PullResult {}
    }

    /**
     * Calls {@code POST /pull-packet} with a signed nonce.
     *
     * @param workerKeyId  UUID of the registered worker key
     * @param signature    Base64-encoded Ed25519 signature of the canonical nonce bytes
     * @param signedNonce  ISO-8601 instant string that was signed
     * @return {@link PullResult.PacketAssigned} or {@link PullResult.NoWork}
     * @throws DispatcherException on 4xx / 5xx / network error
     */
    public PullResult pullPacket(UUID workerKeyId, String signature, String signedNonce)
            throws DispatcherException {
        PullPacketRequestBody body = new PullPacketRequestBody(workerKeyId, signature, signedNonce);
        String bodyJson = toJson(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(dispatcherBaseUrl + "/pull-packet"))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> response = sendRequest(request);

        if (response.statusCode() == 204) {
            return new PullResult.NoWork();
        }
        if (response.statusCode() == 200) {
            PullPacketResponseBody resp = fromJson(response.body(), PullPacketResponseBody.class);
            // n is the permutation size, which equals rowCount (rows are what get permuted).
            // See PacketSolver: it generates permutations of [0..n) and uses them as row sequences.
            int n = resp.jobDef().rowCount();
            return new PullResult.PacketAssigned(
                    resp.packetId(),
                    resp.jobId(),
                    resp.jobDef(),
                    n,
                    resp.rankFrom(),
                    resp.rankTo(),
                    resp.deadline()
            );
        }
        throw new DispatcherException(response.statusCode(), response.body(), "pull-packet");
    }

    // -------------------------------------------------------------------------
    // submit-result (AC3, AC4)
    // -------------------------------------------------------------------------

    /**
     * Calls {@code POST /submit-result}.
     *
     * @param packetId            UUID of the solved packet
     * @param jobId               UUID of the owning job
     * @param result              the PacketResult from PacketSolver
     * @param workerKeyId         UUID of the registered worker key
     * @param base64Signature     Base64-encoded Ed25519 signature over the 72-byte canonical payload
     * @return true if this was the first accepted result; false if late-logged
     * @throws DispatcherException on 4xx / 5xx / network error
     */
    public boolean submitResult(
            UUID packetId,
            UUID jobId,
            PacketResult result,
            UUID workerKeyId,
            String base64Signature
    ) throws DispatcherException {
        SubmitResultRequestBody body = new SubmitResultRequestBody(
                packetId,
                jobId,
                result.bestRank(),
                Double.toString(result.bestScore()),
                result.permutationsScored(),
                result.wallClockNanos(),
                workerKeyId,
                base64Signature
        );
        String bodyJson = toJson(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(dispatcherBaseUrl + "/submit-result"))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> response = sendRequest(request);

        if (response.statusCode() == 200) {
            SubmitResultResponseBody resp = fromJson(response.body(), SubmitResultResponseBody.class);
            return resp.firstResult();
        }
        throw new DispatcherException(response.statusCode(), response.body(), "submit-result");
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private HttpResponse<String> sendRequest(HttpRequest request) throws DispatcherException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DispatcherException(-1, e.getMessage(), request.uri().getPath(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DispatcherException(-1, "Request interrupted", request.uri().getPath(), e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize request body", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize response body: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal JSON DTO records (package-private for testing)
    // -------------------------------------------------------------------------

    record RegisterKeyRequestBody(String role, String publicKey, String supersedes, String name) {}

    record RegisterKeyResponseBody(UUID keyId, String role, Instant registeredAt) {}

    record PullPacketRequestBody(UUID workerKeyId, String signature, String signedNonce) {}

    record PullPacketResponseBody(
            UUID packetId,
            UUID jobId,
            CanonicalPhaseDef jobDef,
            long rankFrom,
            long rankTo,
            Instant deadline
    ) {}

    record SubmitResultRequestBody(
            UUID packetId,
            UUID jobId,
            Long bestRank,
            String bestScore,
            Long permutationsScored,
            Long wallClockNanos,
            UUID workerKeyId,
            String signature
    ) {}

    record SubmitResultResponseBody(
            boolean accepted,
            boolean firstResult,
            Boolean latentlyLogged,
            Boolean duplicate,
            String deadline
    ) {}
}
