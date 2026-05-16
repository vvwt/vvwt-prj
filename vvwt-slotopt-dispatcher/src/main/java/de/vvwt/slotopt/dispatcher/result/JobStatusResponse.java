// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import java.util.UUID;

/**
 * Response DTO for the {@code GET /api/job-status/{jobId}} endpoint.
 *
 * <p>Java record per AC-JOB-STATUS-CONTROLLER. Carries aggregated job status and packet completion
 * counts.
 *
 * <p>Spec: E37S02 spec section (b) Endpoint 6; AC-JOB-STATUS-CONTROLLER (E37S09).
 */
public record JobStatusResponse(
        UUID jobId, String status, int totalPackets, int completedPackets) {}
