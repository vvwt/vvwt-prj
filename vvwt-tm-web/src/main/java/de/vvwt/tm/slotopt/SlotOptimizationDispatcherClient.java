// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.tm.slotopt.internal.DispatcherRegistrationContext;
import java.util.Optional;
import java.util.UUID;

/**
 * Client interface for the vvwt-slotopt-dispatcher HTTP API (Leg 2 routing per DEC-11 + DEC-43).
 *
 * <p>Provides the HTTP operations required for Leg 2 slot-optimization dispatch:
 *
 * <ol>
 *   <li>{@link #register()} — POST {@code /api/register-key} with TM's Ed25519 public key per
 *       DEC-43 D2; returns the parsed registration context including the announced algorithm list
 *       per DEC-43 D1.
 *   <li>{@link #submitJob(RawPhaseDef)} — POST {@code /api/submit-job} with the structural phase
 *       payload per DEC-9 (no UUIDs); returns the assigned job UUID.
 *   <li>{@link #pollResult(UUID, CancellationToken)} — GET {@code /api/job-status/{id}} until
 *       COMPLETED or timeout; supports cooperative cancellation (E63S06).
 *   <li>{@link #fetchBestSoFar(UUID)} — GET {@code /api/job-status/{id}} once and extract the
 *       {@code bestSoFar} field (E63S06 Leg-2 cancel case 2).
 * </ol>
 *
 * <h2>DEC-11 boundary</h2>
 *
 * <p>This interface and its implementation MUST NOT import any class from {@code
 * vvwt-slotopt-dispatcher}. The Maven Enforcer rule in {@code vvwt-tm-web/pom.xml}
 * (AC-MAVEN-ENFORCER-RULE) makes this boundary build-checked.
 *
 * <h2>Error handling</h2>
 *
 * <p>Algorithm-mismatch errors (HTTP 400 per E37S09 server enforcement) are wrapped in {@link
 * DispatcherAlgorithmMismatchException}. All other wire errors (HTTP 5xx, network failure, timeout)
 * are wrapped in {@link RuntimeException} subclasses — callers (specifically {@link
 * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient}) catch these and fall through to Leg 3
 * (AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR).
 *
 * <h2>Threading</h2>
 *
 * <p>Implementations are expected to be thread-safe and Spring-singleton-safe.
 *
 * @see de.vvwt.tm.slotopt.internal.DefaultSlotOptimizationDispatcherClient
 * @see DispatcherReachabilityService
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-11.md">DEC-11</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D1–D3</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-11/D-11a</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E27S03.story.md">Story E27S03</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E63S06.story.md">Story E63S06</a>
 */
public interface SlotOptimizationDispatcherClient {

    /**
     * Registers TM's Ed25519 public key with the dispatcher per DEC-43 D2.
     *
     * <p>POSTs to {@code /api/register-key} with the TM worker UUID, role {@code "tm-submitter"},
     * algorithm {@code "Ed25519"} (DEC-43 D4 V1), and the raw public key bytes from {@link
     * de.vvwt.slotopt.worker.identity.WorkerKeyManager#getPublicKeyBytes()}.
     *
     * <p>The response is parsed for the DEC-43 D1 algorithm list. If the registration response
     * includes a {@code deprecation_date} on the registered algorithm, a {@link
     * SlotOptimizationDeprecationWarningEvent} is published via Spring's {@code
     * ApplicationEventPublisher} (DEC-43 D3 + DEC-48).
     *
     * @return the registration context, including the algorithm list from the registration response
     * @throws RuntimeException on HTTP error or network failure
     */
    DispatcherRegistrationContext register();

    /**
     * Submits a slot-optimization job to the dispatcher per DEC-9.
     *
     * <p>POSTs to {@code /api/submit-job} with the structural phase payload ({@code phaseId, rows:
     * [[{group, pos}, ...]]}). No UUIDs in the payload (DEC-9 boundary).
     *
     * @param rawPhaseDef the structural phase definition; must not be null
     * @return the assigned job UUID from the dispatcher's 202 Accepted response
     * @throws DispatcherAlgorithmMismatchException if the dispatcher returns HTTP 400 for algorithm
     *     mismatch
     * @throws RuntimeException on other HTTP errors or network failure
     */
    UUID submitJob(RawPhaseDef rawPhaseDef);

    /**
     * Polls the dispatcher for the result of the given job, using exponential backoff. Supports
     * cooperative cancellation via a {@link CancellationToken} (E63S06 —
     * AC-TEST-CANCEL-INTERRUPTS-POLL).
     *
     * <p>GETs {@code /api/job-status/{id}} until the job status is {@code COMPLETED} or the
     * configured poll timeout is exceeded. If {@code token} is non-null and {@code
     * token.isCancelled()} is {@code true} at any poll iteration, returns {@link Optional#empty()}
     * immediately (the caller then fetches bestSoFar for the Leg-2 BSF resolution).
     *
     * @param jobId the job UUID assigned by {@link #submitJob(RawPhaseDef)}
     * @param token cooperative cancellation token; may be {@code null} (no cancellation check)
     * @return the permutation result if the job completed within the poll timeout, or {@link
     *     Optional#empty()} if the job is still pending, timed out, or cancelled
     * @throws DispatcherAlgorithmMismatchException if the dispatcher returns HTTP 400 for algorithm
     *     mismatch during result polling
     * @throws RuntimeException on other HTTP errors
     */
    Optional<int[]> pollResult(UUID jobId, CancellationToken token);

    /**
     * Backwards-compatible single-argument overload. Delegates to {@link #pollResult(UUID,
     * CancellationToken)} with a {@code null} token (no cancellation).
     *
     * @param jobId the job UUID assigned by {@link #submitJob(RawPhaseDef)}
     * @return the permutation result if the job completed within the poll timeout, or {@link
     *     Optional#empty()} on timeout or wire error
     */
    default Optional<int[]> pollResult(UUID jobId) {
        return pollResult(jobId, null);
    }

    /**
     * Fetches the best-so-far result from the dispatcher for an in-progress job (E63S06,
     * AC-TEST-CANCEL-CASE-PARTIAL-BEST-SO-FAR — case 2 of Leg-2 cancel BSF resolution).
     *
     * <p>GETs {@code /api/job-status/{id}} once and extracts the {@code bestSoFar} field from the
     * response. This field is populated by the dispatcher as packets complete (E60S04).
     *
     * <ul>
     *   <li>If {@code bestSoFar} is non-null → returns {@code Optional.of(new int[]{bestRank})}.
     *   <li>If {@code bestSoFar} is null (no packet completed yet) → returns {@link
     *       Optional#empty()}.
     *   <li>On wire error → returns {@link Optional#empty()} (case 3 fallback — never throws to the
     *       caller; the phase retains its existing L1+L2 assignment per
     *       AC-ERR-BEST-SO-FAR-FETCH-FAILURE).
     * </ul>
     *
     * <p>DEC-9: carries only structural data (bestRank). No team UUIDs or names. DEC-11: HTTP only
     * — no compile dependency on {@code vvwt-slotopt-dispatcher}.
     *
     * @param jobId the job UUID to query
     * @return the best lap-permutation rank found so far, or {@link Optional#empty()} if none
     */
    Optional<int[]> fetchBestSoFar(UUID jobId);
}
