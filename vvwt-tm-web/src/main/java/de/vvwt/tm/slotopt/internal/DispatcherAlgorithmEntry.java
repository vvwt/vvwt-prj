// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.Map;

/**
 * TM-side representation of a single algorithm entry in the DEC-43 D1 algorithm-announcement list
 * returned WITH the dispatcher registration response.
 *
 * <p>Field names use snake_case per the DEC-43 D1 wire schema. Jackson's {@code @JsonProperty}
 * annotations ensure correct deserialization from the dispatcher's JSON response.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} allows the dispatcher to add new fields
 * (e.g., performance hints) in future minor schema versions without breaking this client (DEC-21
 * forward-compat semantics for envelopes).
 *
 * <h2>DEC-43 D1 wire fields</h2>
 *
 * <ul>
 *   <li>{@code algorithm_id} — server-canonical identifier (e.g., {@code "Ed25519"}); never null
 *   <li>{@code display_name} — human-readable name; never null
 *   <li>{@code deprecation_date} — ISO-8601 date or null; null means accepted indefinitely
 *   <li>{@code parameters} — JSON object or null; null for parameterless algorithms (Ed25519 V1)
 * </ul>
 *
 * @param algorithmId server-canonical algorithm identifier; never null
 * @param displayName human-readable name for operator/admin UIs; never null
 * @param deprecationDate null means not deprecated; non-null means the algorithm is deprecated
 *     effective the first instant of the day AFTER this date (DEC-48 boundary semantics)
 * @param parameters algorithm-specific parameter set; null for Ed25519 V1
 * @see DispatcherRegistrationContext
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D1</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatcherAlgorithmEntry(
        @JsonProperty("algorithm_id") String algorithmId,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("deprecation_date") LocalDate deprecationDate,
        @JsonProperty("parameters") Map<String, Object> parameters) {}
