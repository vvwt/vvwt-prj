// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

/**
 * Response DTO for the {@code POST /api/submit-result} endpoint.
 *
 * <p>Java record. {@code accepted=true, reason=null} for the first valid result (first-valid-wins
 * per DEC-6). {@code accepted=false, reason="superseded"} when the packet already has a result.
 *
 * <p>Spec: E37S02 spec section (b) Endpoint 4; AC-SUBMIT-RESULT-DTOs (E37S09).
 */
public record SubmitResultResponse(boolean accepted, String reason) {}
