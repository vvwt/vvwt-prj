package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.draft.DraftSection;

import java.util.List;

/**
 * REST response DTO for a single draft section (AC2, AC3 — E05S06; extended by E08S05).
 *
 * <p>E08S05: the {@code breaks} field is added to include intra-phase break configuration (AC2).
 *
 * @see DraftResponse
 * @see DraftBreakResponse
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S05.story.md">Story E08S05 AC2</a>
 */
public record DraftSectionResponse(
        int sectionNumber,
        String sortType,
        int groupCount,
        String gameMode,
        int lapBreakTimeMinutes,
        int sectionBreakTimeMinutes,
        int lapTimeMinutes,
        int setQuantity,
        List<DraftBreakResponse> breaks
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
                section.getSetQuantity(),
                section.getBreaks().stream().map(DraftBreakResponse::from).toList()
        );
    }
}
