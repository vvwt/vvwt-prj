package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * REST request DTO for a single draft section (AC1 field mapping — E05S06; extended by E08S05).
 *
 * <p>Jakarta Validation annotations provide REST-layer validation. Domain-layer validation is
 * performed by {@link de.vvwt.tm.domain.draft.DraftSection#validate()} and {@link
 * de.vvwt.tm.domain.draft.DraftSection#validateBreaks(int)}.
 *
 * <p>E08S05: the {@code breaks} field is added to support intra-phase break configuration (AC2,
 * AC5).
 *
 * @see DraftRequest
 * @see DraftBreakRequest
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story
 *     E05S06 AC1</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S05.story.md">Story
 *     E08S05 AC2</a>
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
         * Optional intra-phase breaks for this section (AC2 — E08S05). May be {@code null} (treated
         * as empty list). Each break is independently validated.
         */
        @Valid List<DraftBreakRequest> breaks) {}
