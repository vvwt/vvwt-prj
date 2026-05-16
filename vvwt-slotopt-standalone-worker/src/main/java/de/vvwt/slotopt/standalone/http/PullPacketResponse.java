// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.http;

import java.time.Instant;
import java.util.UUID;

/**
 * HTTP response body for {@code POST /api/pull-packet} (HTTP 200 case).
 *
 * <p>Worker-side DTO mirroring the dispatcher's {@code PullPacketResponse} wire shape (per Brief
 * D-10; implementer reads {@code vvwt-slotopt-dispatcher/.../packet/PullPacketResponse.java} for
 * exact field shape).
 *
 * <p>HTTP 204 (no available packets) is represented as an empty {@link java.util.Optional} from
 * {@link DispatcherClient#pullPacketOptional(PullPacketRequest)}.
 *
 * <p>Story: E41S05 AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT, AC-EMPTY-PULL-RESPONSE-BACKOFF.
 */
public record PullPacketResponse(
        UUID packetId, UUID jobId, String packetPayloadJson, Instant timeoutAt) {}
