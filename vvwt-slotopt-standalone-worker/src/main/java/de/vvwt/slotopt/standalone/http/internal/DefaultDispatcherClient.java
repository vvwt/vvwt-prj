// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.http.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.vvwt.slotopt.standalone.http.AnnouncedAlgorithm;
import de.vvwt.slotopt.standalone.http.AnnouncedAlgorithmsResponse;
import de.vvwt.slotopt.standalone.http.DispatcherClient;
import de.vvwt.slotopt.standalone.http.DispatcherException;
import de.vvwt.slotopt.standalone.http.PullPacketRequest;
import de.vvwt.slotopt.standalone.http.PullPacketResponse;
import de.vvwt.slotopt.standalone.http.RegisterKeyRequest;
import de.vvwt.slotopt.standalone.http.RegisterKeyResponse;
import de.vvwt.slotopt.standalone.http.SubmitResultRequest;
import de.vvwt.slotopt.standalone.http.SubmitResultResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Default implementation of {@link DispatcherClient} using JDK 11+ {@link HttpClient}.
 *
 * <p>Uses no third-party HTTP library per DEC-3 minimal-dependency philosophy. JSON
 * (de)serialization is done via Jackson ({@code jackson-databind} + {@code jackson-datatype-jsr310}
 * for {@code LocalDate} / {@code Instant} fields).
 *
 * <p>DEC-35-by-analogy: implementation resides in {@code http.internal}; the public interface
 * {@link DispatcherClient} is in the {@code http} package root.
 *
 * <p>Story: E41S04 AC-DEFAULT-DISPATCHER-CLIENT; E41S05
 * AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT, AC-SUBMIT-RESULT-WITH-ALGORITHM.
 */
public class DefaultDispatcherClient implements DispatcherClient {

    private static final String CONTENT_TYPE_JSON = "application/json";

    private final URI dispatcherUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new {@code DefaultDispatcherClient}.
     *
     * @param dispatcherUrl dispatcher base URL (e.g., {@code http://dispatcher.example.com:8080});
     *     must not be {@code null}
     * @param httpTimeout HTTP request timeout per DEC-3 / AC-DEFAULT-DISPATCHER-CLIENT; must not be
     *     {@code null}
     */
    public DefaultDispatcherClient(URI dispatcherUrl, Duration httpTimeout) {
        this.dispatcherUrl = dispatcherUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(httpTimeout).build();
        this.objectMapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** {@inheritDoc} */
    @Override
    public AnnouncedAlgorithmsResponse fetchAnnouncedAlgorithms() throws DispatcherException {
        URI uri = dispatcherUrl.resolve("/api/algorithms");
        HttpRequest request =
                HttpRequest.newBuilder(uri).GET().header("Accept", CONTENT_TYPE_JSON).build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DispatcherException(
                        response.statusCode(),
                        "GET /api/algorithms returned HTTP " + response.statusCode(),
                        null);
            }
            List<AnnouncedAlgorithm> algorithms =
                    objectMapper.readValue(
                            response.body(), new TypeReference<List<AnnouncedAlgorithm>>() {});
            return new AnnouncedAlgorithmsResponse(algorithms);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DispatcherException(
                    0,
                    "GET /api/algorithms failed: " + e.getMessage(),
                    e instanceof IOException ? e : null);
        }
    }

    /** {@inheritDoc} */
    @Override
    public RegisterKeyResponse registerKey(RegisterKeyRequest request) throws DispatcherException {
        URI uri = dispatcherUrl.resolve("/api/register-key");
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest =
                    HttpRequest.newBuilder(uri)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .header("Content-Type", CONTENT_TYPE_JSON)
                            .header("Accept", CONTENT_TYPE_JSON)
                            .build();
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DispatcherException(
                        response.statusCode(),
                        "POST /api/register-key returned HTTP " + response.statusCode(),
                        null);
            }
            return objectMapper.readValue(response.body(), RegisterKeyResponse.class);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DispatcherException(
                    0,
                    "POST /api/register-key failed: " + e.getMessage(),
                    e instanceof IOException ? e : null);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PullPacketResponse> pullPacketOptional(PullPacketRequest request)
            throws DispatcherException {
        URI uri = dispatcherUrl.resolve("/api/pull-packet");
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest =
                    HttpRequest.newBuilder(uri)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .header("Content-Type", CONTENT_TYPE_JSON)
                            .header("Accept", CONTENT_TYPE_JSON)
                            .build();
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DispatcherException(
                        response.statusCode(),
                        "POST /api/pull-packet returned HTTP " + response.statusCode(),
                        null);
            }
            return Optional.of(objectMapper.readValue(response.body(), PullPacketResponse.class));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DispatcherException(
                    0,
                    "POST /api/pull-packet failed: " + e.getMessage(),
                    e instanceof IOException ? e : null);
        }
    }

    /** {@inheritDoc} */
    @Override
    public SubmitResultResponse submitResult(SubmitResultRequest request)
            throws DispatcherException {
        URI uri = dispatcherUrl.resolve("/api/submit-result");
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest =
                    HttpRequest.newBuilder(uri)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .header("Content-Type", CONTENT_TYPE_JSON)
                            .header("Accept", CONTENT_TYPE_JSON)
                            .build();
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DispatcherException(
                        response.statusCode(),
                        "POST /api/submit-result returned HTTP " + response.statusCode(),
                        null);
            }
            return objectMapper.readValue(response.body(), SubmitResultResponse.class);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DispatcherException(
                    0,
                    "POST /api/submit-result failed: " + e.getMessage(),
                    e instanceof IOException ? e : null);
        }
    }
}
