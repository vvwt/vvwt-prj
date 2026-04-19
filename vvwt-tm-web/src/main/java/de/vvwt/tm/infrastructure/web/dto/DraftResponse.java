package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.draft.DraftConfig;
import java.util.List;

/**
 * REST response body for draft GET and save endpoints (AC2, AC3 — E05S06).
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story
 *     E05S06 AC2, AC3</a>
 */
public record DraftResponse(List<DraftSectionResponse> sections) {

    /**
     * Maps a {@link DraftConfig} domain object to this response DTO.
     *
     * @param config the domain draft configuration
     * @return the response DTO with all sections mapped
     */
    public static DraftResponse from(DraftConfig config) {
        List<DraftSectionResponse> sectionResponses =
                config.getSections().stream().map(DraftSectionResponse::from).toList();
        return new DraftResponse(sectionResponses);
    }
}
