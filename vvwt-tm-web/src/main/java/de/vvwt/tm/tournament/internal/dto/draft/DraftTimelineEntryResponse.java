package de.vvwt.tm.tournament.internal.dto.draft;

import java.time.LocalTime;

/**
 * REST response DTO for a single timeline entry in the draft preview.
 *
 * <p>Plain data-carrier — does NOT depend on {@code de.vvwt.tm.domain.timeline.TimelineEntry} or
 * future E21S11 timeline domain classes. E21S11's {@code TimelineCalculationService} will produce
 * entries of this shape (or be mapped onto it). E21S07 is the shape-owner.
 *
 * <p>The {@code type} field uses string names of timeline entry types: {@code MATCH_ROUND}, {@code
 * LAP_BREAK}, {@code INTRA_PHASE_BREAK}, {@code SECTION_BREAK}.
 *
 * <p>Inventory: E21S01 line 440. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see DraftPreviewResponse
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction (shape owner)</a>
 * @see <a href="E21S11">E21S11 — Timeline domain classes (future producer)</a>
 */
public record DraftTimelineEntryResponse(
        int phaseNumber,
        int lapNumber,
        String type,
        LocalTime startTime,
        LocalTime endTime,
        String label) {}
