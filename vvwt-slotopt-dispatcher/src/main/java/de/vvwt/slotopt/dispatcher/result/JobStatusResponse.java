// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import java.util.UUID;

/**
 * Response DTO for the {@code GET /api/job-status/{jobId}} endpoint.
 *
 * <p>Java record per AC-JOB-STATUS-CONTROLLER. Carries aggregated job status, packet completion
 * counts, and — as of E60S04 — the computed result surfaces.
 *
 * <p>Result fields (DEC-9: structural only — no team UUIDs or names):
 *
 * <ul>
 *   <li>{@code finalResult} — present only when {@code status == COMPLETED}; carries the aggregated
 *       global optimum ({@code bestRank} / {@code bestScore}) across all packets of the job. Null
 *       when the job is not yet completed (AC-TEST-JOB-STATUS-FINAL-RESULT).
 *   <li>{@code bestSoFar} — present only when the job is NOT {@code COMPLETED} and at least one
 *       packet result has been retained; carries the best result among completed packets at query
 *       time. Null when no packet has completed yet (AC-ERR-BEST-SO-FAR-NO-COMPLETED-PACKETS). Null
 *       when the job is {@code COMPLETED} (use {@code finalResult} instead —
 *       AC-TEST-JOB-STATUS-BEST-SO-FAR).
 * </ul>
 *
 * <p>Both fields are nullable — JSON serialization omits them via {@code @JsonInclude} on the
 * Jackson configuration (or Jackson's default null-exclusion when configured globally). The
 * controller never exposes an empty-object placeholder; absent means absent.
 *
 * <p>AC-GOV-RESPONSE-EXTENSION-BOUNDED: the granular packet-count breakdown ({@code
 * packetsAssigned}, {@code packetsPending}, {@code packetsFailed}) from the E37-spec is explicitly
 * NOT added — it is observability-only and out of Epic E60 scope. {@code totalPackets} / {@code
 * completedPackets} are retained unchanged.
 *
 * <p>Spec: E37S02 spec section (b) Endpoint 6; AC-JOB-STATUS-CONTROLLER (E37S09);
 * AC-TEST-JOB-STATUS-FINAL-RESULT, AC-TEST-JOB-STATUS-BEST-SO-FAR (E60S04).
 */
public record JobStatusResponse(
        UUID jobId,
        String status,
        int totalPackets,
        int completedPackets,
        OptimumResult finalResult,
        OptimumResult bestSoFar) {}
