// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.Map;

/**
 * Immutable value object representing a single algorithm entry in the DEC-43 D1 announcement list.
 *
 * <p>This record is the wire payload for {@code GET /api/algorithms}. Field names use snake_case
 * per the DEC-43 D1 schema, enforced via {@code @JsonProperty} annotations on each record
 * component.
 *
 * <p>Why a record: {@code AnnouncedAlgorithm} is an immutable value object with no behavior — the
 * canonical Java record use case (per story notes / DEC-35 records-for-VOs convention; the
 * records-as-entity-classes-forbidden clause does NOT apply — this is a wire-payload VO, not a
 * Spring Data JDBC entity).
 *
 * <h2>DEC-43 D1 wire schema</h2>
 *
 * <pre>
 * {
 *   "algorithm_id":    "Ed25519",        // server-canonical identifier; never null
 *   "display_name":    "Ed25519",        // human-readable name; never null
 *   "deprecation_date": null,            // ISO-8601 date or null
 *   "parameters":       null             // JSON object or null
 * }
 * </pre>
 *
 * <p>Spec: E40S02 AC-ANNOUNCED-ALGORITHM-RECORD; DEC-43 § D1.
 */
public record AnnouncedAlgorithm(
        @JsonProperty("algorithm_id") String algorithmId,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("deprecation_date") LocalDate deprecationDate,
        @JsonProperty("parameters") Map<String, Object> parameters) {}
