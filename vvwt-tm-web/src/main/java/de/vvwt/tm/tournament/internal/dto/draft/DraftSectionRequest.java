// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto.draft;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * REST request DTO for a single draft section.
 *
 * <p>Jakarta Validation provides REST-layer validation. Domain-layer validation is performed by
 * {@link de.vvwt.tm.tournament.draft.DraftSection#validate()} and {@link
 * de.vvwt.tm.tournament.draft.DraftSection#validateBreaks(int)}.
 *
 * <p>Inventory: E21S01 line 438. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * <h2>E51S15 / E58S02 — distributionMode field</h2>
 *
 * <p>{@code distributionMode} is optional in the request (may be absent/null). The domain class
 * {@link de.vvwt.tm.tournament.draft.DraftSection} defaults null to {@code "sequential"}. When
 * present, the value is forwarded as-is (plain String); registry-membership validation occurs at
 * draft save/apply time. Migrated from {@code DistributionMode} enum to {@code String} by E58S02
 * (DEC-73 D-2).
 *
 * <h2>E58S01 — gameMode migrated from GameMode enum to String (DEC-73 D-5)</h2>
 *
 * <p>{@link #gameMode} is now a plain {@code String} (registry key) instead of the removed {@code
 * GameMode} enum. Jackson deserializes the JSON string value directly. {@code @NotBlank} validates
 * presence at the REST layer. Registry-membership validation (AC6) occurs in {@link
 * de.vvwt.tm.tournament.internal.DefaultDraftService} via {@link
 * de.vvwt.tm.tournament.draft.DraftConfig#validateGameModeMembership(java.util.Set)}.
 *
 * @see DraftRequest
 * @see DraftBreakRequest
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E51S15">E51S15 — distributionMode feature</a>
 * @see <a href="E58S01">E58S01 — gameMode String migration; AC5</a>
 * @see <a href="E58S02">E58S02 — distributionMode String migration; AC5</a>
 */
public record DraftSectionRequest(
        @NotNull @Min(value = 1, message = "sectionNumber must be ≥ 1") Integer sectionNumber,
        @NotBlank
                @Pattern(
                        regexp = "team_number|placement_group|group_placement",
                        message =
                                "sortType must be one of: team_number, placement_group,"
                                        + " group_placement")
                String sortType,
        @NotNull @Min(value = 1, message = "groupCount must be ≥ 1") Integer groupCount,
        /**
         * Game mode registry key for this phase (e.g., {@code "roundRobin"}, {@code
         * "awardCeremony"}). Required; must not be blank. Registry-membership validation (AC6,
         * E58S01) occurs in {@link de.vvwt.tm.tournament.internal.DefaultDraftService}.
         *
         * <p>Migrated from {@code GameMode} enum to {@code String} by E58S01 (DEC-73 D-5). Jackson
         * deserializes the JSON value as a plain String.
         */
        @NotBlank(message = "gameMode must not be blank") String gameMode,
        @NotNull @Min(value = 0, message = "lapBreakTimeMinutes must be ≥ 0")
                Integer lapBreakTimeMinutes,
        @NotNull @Min(value = 0, message = "sectionBreakTimeMinutes must be ≥ 0")
                Integer sectionBreakTimeMinutes,
        @NotNull @Min(value = 1, message = "lapTimeMinutes must be > 0") Integer lapTimeMinutes,
        @NotNull @Min(value = 1, message = "setQuantity must be ≥ 1") Integer setQuantity,
        /**
         * Optional intra-phase breaks. May be {@code null} (treated as empty list). Each break is
         * independently validated.
         */
        @Valid List<DraftBreakRequest> breaks,
        /**
         * Team distribution algorithm for Phase-1 avatar assignment. Optional — absent/null
         * defaults to {@code "sequential"} in the domain class. When present, the value is
         * forwarded as a plain String. Registry-membership validation occurs at draft save/apply
         * time (AC6, E58S02). Migrated from {@code DistributionMode} enum to {@code String} by
         * E58S02 (DEC-73 D-2).
         *
         * @see de.vvwt.tm.tournament.draft.DraftSection#getDistributionMode()
         * @see <a href="E51S15">E51S15 — distributionMode feature (sequential default + round-robin
         *     toggle)</a>
         * @see <a href="E58S02">E58S02 — DistributionMode enum removed</a>
         */
        String distributionMode) {}
