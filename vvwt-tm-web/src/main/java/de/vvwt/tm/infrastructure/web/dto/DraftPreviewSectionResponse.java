package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.draft.DraftPreviewSection;

/**
 * REST response DTO for one section in a draft preview (AC4 — E05S06).
 *
 * @see DraftPreviewResponse
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story
 *     E05S06 AC4</a>
 */
public record DraftPreviewSectionResponse(
        int phaseNumber,
        int groupCount,
        int teamsPerGroup,
        int matchesPerGroup,
        int totalLaps,
        int totalMatches,
        int estimatedTimeMinutes) {

    /**
     * Maps a {@link DraftPreviewSection} domain object to this response DTO.
     *
     * @param preview the domain preview section
     * @return the response DTO
     */
    public static DraftPreviewSectionResponse from(DraftPreviewSection preview) {
        return new DraftPreviewSectionResponse(
                preview.getPhaseNumber(),
                preview.getGroupCount(),
                preview.getTeamsPerGroup(),
                preview.getMatchesPerGroup(),
                preview.getTotalLaps(),
                preview.getTotalMatches(),
                preview.getEstimatedTimeMinutes());
    }
}
