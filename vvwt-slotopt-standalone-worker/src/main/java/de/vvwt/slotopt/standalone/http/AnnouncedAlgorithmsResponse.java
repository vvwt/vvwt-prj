// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.http;

import java.util.List;

/**
 * Response wrapper for the {@code GET /api/algorithms} endpoint.
 *
 * <p>The dispatcher returns a JSON array of {@link AnnouncedAlgorithm} objects. This record wraps
 * the deserialized list for use in the bootstrap validation logic.
 *
 * <p>Story: E41S04 AC-FETCH-ALGORITHMS-WIRE, AC-DISPATCHER-CLIENT-INTERFACE.
 */
public record AnnouncedAlgorithmsResponse(List<AnnouncedAlgorithm> algorithms) {}
