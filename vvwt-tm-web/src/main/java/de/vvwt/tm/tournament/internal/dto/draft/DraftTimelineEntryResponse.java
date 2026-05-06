package de.vvwt.tm.tournament.internal.dto.draft;

import java.time.LocalTime;

/**
 * REST response DTO for a single timeline entry in the draft preview.
 *
 * <p>Plain data-carrier mapping from {@link de.vvwt.tm.tournament.TimelineEntry} (E21S11). The
 * {@link de.vvwt.tm.tournament.TimelineCalculationService} produces {@code TimelineEntry} domain
 * records; {@code DefaultDraftService.toResponse()} (E48S12) maps them to this DTO. E21S07 is the
 * shape-owner; E48S12 activates the production pipeline.
 *
 * <p>The {@code type} field uses the string name of {@link
 * de.vvwt.tm.tournament.TimelineEntryType}: {@code MATCH_ROUND}, {@code LAP_BREAK}, {@code
 * INTRA_PHASE_BREAK}, {@code SECTION_BREAK}.
 *
 * <p>Inventory: E21S01 line 440. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see DraftPreviewResponse
 * @see de.vvwt.tm.tournament.TimelineEntry
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction (shape owner)</a>
 * @see <a href="E21S11">E21S11 — TimelineCalculationService (producer)</a>
 * @see <a href="E48S12">E48S12 — Timeline wiring (active production pipeline)</a>
 */
public record DraftTimelineEntryResponse(
        int phaseNumber,
        int lapNumber,
        String type,
        LocalTime startTime,
        LocalTime endTime,
        String label) {}
