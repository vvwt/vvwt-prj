package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * Request body for POST /api/tournaments (AC3 — E05S04).
 *
 * <p>All required fields are annotated with {@link NotBlank} or {@link NotNull} +
 * {@link Min}. Validation errors are handled by
 * {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} and returned as
 * HTTP 400 with field-level error details (AC10).
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S04.story.md">Story E05S04</a>
 */
public record TournamentCreateRequest(

        /** Human-readable tournament label (required). */
        @NotBlank(message = "description is required")
        String description,

        /** Optional tournament date. May be null. */
        LocalDateTime appointment,

        /** Number of teams (required, ≥ 2 per AC3). */
        @NotNull(message = "teamCount is required")
        @Min(value = 2, message = "teamCount must be at least 2")
        Integer teamCount,

        /** Number of courts (required, ≥ 1 per AC3). */
        @NotNull(message = "fieldCount is required")
        @Min(value = 1, message = "fieldCount must be at least 1")
        Integer fieldCount,

        /** Match format enum name (required). Valid values: BEST_OF_1, BEST_OF_3, BEST_OF_5,
         *  BEST_OF_7, FIXED_2_SETS. Validated by TournamentService against the MatchFormat enum. */
        @NotBlank(message = "matchFormat is required")
        String matchFormat,

        /** Spring bean ID of the scoring rule (required). */
        @NotBlank(message = "scoringRuleId is required")
        String scoringRuleId,

        /** Spring bean ID of the set validation rule (required). */
        @NotBlank(message = "setValidationRuleId is required")
        String setValidationRuleId,

        /** Spring bean ID of the match generator (required). */
        @NotBlank(message = "matchGeneratorId is required")
        String matchGeneratorId
) {}
