package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.constraints.Min;

import java.time.LocalDateTime;

/**
 * Request body for PUT /api/tournaments/{id} (AC4 — E05S04).
 *
 * <p>All fields are optional in the HTTP sense — a {@code null} value means "do not change
 * this field". The service applies only non-null (and valid) values. The appointment field
 * may be set to {@code null} to clear the tournament date.
 *
 * <p>Bean validation is applied only to fields that are present (non-null). Null values
 * are skipped by the service layer rather than rejected — this is a partial-update pattern.
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S04.story.md">Story E05S04</a>
 */
public record TournamentUpdateRequest(

        /** New description. Applied if not {@code null}. */
        String description,

        /** New appointment date. Applied unconditionally (null means clear the date). */
        LocalDateTime appointment,

        /** New team count. Applied if &gt; 0. */
        @Min(value = 2, message = "teamCount must be at least 2")
        Integer teamCount,

        /** New field count. Applied if &gt; 0. */
        @Min(value = 1, message = "fieldCount must be at least 1")
        Integer fieldCount,

        /** New match format enum name. Applied if not {@code null}. */
        String matchFormat,

        /** New scoring rule bean ID. Applied if not {@code null}. */
        String scoringRuleId,

        /** New set validation rule bean ID. Applied if not {@code null}. */
        String setValidationRuleId,

        /** New match generator bean ID. Applied if not {@code null}. */
        String matchGeneratorId
) {}
