package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.draft.DraftPreviewSection;

import java.util.List;

/**
 * REST response body for the draft preview endpoint (AC4 — E05S06).
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06 AC4</a>
 */
public record DraftPreviewResponse(List<DraftPreviewSectionResponse> sections) {

    /**
     * Maps a list of {@link DraftPreviewSection} domain objects to this response DTO.
     *
     * @param previews the domain preview sections
     * @return the response DTO
     */
    public static DraftPreviewResponse from(List<DraftPreviewSection> previews) {
        return new DraftPreviewResponse(
                previews.stream()
                        .map(DraftPreviewSectionResponse::from)
                        .toList()
        );
    }
}
