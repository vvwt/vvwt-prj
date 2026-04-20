package de.vvwt.tm.tournament.internal.dto.draft;

import de.vvwt.tm.tournament.internal.draft.DraftPreviewSection;
import java.util.List;

/**
 * REST response body for the draft preview endpoint.
 *
 * <p>The {@code timeline} field carries predicted clock-time entries for every match round, lap
 * break, intra-phase break, and section break when a {@code plannedStartTime} is set on the
 * tournament. When {@code plannedStartTime} is {@code null}, the list is empty.
 *
 * <p>Inventory: E21S01 line 434. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see de.vvwt.tm.tournament.DraftController
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public record DraftPreviewResponse(
        List<DraftPreviewSectionResponse> sections, List<DraftTimelineEntryResponse> timeline) {

    /**
     * Maps domain preview sections and timeline entries to this response DTO.
     *
     * @param previews the domain preview sections
     * @param timeline the timeline entries; may be empty when no start time is set
     * @return the response DTO
     */
    public static DraftPreviewResponse from(
            List<DraftPreviewSection> previews, List<DraftTimelineEntryResponse> timeline) {
        return new DraftPreviewResponse(
                previews.stream().map(DraftPreviewSectionResponse::from).toList(), timeline);
    }
}
