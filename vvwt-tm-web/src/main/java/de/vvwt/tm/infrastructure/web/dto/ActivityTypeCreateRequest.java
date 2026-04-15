package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/tournaments/{tournamentId}/activity-types (E08S06, AC1).
 *
 * <p>All mandatory fields are annotated with {@link NotBlank}. Validation errors are handled
 * by {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} and returned as HTTP 400.
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S06.story.md">Story E08S06</a>
 */
public record ActivityTypeCreateRequest(

        /** Activity name, unique within the tournament (required). */
        @NotBlank(message = "name is required")
        String name,

        /**
         * Assignment rule identifier (required). V1 valid value: {@code "FIRST_FREE_ROUND"}.
         * Service validates against the {@link de.vvwt.tm.domain.AssignmentRule} enum.
         */
        @NotBlank(message = "assignmentRule is required")
        String assignmentRule,

        /**
         * Optional capacity limit: max teams per round. {@code null} means unlimited.
         * Non-null must be &gt; 0 (validated by domain service).
         */
        @Min(value = 1, message = "capacityPerRound must be at least 1 if provided")
        Integer capacityPerRound,

        /**
         * Display ordering. Defaults to 0 if not provided (non-primitive — service caller
         * normalises {@code null} to 0).
         */
        Integer sortOrder
) {}
