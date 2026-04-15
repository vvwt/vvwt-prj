package de.vvwt.tm.domain.draft;

import de.vvwt.tm.domain.timeline.TimelineEntry;

import java.util.List;

/**
 * Result returned by {@link de.vvwt.tm.domain.DraftService#previewDraft} (AC3 — E08S05).
 *
 * <p>Combines the structural preview (section counts) with the optional time-aware
 * timeline. The {@code timeline} list is empty when no {@code plannedStartTime} is set
 * on the tournament, preserving backward compatibility with E05S06 clients.
 *
 * @param sections structural preview per section (always non-empty for a non-empty draft)
 * @param timeline ordered list of timeline entries; empty when {@code plannedStartTime} is null
 *
 * @see DraftPreviewSection
 * @see TimelineEntry
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S05.story.md">Story E08S05 AC3</a>
 */
public record DraftPreviewResult(
        List<DraftPreviewSection> sections,
        List<TimelineEntry> timeline
) {
}
