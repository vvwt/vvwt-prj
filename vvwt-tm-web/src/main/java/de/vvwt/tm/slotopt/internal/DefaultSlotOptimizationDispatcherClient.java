// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.DispatcherAlgorithmMismatchException;
import de.vvwt.tm.slotopt.SlotOptimizationDeprecationWarningEvent;
import de.vvwt.tm.slotopt.SlotOptimizationDispatcherClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

/**
 * Default implementation of {@link SlotOptimizationDispatcherClient} using Spring Framework 6's
 * {@link RestClient} (synchronous, fluent; Spring Boot 4.0.5 default per DEC-29/30).
 *
 * <h2>DEC-11 boundary</h2>
 *
 * <p>This class MUST NOT import any class from {@code vvwt-slotopt-dispatcher}. The Maven Enforcer
 * rule in {@code vvwt-tm-web/pom.xml} (AC-MAVEN-ENFORCER-RULE) makes this build-checked.
 *
 * <h2>DEC-43 D3 V1 never-fires invariant</h2>
 *
 * <p>The {@link SlotOptimizationDeprecationWarningEvent} publication branch in {@link #register()}
 * is defensive code per DEC-43 D3 + DEC-48. At V1 ship time (DEC-43 D4), the dispatcher announces
 * only {@code "Ed25519"} with {@code null} deprecation_date — this branch never fires at V1. It is
 * active only when Phase-2+ algorithm migration introduces the first non-null deprecation_date into
 * the dispatcher's algorithm registry.
 *
 * <p>Per Brief S-3a: ~50 LOC for protocol-shape compliance; cost ≈ 1h delivery work; V1 fire-rate =
 * zero. The implementation is retained as required by DEC-43 D3 compliance.
 *
 * <h2>Polling cadence</h2>
 *
 * <p>Uses exponential backoff: 1s → 2s → 4s → 8s → 16s → 30s (cap), up to {@code pollTimeoutMs}. On
 * timeout, returns {@link Optional#empty()} and the caller falls through to Leg 3
 * (AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR).
 *
 * @see SlotOptimizationDispatcherClient
 * @see de.vvwt.tm.slotopt.DispatcherReachabilityService
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-11.md">DEC-11</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D1–D4</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-48.md">DEC-48</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S03.story.md">Story
 *     E27S03</a>
 */
