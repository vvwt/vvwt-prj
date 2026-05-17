// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for the {@code POST /api/pull-packet} endpoint (HTTP 200 case).
 *
 * <p>Per AC-PULL-PACKET-DTOs:
 *
 * <ul>
 *   <li>{@code packetId} — UUID of the claimed packet
 *   <li>{@code jobId} — UUID of the owning job
 *   <li>{@code packetPayloadJson} — JSON payload for the worker (contains rank interval + job def)
 *   <li>{@code timeoutAt} — deadline for result submission
 * </ul>
 *
 * <p>Empty response (no unclaimed packets) is represented as HTTP 204 No Content, not by this DTO.
 *
 * <p>Story: E37S08; AC-PULL-PACKET-DTOs; DEC-35
 */
public record PullPacketResponse(
        UUID packetId, UUID jobId, String packetPayloadJson, Instant timeoutAt) {}
