// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.runtime;

import java.time.Instant;
import java.util.UUID;

/**
 * HTTP response body for {@code POST /api/pull-packet} (HTTP 200 case).
 *
 * <p>Worker-side DTO mirroring the dispatcher's {@code PullPacketResponse} wire shape (per Brief
 * D-10). HTTP 204 (no available packets) is represented as an empty {@link java.util.Optional} from
 * {@link DispatcherClient#pullPacketOptional(PullPacketRequest)}.
 *
 * <p>Story: E41S05 AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT (moved to E63S01 shared library).
 */
public record PullPacketResponse(
        UUID packetId, UUID jobId, String packetPayloadJson, Instant timeoutAt) {}
