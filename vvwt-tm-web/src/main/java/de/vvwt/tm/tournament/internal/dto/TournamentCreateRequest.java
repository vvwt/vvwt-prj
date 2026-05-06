package de.vvwt.tm.tournament.internal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Request body for POST /api/tm/tournaments — reconstruction-in-place target (DEC-21/DEC-22).
 *
 * <p>Mirrors {@code de.vvwt.tm.infrastructure.web.dto.TournamentCreateRequest} but lives in the
 * Modulith target package {@code de.vvwt.tm.tournament.internal.dto} (implementation surface, not
 * public API per DEC-21 §Module layout).
 *
 * <p>All required fields carry bean-validation constraints. Validation errors are handled by {@link
 * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} and returned as HTTP 400 with field-level
 * error details.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultTournamentService
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal.dto package</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E08S05">E08S05 — AC4 plannedStartTime introduction</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
 * @see <a href="E48S14">E48S14 — Bug-fix: plannedStartTime missing in CREATE path</a>
 */
public record TournamentCreateRequest(

        /** Human-readable tournament label (required). */
        @NotBlank(message = "description is required") String description,

        /** Optional tournament date. May be null. */
        LocalDateTime appointment,

        /** Number of teams (required, ≥ 2). */
        @NotNull(message = "teamCount is required")
                @Min(value = 2, message = "teamCount must be at least 2")
                Integer teamCount,

        /** Number of courts (required, ≥ 1). */
        @NotNull(message = "fieldCount is required")
                @Min(value = 1, message = "fieldCount must be at least 1")
                Integer fieldCount,

        /**
         * Match format enum name (required). Valid values: BEST_OF_1, BEST_OF_3, BEST_OF_5,
         * BEST_OF_7, FIXED_2_SETS. Validated by service against the MatchFormat enum.
         */
        @NotBlank(message = "matchFormat is required") String matchFormat,

        /** Spring bean ID of the scoring rule (required). */
        @NotBlank(message = "scoringRuleId is required") String scoringRuleId,

        /** Spring bean ID of the set validation rule (required). */
        @NotBlank(message = "setValidationRuleId is required") String setValidationRuleId,

        /** Spring bean ID of the match generator (required). */
        @NotBlank(message = "matchGeneratorId is required") String matchGeneratorId,

        /**
         * Optional planned start time for timeline calculation. Null allowed — mirrors {@link
         * TournamentUpdateRequest#plannedStartTime()} (E08S05 AC4). Bug-fix: previously missing
         * from this record, causing {@code plannedStartTime} to be silently dropped on CREATE.
         *
         * @see <a href="E08S05">E08S05 — AC4 plannedStartTime introduction</a>
         * @see <a href="E48S14">E48S14 — Bug-fix: field was absent from CREATE DTO</a>
         */
        LocalTime plannedStartTime) {}