public class DefaultSlotOptimizationDispatcherClient implements SlotOptimizationDispatcherClient {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultSlotOptimizationDispatcherClient.class);

    /** Maximum per-poll-backoff interval in milliseconds. */
    private static final long MAX_POLL_BACKOFF_MS = 30_000L;

    /** Initial poll interval in milliseconds. */
    private static final long INITIAL_POLL_INTERVAL_MS = 1_000L;

    private final RestClient restClient;
    private final UUID workerId;
    private final WorkerKeyManager keyManager;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final long pollTimeoutMs;

    /**
     * Constructs the dispatcher client.
     *
     * @param dispatcherBaseUrl the dispatcher's base URL (e.g., {@code http://dispatcher:8080})
     * @param workerId the UUID identifying this TM instance as a registered submitter
     * @param keyManager the worker key manager providing the Ed25519 public key and algorithm ID
     * @param eventPublisher Spring ApplicationEventPublisher for DEC-43 D3 warning events
     * @param objectMapper Jackson ObjectMapper (must have JavaTimeModule registered)
     * @param pollTimeoutMs maximum total time to wait for a job result before returning empty
     */
    public DefaultSlotOptimizationDispatcherClient(
            String dispatcherBaseUrl,
            UUID workerId,
            WorkerKeyManager keyManager,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            long pollTimeoutMs) {
        this.restClient = RestClient.builder().baseUrl(dispatcherBaseUrl).build();
        this.workerId = workerId;
        this.keyManager = keyManager;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.pollTimeoutMs = pollTimeoutMs;
    }

    /**
     * {@inheritDoc}
     *
     * <p>POSTs to {@code /api/register-key} with:
     *
     * <ul>
     *   <li>{@code workerId} — this TM instance's UUID
     *   <li>{@code role} — {@code "tm-submitter"} (the TM submitter role)
     *   <li>{@code algorithm} — {@code "Ed25519"} (V1 per DEC-43 D4)
     *   <li>{@code publicKeyBytes} — raw Ed25519 public key bytes from {@link WorkerKeyManager}
     * </ul>
     *
     * <p>Parses the response for the DEC-43 D1 algorithm list (field {@code algorithms}). If absent
     * from the response (current dispatcher state), returns empty list.
     *
     * <p><strong>DEC-43 D3 V1 never-fires invariant:</strong> The deprecation-warning publication
     * branch below is defensive code for Phase-2+ algorithm migration. V1 wire shape = single
     * "Ed25519" with null deprecation_date → this branch never fires at V1; defensive code per
     * DEC-43 D3 protocol-shape compliance.
     */
    @Override
    public DispatcherRegistrationContext register() {
        String algorithmId = keyManager.algorithmId();
        byte[] publicKeyBytes = keyManager.getPublicKeyBytes();

        // Build the registration request body
        Map<String, Object> requestBody =
                Map.of(
                        "workerId",
                        workerId,
                        "role",
                        "tm-submitter",
                        "algorithm",
                        algorithmId,
                        "publicKeyBytes",
                        Base64.getEncoder().encodeToString(publicKeyBytes));

        LOG.info(
                "DefaultSlotOptimizationDispatcherClient.register: workerId={}, algorithm={}",
                workerId,
                algorithmId);

        // POST /api/register-key
        String responseBody =
                restClient
                        .post()
                        .uri("/api/register-key")
                        .body(requestBody)
                        .header("Content-Type", "application/json")
                        .retrieve()
                        .body(String.class);

        // Parse the extended registration response for DEC-43 D1 algorithm list
        RegisterKeyExtendedResponse parsed;
        try {
            parsed = objectMapper.readValue(responseBody, RegisterKeyExtendedResponse.class);
        } catch (Exception e) {
            LOG.warn(
                    "DefaultSlotOptimizationDispatcherClient.register: failed to parse registration"
                            + " response — using empty algorithm list",
                    e);
            // Defensive: treat as no algorithm list (V1 dispatcher state)
            return new DispatcherRegistrationContext(workerId, List.of());
        }

        List<DispatcherAlgorithmEntry> algorithms =
                parsed.algorithms() != null ? parsed.algorithms() : List.of();

        // Build the registration context
        DispatcherRegistrationContext context =
                new DispatcherRegistrationContext(workerId, algorithms);

        // DEC-43 D3 + DEC-48: publish warning event if any algorithm has a future deprecation date
        //
        // DEC-43 D3 V1 never-fires invariant: V1 wire shape per DEC-43 D4 = "Ed25519" with null
        // deprecation_date → this branch never fires at V1; defensive code per DEC-43 D3
        // protocol-shape compliance.
        for (DispatcherAlgorithmEntry entry : algorithms) {
            LocalDate depDate = entry.deprecationDate();
            if (depDate != null) {
                // DEC-48 boundary: accepted iff
                // Instant.now().isBefore(depDate.plusDays(1).atStartOfDay(UTC))
                Instant deadline = depDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                if (Instant.now().isBefore(deadline)) {
                    // Derive recommended migration target: first non-deprecated algorithm
                    String recommended =
                            algorithms.stream()
                                    .filter(a -> a.deprecationDate() == null)
                                    .map(DispatcherAlgorithmEntry::algorithmId)
                                    .findFirst()
                                    .orElse(null);

                    LOG.warn(
                            "DefaultSlotOptimizationDispatcherClient: algorithm {} is deprecated"
                                    + " (deprecation_date={}); publishing admin warning event."
                                    + " Recommended: {}",
                            entry.algorithmId(),
                            depDate,
                            recommended);

                    eventPublisher.publishEvent(
                            new SlotOptimizationDeprecationWarningEvent(
                                    entry.algorithmId(),
                                    entry.displayName(),
                                    depDate,
                                    recommended));
                }
            }
        }

        return context;
    }

    /**
     * {@inheritDoc}
     *
     * <p>POSTs the structural phase payload to {@code /api/submit-job} per DEC-9.
     */
    @Override
    public UUID submitJob(RawPhaseDef rawPhaseDef) {
        // Build the DEC-9 wire payload (structural tuples only — no UUIDs in rows)
        Map<String, Object> payload = buildSubmitJobPayload(rawPhaseDef);

        LOG.debug(
                "DefaultSlotOptimizationDispatcherClient.submitJob: phaseId={}",
                rawPhaseDef.phaseId());

        try {
            String responseBody =
                    restClient
                            .post()
                            .uri("/api/submit-job")
                            .body(payload)
                            .header("Content-Type", "application/json")
                            .retrieve()
                            .onStatus(
                                    status -> status.value() == 400,
                                    (req, resp) -> {
                                        throw new DispatcherAlgorithmMismatchException(
                                                keyManager.algorithmId(), 400);
                                    })
                            .body(String.class);

            SubmitJobResponseDto response =
                    objectMapper.readValue(responseBody, SubmitJobResponseDto.class);
            return response.jobId();
        } catch (DispatcherAlgorithmMismatchException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "DefaultSlotOptimizationDispatcherClient.submitJob failed: " + e.getMessage(),
                    e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Polls {@code GET /api/job-status/{id}} with exponential backoff until COMPLETED or {@code
     * pollTimeoutMs} elapsed. If {@code token} is non-null and cancelled at any iteration, returns
     * {@link Optional#empty()} immediately (E63S06 AC-TEST-CANCEL-INTERRUPTS-POLL).
     */
    @Override
    public Optional<int[]> pollResult(UUID jobId, CancellationToken token) {
        long startMs = System.currentTimeMillis();
        long backoffMs = INITIAL_POLL_INTERVAL_MS;

        LOG.debug(
                "DefaultSlotOptimizationDispatcherClient.pollResult: jobId={}, timeoutMs={}",
                jobId,
                pollTimeoutMs);

        while (System.currentTimeMillis() - startMs < pollTimeoutMs) {
            // E63S06 AC-TEST-CANCEL-INTERRUPTS-POLL: check cancellation before each poll
            if (token != null && token.isCancelled()) {
                LOG.info(
                        "DefaultSlotOptimizationDispatcherClient.pollResult: jobId={} poll"
                                + " cancelled via CancellationToken — returning empty for"
                                + " Leg-2 BSF resolution",
                        jobId);
                return Optional.empty();
            }

            try {
                String responseBody =
                        restClient
                                .get()
                                .uri("/api/job-status/{id}", jobId)
                                .retrieve()
                                .onStatus(
                                        status -> status.value() == 400,
                                        (req, resp) -> {
                                            throw new DispatcherAlgorithmMismatchException(
                                                    keyManager.algorithmId(), 400);
                                        })
                                .body(String.class);

                JobStatusResponseDto status =
                        objectMapper.readValue(responseBody, JobStatusResponseDto.class);

                if ("COMPLETED".equals(status.status())
                        || "COMPLETED_FROM_CACHE".equals(status.status())) {
                    // Job is done — extract the bestRank from finalResult and return it.
                    // AC-TEST-POLLRESULT-RETURNS-REAL-RESULT (E63S02): return the actual rank so
                    // the caller (RoutingSlotOptimizationClient.tryLeg2) can apply it via
                    // SlotResultApplicator. An empty-array sentinel would leave the phase
                    // unoptimized.
                    // AC-ERR-MALFORMED-RESULT-REJECTED: if finalResult is null/missing, treat as
                    // fetch failure → return empty so caller falls through to Leg 3.
                    if (status.finalResult() == null) {
                        LOG.warn(
                                "DefaultSlotOptimizationDispatcherClient.pollResult: jobId={}"
                                        + " COMPLETED but finalResult is null — treating as fetch"
                                        + " failure, falling through to Leg 3",
                                jobId);
                        return Optional.empty();
                    }
                    long bestRank = status.finalResult().bestRank();
                    LOG.info(
                            "DefaultSlotOptimizationDispatcherClient.pollResult: jobId={}"
                                    + " COMPLETED after {}ms, bestRank={}",
                            jobId,
                            System.currentTimeMillis() - startMs,
                            bestRank);
                    return Optional.of(new int[] {(int) bestRank});
                }

                // Job still in progress — wait with backoff
                LOG.debug(
                        "DefaultSlotOptimizationDispatcherClient.pollResult: jobId={} status={},"
                                + " polling again in {}ms",
                        jobId,
                        status.status(),
                        backoffMs);
            } catch (DispatcherAlgorithmMismatchException e) {
                throw e;
            } catch (Exception e) {
                LOG.warn(
                        "DefaultSlotOptimizationDispatcherClient.pollResult: error polling"
                                + " jobId={}: {}",
                        jobId,
                        e.getMessage());
                throw new RuntimeException(
                        "Poll error for jobId=" + jobId + ": " + e.getMessage(), e);
            }

            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            backoffMs = Math.min(backoffMs * 2, MAX_POLL_BACKOFF_MS);
        }

        // Poll timeout — fall through to Leg 3 at routing level
        LOG.warn(
                "DefaultSlotOptimizationDispatcherClient.pollResult: timeout after {}ms for"
                        + " jobId={}",
                pollTimeoutMs,
                jobId);
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>GETs {@code /api/job-status/{id}} once and extracts the {@code bestSoFar} field. Used for
     * Leg-2 cancel case 2 (E63S06 AC-TEST-CANCEL-CASE-PARTIAL-BEST-SO-FAR).
     *
     * <p>Never throws — on any error, returns {@link Optional#empty()} (phase falls back to case 3:
     * retain existing L1+L2 assignment per AC-ERR-BEST-SO-FAR-FETCH-FAILURE).
     */
    @Override
    public Optional<int[]> fetchBestSoFar(UUID jobId) {
        LOG.debug("DefaultSlotOptimizationDispatcherClient.fetchBestSoFar: jobId={}", jobId);
        try {
            String responseBody =
                    restClient
                            .get()
                            .uri("/api/job-status/{id}", jobId)
                            .retrieve()
                            .body(String.class);

            JobStatusResponseDto status =
                    objectMapper.readValue(responseBody, JobStatusResponseDto.class);

            if (status.bestSoFar() == null) {
                LOG.info(
                        "DefaultSlotOptimizationDispatcherClient.fetchBestSoFar: jobId={}"
                                + " — no bestSoFar (no packet completed) → case 3 (retain L1+L2)",
                        jobId);
                return Optional.empty();
            }

            long bestRank = status.bestSoFar().bestRank();
            LOG.info(
                    "DefaultSlotOptimizationDispatcherClient.fetchBestSoFar: jobId={}"
                            + " — partial bestSoFar rank={} → case 2 (apply partial result)",
                    jobId,
                    bestRank);
            return Optional.of(new int[] {(int) bestRank});

        } catch (Exception e) {
            // AC-ERR-BEST-SO-FAR-FETCH-FAILURE: fetch error → case 3 (never throws)
            LOG.warn(
                    "DefaultSlotOptimizationDispatcherClient.fetchBestSoFar: error for jobId={}"
                            + ": {} → case 3 (retain L1+L2)",
                    jobId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // Internal wire format helpers
    // =========================================================================

    /**
     * Builds the DEC-9 structural-tuple-only payload for submit-job.
     *
     * <p>The {@code jobDef} field contains the new UUID + n + canonicalPhaseDef (from the
     * SubmitJobRequest shape). The {@code phase} field contains the DEC-9 structural rows. No UUIDs
     * appear in the rows.
     */
    private Map<String, Object> buildSubmitJobPayload(RawPhaseDef rawPhaseDef) {
        // Build rows array per RawPhaseDefDeserializer wire format (E37S07):
        // each row = {"positions": [{group: int, pos: int}, ...]}
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RawRow row : rawPhaseDef.rows()) {
            List<Map<String, Integer>> positions = new ArrayList<>();
            for (PositionTuple pt : row.positions()) {
                positions.add(Map.of("group", pt.group(), "pos", pt.pos()));
            }
            rows.add(Map.of("positions", positions));
        }

        // Submit-job payload per SubmitJobRequest shape (E37S07)
        Map<String, Object> phase =
                Map.of(
                        "phaseId", rawPhaseDef.phaseId(),
                        "rowCount", rawPhaseDef.rowCount(),
                        "rows", rows);

        // JobDef: UUID + n + canonicalPhaseDef (E63S08: canonical must include rows)
        Map<String, Object> jobDef =
                Map.of(
                        "jobId", UUID.randomUUID(),
                        "n", rawPhaseDef.rowCount(),
                        "canonicalPhaseDef", buildCanonicalPhaseDefPayload(rawPhaseDef));

        return Map.of("jobDef", jobDef, "phase", phase);
    }

    /**
     * Builds the canonicalPhaseDef payload for the JobDef in the submit-job request.
     *
     * <p>Computes the canonical form from the raw phase def via {@link
     * de.vvwt.slotopt.worker.types.StructuralFingerprint#canonicalize(RawPhaseDef)}, producing a
     * fully-populated {@code CanonicalPhaseDef} with {@code rowCount}, {@code avatarCount}, and
     * {@code rows}. The dispatcher's {@code CanonicalPhaseDef} constructor requires all three
     * fields to be non-null/non-empty (E63S08 — fixes pre-existing omission of {@code rows}).
     */
    private Map<String, Object> buildCanonicalPhaseDefPayload(RawPhaseDef rawPhaseDef) {
        de.vvwt.slotopt.worker.types.CanonicalPhaseDef canonical =
                de.vvwt.slotopt.worker.types.StructuralFingerprint.canonicalize(rawPhaseDef);
        return Map.of(
                "rowCount", canonical.rowCount(),
                "avatarCount", canonical.avatarCount(),
                "rows", canonical.rows());
    }

    // =========================================================================
    // Wire format DTOs (internal to this class — not exposed as module API)
    // =========================================================================

    /**
     * Extended registration response DTO — includes the DEC-43 D1 algorithm list if present. Uses
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} for forward compatibility.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RegisterKeyExtendedResponse(
            @JsonProperty("workerId") UUID workerId,
            @JsonProperty("role") String role,
            @JsonProperty("algorithm") String algorithm,
            @JsonProperty("algorithms") List<DispatcherAlgorithmEntry> algorithms) {}

    /** Submit-job response DTO. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SubmitJobResponseDto(
            @JsonProperty("jobId") UUID jobId,
            @JsonProperty("submittedAt") Instant submittedAt,
            @JsonProperty("cacheHit") boolean cacheHit) {}

    /**
     * Job status response DTO per E37S09 wire format, extended with E60S04 result surfaces.
     *
     * <p>AC-GOV-E60-SURFACE-BINDING (E63S02): {@code finalResult} carries the global optimum
     * ({@code bestRank} / {@code bestScore}) for a COMPLETED job — introduced by E60S04.
     *
     * <p>E63S06 extension: {@code bestSoFar} carries the best result among completed packets for an
     * in-progress job — exposed by E60S04. Used for Leg-2 cancel case 2
     * (AC-TEST-CANCEL-CASE-PARTIAL-BEST-SO-FAR). Null when no packet has completed yet (case 3).
     * Null when the job is COMPLETED (use {@code finalResult} instead).
     *
     * <p>DEC-9: carries only structural data (bestRank / bestScore). No team UUIDs or names.
     *
     * <p>AC-GOV-NO-DISPATCHER-COMPILE-DEP: this DTO is defined independently of {@code
     * vvwt-slotopt-dispatcher}'s {@code OptimumResult} — the Maven Enforcer ban stays.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobStatusResponseDto(
            @JsonProperty("jobId") UUID jobId,
            @JsonProperty("status") String status,
            @JsonProperty("totalPackets") int totalPackets,
            @JsonProperty("completedPackets") int completedPackets,
            @JsonProperty("finalResult") FinalResultDto finalResult,
            @JsonProperty("bestSoFar") FinalResultDto bestSoFar) {}

    /**
     * Local DTO for the {@code finalResult} field of the job-status response (E60S04).
     *
     * <p>Mirrors {@code vvwt-slotopt-dispatcher}'s {@code OptimumResult} shape without introducing
     * a compile dependency (DEC-11 / AC-GOV-NO-DISPATCHER-COMPILE-DEP). Fields: {@code bestRank}
     * (the lap-permutation rank in [0, lapCount!)) and {@code bestScore} (the optimizer's cost).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FinalResultDto(
            @JsonProperty("bestRank") long bestRank, @JsonProperty("bestScore") double bestScore) {}
}
