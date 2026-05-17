// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto;

import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Request body for PUT /api/tm/tournaments/{id} — reconstruction-in-place target (DEC-21/DEC-22).
 *
 * <p>Mirrors {@code de.vvwt.tm.infrastructure.web.dto.TournamentUpdateRequest} but lives in the
 * Modulith target package {@code de.vvwt.tm.tournament.internal.dto}.
 *
 * <p>All fields are optional in the HTTP sense — a {@code null} value means "do not change this
 * field". The service applies only non-null (and valid) values. The appointment and
 * plannedStartTime fields may be set to {@code null} to clear the existing values.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultTournamentService
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal.dto package</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
 * @see <a href="E51S07">E51S07 — AC-IMPL-TOURNAMENT-FORM-CHECKBOX (optimize field)</a>
 */
public record TournamentUpdateRequest(

        /** New description. Applied if not {@code null}. */
        String description,

        /** New appointment date. Applied unconditionally (null means clear the date). */
        LocalDateTime appointment,

        /** New team count. Applied if &gt; 0. */
        @Min(value = 2, message = "teamCount must be at least 2") Integer teamCount,

        /** New field count. Applied if &gt; 0. */
        @Min(value = 1, message = "fieldCount must be at least 1") Integer fieldCount,

        /** New match format enum name. Applied if not {@code null}. */
        String matchFormat,

        /** New scoring rule bean ID. Applied if not {@code null}. */
        String scoringRuleId,

        /** New set validation rule bean ID. Applied if not {@code null}. */
        String setValidationRuleId,

        /** New match generator bean ID. Applied if not {@code null}. */
        String matchGeneratorId,

        /**
         * Optional planned start time for timeline calculation. Applied unconditionally — {@code
         * null} clears the existing value.
         */
        LocalTime plannedStartTime,

        /**
         * Whether slot-optimization should run for this tournament. Applied if not {@code null}.
         * {@code null} means "no change" (matches the nullable-field convention of other fields in
         * this record). E51S07 AC-IMPL-TOURNAMENT-FORM-CHECKBOX.
         */
        Boolean optimize) {}
