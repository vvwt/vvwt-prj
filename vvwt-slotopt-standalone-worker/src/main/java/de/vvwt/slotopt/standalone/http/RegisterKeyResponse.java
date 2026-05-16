// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.http;

import java.time.Instant;
import java.util.UUID;

/**
 * HTTP response body for {@code POST /api/register-key} from the standalone worker's perspective.
 *
 * <p>Mirrors the wire shape of the dispatcher's {@code RegisterKeyResponse} record (per Brief D-10;
 * implementer read {@code vvwt-slotopt-dispatcher/…/identity/RegisterKeyResponse.java} for field
 * shape). Fields: {@code workerId}, {@code role}, {@code algorithm}, {@code registeredAt}.
 *
 * <p>Story: E41S04 AC-REGISTER-KEY-WITH-ALGORITHM, AC-DISPATCHER-CLIENT-INTERFACE.
 */
public record RegisterKeyResponse(
        UUID workerId, String role, String algorithm, Instant registeredAt) {}
