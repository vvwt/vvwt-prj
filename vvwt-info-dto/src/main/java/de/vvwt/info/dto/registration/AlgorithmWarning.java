// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.dto.registration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

/**
 * Warning embedded in a {@link RegistrationResponse} when the client registered using an algorithm
 * whose {@code deprecation_date} is non-null and in the future (AC8 / DEC-43 D3).
 *
 * <p>Field shape only — population semantics (i.e., computing {@code days_remaining}, deciding when
 * to include this warning) are owned by E38S04.
 *
 * @param algorithm_id the algorithm that is approaching deprecation
 * @param deprecation_date the date after which new registrations with this algorithm are rejected
 * @param days_remaining number of days until the deprecation date; informational, computed
 *     server-side
 * @see <a href="../../../../../../../../docs/governance/stories/E38S02.story.md">E38S02</a>
 */
public record AlgorithmWarning(
        @JsonProperty("algorithm_id") String algorithm_id,
        @JsonProperty("deprecation_date") LocalDate deprecation_date,
        @JsonProperty("days_remaining") Integer days_remaining) {}
