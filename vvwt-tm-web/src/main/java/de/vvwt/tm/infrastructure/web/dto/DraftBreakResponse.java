package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.draft.DraftBreak;

/**
 * REST response DTO for a single intra-phase break in a draft section (E08S05 AC2).
 *
 * @see DraftSectionResponse
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S05.story.md">Story
 *     E08S05 AC2</a>
 */
public record DraftBreakResponse(int afterLapNumber, int durationMinutes, String label) {

    /**
     * Maps a {@link DraftBreak} domain object to this response DTO.
     *
     * @param b the domain break
     * @return the response DTO
     */
    public static DraftBreakResponse from(DraftBreak b) {
        return new DraftBreakResponse(b.getAfterLapNumber(), b.getDurationMinutes(), b.getLabel());
    }
}
