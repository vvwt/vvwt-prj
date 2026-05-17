// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minimal HTTP test client for E2E integration testing.
 *
 * <p>Submits jobs to and queries job-status from a live {@code vvwt-slotopt-dispatcher} instance
 * using JDK 11+ {@link HttpClient} (no third-party HTTP lib, DEC-3 minimal-dependency principle).
 *
 * <p>Test-scope only — no production class has any dependency on this helper. DEC-70: no test-only
 * members in production code; this class is exclusively in {@code src/test/java}.
 *
 * <p>Story: E60S05; AC-TEST-E2E-LIVE-WORKER; AC-GOV-NO-MODULE-BOUNDARY-BREACH
 */
class TestJobSubmitter {

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    TestJobSubmitter(URI baseUri) {
        this.baseUri = baseUri;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Submits a slot-optimization job to the dispatcher.
     *
     * @param phase the raw phase definition (DEC-9: no UUIDs)
     * @return the job UUID returned by the dispatcher
     * @throws IOException on HTTP failure
     * @throws InterruptedException on thread interruption
     */
    UUID submitJob(RawPhaseDef phase) throws IOException, InterruptedException {
        // Build JobDef — jobId is opaque to the dispatcher (DEC-9 audit-only metadata)
        CanonicalPhaseDef canonical = buildCanonical(phase);
        JobDef jobDef = new JobDef(UUID.randomUUID(), phase.rowCount(), canonical);

        // Serialize request body as JSON
        String body = objectMapper.writeValueAsString(new SubmitJobRequestBody(jobDef, phase));

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/submit-job"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(10))
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 202) {
            throw new IOException(
                    "submit-job returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode node = objectMapper.readTree(response.body());
        return UUID.fromString(node.get("jobId").asText());
    }

    /**
     * Queries the job status for the given job UUID.
     *
     * @param jobId the job UUID
     * @return the parsed {@link JobStatusDto}
     * @throws IOException on HTTP failure
     * @throws InterruptedException on thread interruption
     */
    JobStatusDto getJobStatus(UUID jobId) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(baseUri.resolve("/api/job-status/" + jobId))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException(
                    "job-status returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode node = objectMapper.readTree(response.body());
        String status = node.get("status").asText();
        int totalPackets = node.get("totalPackets").asInt();
        int completedPackets = node.get("completedPackets").asInt();

        Double bestScore = null;
        Integer bestRank = null;
        JsonNode finalResult = node.get("finalResult");
        if (finalResult != null && !finalResult.isNull()) {
            bestScore = finalResult.get("bestScore").asDouble();
            bestRank = finalResult.get("bestRank").asInt();
        }

        return new JobStatusDto(status, totalPackets, completedPackets, bestRank, bestScore);
    }

    /**
     * Polls job-status until the job reaches {@code COMPLETED} or the timeout elapses.
     *
     * <p>AC-ERR-E2E-DETERMINISTIC: condition-polling — no fixed sleep, retries until condition
     * satisfied.
     *
     * @param jobId the job UUID to poll
     * @param timeoutMs maximum total wait time in milliseconds
     * @param pollIntervalMs wait between polls
     * @return the final {@link JobStatusDto} when status is {@code COMPLETED}
     * @throws InterruptedException if the polling thread is interrupted
     * @throws IOException if a status query fails
     * @throws AssertionError if the timeout elapses before {@code COMPLETED}
     */
    JobStatusDto awaitCompleted(UUID jobId, long timeoutMs, long pollIntervalMs)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JobStatusDto dto = getJobStatus(jobId);
            if ("COMPLETED".equals(dto.status())) {
                return dto;
            }
            Thread.sleep(pollIntervalMs);
        }
        // Final probe after deadline — gives a useful failure message
        JobStatusDto final_ = getJobStatus(jobId);
        throw new AssertionError(
                "Job "
                        + jobId
                        + " did not reach COMPLETED within "
                        + timeoutMs
                        + "ms; last status: "
                        + final_);
    }

    // -------------------------------------------------------------------------
    // Helpers — canonical construction for a simple RawPhaseDef
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal {@link CanonicalPhaseDef} for a given {@link RawPhaseDef}.
     *
     * <p>The canonical form groups positions by row for the scoring kernel. For a phase with R rows
     * and P positions each, canonical is R rows × P canonical columns, each column containing the
     * position indices 0..P-1.
     */
    private static CanonicalPhaseDef buildCanonical(RawPhaseDef phase) {
        int rows = phase.rowCount();
        if (rows == 0) {
            return new CanonicalPhaseDef(0, 0, List.of());
        }
        int positions = phase.rows().get(0).positions().size();
        List<List<Integer>> canonicalRows = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            List<Integer> row = new ArrayList<>();
            for (int p = 0; p < positions; p++) {
                row.add(p);
            }
            canonicalRows.add(List.copyOf(row));
        }
        return new CanonicalPhaseDef(rows, positions, List.copyOf(canonicalRows));
    }

    // -------------------------------------------------------------------------
    // Inner DTOs (test-scope only)
    // -------------------------------------------------------------------------

    /** Typed view of a job-status response body. */
    record JobStatusDto(
            String status,
            int totalPackets,
            int completedPackets,
            Integer bestRank,
            Double bestScore) {}

    /**
     * Request body serialized for {@code POST /api/submit-job}.
     *
     * <p>Field names match the dispatcher's {@code SubmitJobRequest} record serialization exactly.
     */
    record SubmitJobRequestBody(JobDef jobDef, RawPhaseDef phase) {}

    // -------------------------------------------------------------------------
    // Factory helpers for standard test phases
    // -------------------------------------------------------------------------

    /**
     * Builds a simple test {@link RawPhaseDef} with the given number of rows and 2 positions per
     * row.
     *
     * <p>A 2-row, 2-team phase produces n=2 (2! = 2 perms). The decomposer always creates ≥ 4
     * packets for n=2 (minimum packet count rule), guaranteeing the multi-packet path. (DEC-56: N =
     * rowCount.)
     */
    static RawPhaseDef buildPhase(int rowCount) {
        List<RawRow> rows = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            rows.add(new RawRow(List.of(new PositionTuple(r, 0), new PositionTuple(r, 1))));
        }
        return new RawPhaseDef(1, rowCount, List.copyOf(rows));
    }
}
