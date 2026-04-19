package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.timeline.TimelineEntry;
import java.time.LocalTime;

/**
 * REST response DTO for a single timeline entry in the draft preview (AC3 — E08S05).
 *
 * <p>Returned as part of {@link DraftPreviewResponse#timeline()} when a tournament has a {@code
 * plannedStartTime} set. Exposes the phase number, lap number, type, start time, end time, and
 * optional label for each timeline entry.
 *
 * <p>The {@code type} field uses the string name of {@link
 * de.vvwt.tm.domain.timeline.TimelineEntryType}: {@code MATCH_ROUND}, {@code LAP_BREAK}, {@code
 * INTRA_PHASE_BREAK}, {@code SECTION_BREAK}.
 *
 * @see DraftPreviewResponse
 * @see de.vvwt.tm.domain.timeline.TimelineEntry
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S05.story.md">Story
 *     E08S05 AC3</a>
 */
public record DraftTimelineEntryResponse(
        int phaseNumber,
        int lapNumber,
        String type,
        LocalTime startTime,
        LocalTime endTime,
        String label) {

    /**
     * Maps a {@link TimelineEntry} domain object to this response DTO.
     *
     * @param entry the timeline entry (must not be {@code null})
     * @return the corresponding response DTO
     */
    public static DraftTimelineEntryResponse from(TimelineEntry entry) {
        return new DraftTimelineEntryResponse(
                entry.phaseNumber(),
                entry.lapNumber(),
                entry.type().name(),
                entry.startTime(),
                entry.endTime(),
                entry.label());
    }
}
