package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.draft.DraftSection;

/**
 * REST response DTO for a single draft section (AC2, AC3 — E05S06).
 *
 * @see DraftResponse
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06</a>
 */
public record DraftSectionResponse(
        int sectionNumber,
        String sortType,
        int groupCount,
        String gameMode,
        int lapBreakTimeMinutes,
        int sectionBreakTimeMinutes,
        int lapTimeMinutes,
        int setQuantity
) {

    /**
     * Maps a {@link DraftSection} domain object to this response DTO.
     *
     * @param section the domain object
     * @return the response DTO
     */
    public static DraftSectionResponse from(DraftSection section) {
        return new DraftSectionResponse(
                section.getSectionNumber(),
                section.getSortType(),
                section.getGroupCount(),
                section.getGameMode(),
                section.getLapBreakTimeMinutes(),
                section.getSectionBreakTimeMinutes(),
                section.getLapTimeMinutes(),
                section.getSetQuantity()
        );
    }
}
