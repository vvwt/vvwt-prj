// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.http;

import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/register-key} from the standalone worker's perspective.
 *
 * <p>Mirrors the wire shape of the dispatcher's {@code RegisterKeyRequest} record (per Brief D-10,
 * O-6 (iii); implementer read {@code vvwt-slotopt-dispatcher/…/identity/RegisterKeyRequest.java}
 * for field shape). Field: {@code workerId}, {@code role}, {@code algorithm}, {@code
 * publicKeyBytes}.
 *
 * <p>JSON serialization uses camelCase by default (Jackson record convention), matching the
 * dispatcher's record field names.
 *
 * <p>Story: E41S04 AC-REGISTER-KEY-WITH-ALGORITHM.
 */
public record RegisterKeyRequest(
        UUID workerId, String role, String algorithm, byte[] publicKeyBytes) {}
