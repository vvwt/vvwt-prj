// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.http;

import java.util.List;
import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/pull-packet} from the standalone worker's perspective.
 *
 * <p>Worker-side DTO mirroring the dispatcher's {@code PullPacketRequest} wire shape (per Brief
 * D-10, O-6 (i); implementer reads {@code
 * vvwt-slotopt-dispatcher/.../packet/PullPacketRequest.java} for exact field shape).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code workerId} — UUID of the requesting worker (required)
 *   <li>{@code supportedAlgorithms} — list of algorithm IDs the worker supports per DEC-43 D2 (V1:
 *       always {@code ["Ed25519"]}); must not be null or empty
 * </ul>
 *
 * <p>Story: E41S05 AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT.
 */
public record PullPacketRequest(UUID workerId, List<String> supportedAlgorithms) {}
