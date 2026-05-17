// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.dto.registration;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response payload for a registration request.
 *
 * <p>Per AC8:
 *
 * <ul>
 *   <li>{@code tournament_token} — nullable; populated by the tournament-registration endpoint
 *       (E38S05); {@code null} when returned by the tenant-registration endpoint (E38S04). Field
 *       shape only — population semantics owned by E38S05.
 *   <li>{@code algorithm_warning} — optional; non-null when the client registered using an
 *       algorithm approaching its deprecation date (DEC-43 D3). Field shape only — population
 *       semantics owned by E38S04.
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S02.story.md">E38S02</a>
 */
public record RegistrationResponse(
        @JsonProperty("tournament_token") String tournament_token,
        @JsonProperty("algorithm_warning") AlgorithmWarning algorithm_warning) {}
