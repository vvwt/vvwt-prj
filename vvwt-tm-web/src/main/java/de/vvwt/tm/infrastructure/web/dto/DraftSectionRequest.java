package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * REST request DTO for a single draft section (AC1 field mapping — E05S06).
 *
 * <p>Jakarta Validation annotations provide REST-layer validation. Domain-layer validation
 * is performed by {@link de.vvwt.tm.domain.draft.DraftSection#validate()}.
 *
 * @see DraftRequest
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06 AC1</a>
 */
public record DraftSectionRequest(

        @NotNull
        @Min(value = 1, message = "sectionNumber must be ≥ 1")
        Integer sectionNumber,

        @NotBlank
        @Pattern(
            regexp = "team_number|placement_group|group_placement",
            message = "sortType must be one of: team_number, placement_group, group_placement"
        )
        String sortType,

        @NotNull
        @Min(value = 1, message = "groupCount must be ≥ 1")
        Integer groupCount,

        @NotBlank
        String gameMode,

        @NotNull
        @Min(value = 0, message = "lapBreakTimeMinutes must be ≥ 0")
        Integer lapBreakTimeMinutes,

        @NotNull
        @Min(value = 0, message = "sectionBreakTimeMinutes must be ≥ 0")
        Integer sectionBreakTimeMinutes,

        @NotNull
        @Min(value = 1, message = "lapTimeMinutes must be > 0")
        Integer lapTimeMinutes,

        @NotNull
        @Min(value = 1, message = "setQuantity must be ≥ 1")
        Integer setQuantity

) {}
