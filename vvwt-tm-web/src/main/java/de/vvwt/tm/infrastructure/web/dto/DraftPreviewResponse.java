package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.draft.DraftPreviewSection;
import de.vvwt.tm.domain.timeline.TimelineEntry;
import java.util.List;

/**
 * REST response body for the draft preview endpoint (AC4 — E05S06; extended by E08S05 AC3).
 *
 * <p>E08S05: the {@code timeline} field is added. When the tournament has a {@code
 * plannedStartTime} set, this list contains predicted clock-time entries for every match round, lap
 * break, intra-phase break, and section break. When {@code plannedStartTime} is {@code null}, the
 * list is empty — backward compatible with E05S06 clients.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story
 *     E05S06 AC4</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S05.story.md">Story
 *     E08S05 AC3</a>
 */
public record DraftPreviewResponse(
        List<DraftPreviewSectionResponse> sections, List<DraftTimelineEntryResponse> timeline) {

    /**
     * Maps a list of {@link DraftPreviewSection} domain objects (plus optional timeline entries) to
     * this response DTO.
     *
     * @param previews the domain preview sections
     * @param timeline the computed timeline entries; may be empty when no start time is set
     * @return the response DTO
     */
    public static DraftPreviewResponse from(
            List<DraftPreviewSection> previews, List<TimelineEntry> timeline) {
        return new DraftPreviewResponse(
                previews.stream().map(DraftPreviewSectionResponse::from).toList(),
                timeline.stream().map(DraftTimelineEntryResponse::from).toList());
    }
}
