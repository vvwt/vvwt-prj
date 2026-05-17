// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.Map;

/**
 * Worker-side mirror of the DEC-43 D1 algorithm entry in the {@code GET /api/algorithms} response.
 *
 * <p>Field names use snake_case on the wire, enforced via {@code @JsonProperty} annotations, to
 * match the dispatcher's {@code AnnouncedAlgorithm} record (per Brief D-4; implementer read {@code
 * vvwt-slotopt-dispatcher/…/crypto/AnnouncedAlgorithm.java} for exact field shape).
 *
 * <p>This record is the worker-runtime library's own DTO — no dependency on the dispatcher module
 * is required; only the wire shape must match (DEC-3 minimal-dependency, DEC-11 service boundary).
 *
 * <p>Story: E41S04 AC-FETCH-ALGORITHMS-WIRE (moved to shared library E63S01).
 */
public record AnnouncedAlgorithm(
        @JsonProperty("algorithm_id") String algorithmId,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("deprecation_date") LocalDate deprecationDate,
        @JsonProperty("parameters") Map<String, Object> parameters) {}
