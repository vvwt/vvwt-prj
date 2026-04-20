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
 * {@link de.vvwt.tm.tournament.internal.draft.DraftSection#validate()} and {@link
 * de.vvwt.tm.tournament.internal.draft.DraftSection#validateBreaks(int)}.
 *
 * <p>Inventory: E21S01 line 438. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see DraftRequest
 * @see DraftBreakRequest
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
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
        @NotBlank String gameMode,
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
        @Valid List<DraftBreakRequest> breaks) {}
