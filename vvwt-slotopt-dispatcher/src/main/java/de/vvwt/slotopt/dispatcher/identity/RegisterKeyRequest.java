// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.identity;

import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/register-key}.
 *
 * <p>JSON field names match the E37S02 spec section (b) verbatim per
 * AC-REGISTER-KEY-REQUEST-RESPONSE (E37S05). Jackson serializes/deserializes using the record
 * component names.
 *
 * <p>The {@code algorithm} field is REQUIRED (per Brief D-2 + D-6; backward-compat clause applies
 * only to {@code supportedAlgorithms[]} on pull-packet, NOT to this field).
 *
 * <p>Story: E37S05; Spec: E37S02 spec (b)
 */
public record RegisterKeyRequest(
        UUID workerId, String role, String algorithm, byte[] publicKeyBytes) {}
