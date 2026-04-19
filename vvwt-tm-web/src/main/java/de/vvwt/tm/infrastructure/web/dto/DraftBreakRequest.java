package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * REST request DTO for a single intra-phase break within a draft section (E08S05 AC2, AC5).
 *
 * <p>Jakarta Validation enforces REST-layer constraints. Domain-layer semantic validation (valid
 * lap position, no duplicates) is performed by {@link
 * de.vvwt.tm.domain.draft.DraftSection#validateBreaks(int)}.
 *
 * @see DraftSectionRequest
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S05.story.md">Story
 *     E08S05 AC2, AC8</a>
 */
public record DraftBreakRequest(
        @NotNull @Min(value = 1, message = "afterLapNumber must be ≥ 1") Integer afterLapNumber,
        @NotNull @Min(value = 1, message = "durationMinutes must be > 0") Integer durationMinutes,

        /** Optional display label (e.g., "Mittagspause"). May be {@code null} or blank. */
        String label) {}
