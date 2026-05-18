// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 * @see <a href="E51S07">E51S07 — AC-IMPL-TOURNAMENT-FORM-CHECKBOX (optimize field)</a>
 * @see <a href="E53S05">E53S05 — Mannschaftsfoto Vorbelegung (seedMannschaftsfoto field)</a>
 * @see <a href="E68S01">E68S01 — Organizer as editable field on tournament form</a>
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
        LocalTime plannedStartTime,

        /**
         * Whether slot-optimization should run as a background job for phases of this tournament.
         * {@code true} enables the pipeline; {@code false} skips slot-opt. Default {@code null}
         * means the server applies the entity default ({@code true} per DEC-55 D-5 + {@code
         * tournament.optimize} column DEFAULT TRUE in schema E51S01). Null is acceptable: the
         * service maps null → {@code true} (default-on per DEC-55 D-5).
         *
         * @see <a href="E51S07">E51S07 — AC-IMPL-TOURNAMENT-FORM-CHECKBOX</a>
         * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-55.md">DEC-55
         *     D-5</a>
         */
        Boolean optimize,

        /**
         * Whether to seed a {@code Mannschaftsfoto} {@link
         * de.vvwt.tm.tournament.activity.ActivityType} with {@code assignment_rule =
         * FIRST_FREE_ROUND} when the tournament is created. {@code true} (or {@code null} → server
         * default {@code true}) pre-selects the Mannschaftsfoto activity type so that the
         * Mannschaftsfoto-Zeitplan link renders on the print-index immediately after tournament
         * creation. {@code false} opts out — no {@code ActivityType} is seeded, and the print-index
         * link remains hidden until manual configuration.
         *
         * <p>This is the {@code Vorbelegung} analog to the {@code optimize} checkbox (E51S07,
         * DEC-55 D-5). Default {@code null} resolves to {@code true} server-side.
         *
         * @see de.vvwt.tm.tournament.activity.ActivityTypeService
         * @see <a href="E53S05">E53S05 — AC1: Mannschaftsfoto Vorbelegung</a>
         */
        Boolean seedMannschaftsfoto,

        /**
         * Organizer name for the tournament (required). Displayed as the certificate subtitle via
         * {@code {{tom_organizer}}} (E46S03). The create form pre-fills this with the
         * organization's current display name; the operator may change it before saving.
         *
         * <p>{@code null} → service falls back to the tenant's {@code display_name} snapshot
         * (preserving the E46S01 INSERT-snapshot behavior for API callers that omit this field).
         *
         * <p>E68S01: organizer is now mutable — intentional reversal of E46S01's write-once
         * handling. When provided (non-{@code null}), the value must not be blank (AC5). When
         * {@code null}, the server derives it from the tenant snapshot.
         *
         * @see <a href="E68S01">E68S01 — Organizer as editable field</a>
         * @see <a href="E46S01">E46S01 — Original snapshot-at-INSERT logic</a>
         */
        @Pattern(regexp = ".*\\S.*", message = "organizer must not be blank when provided")
                String organizer) {}
