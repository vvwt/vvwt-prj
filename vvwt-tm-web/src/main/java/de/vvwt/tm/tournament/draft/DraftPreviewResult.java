package de.vvwt.tm.tournament.draft;

import de.vvwt.tm.tournament.internal.dto.draft.DraftTimelineEntryResponse;
import java.util.List;

/**
 * Result returned by {@link de.vvwt.tm.tournament.DraftService#preview}.
 *
 * <p>Combines the structural section preview with an optional list of timeline entries. The
 * timeline entries are expressed as {@link DraftTimelineEntryResponse} — a plain data-carrier DTO
 * owned by E21S07. The timeline domain classes ({@code TimelineCalculationService}, {@code
 * TimelineEntry}) arrive in E21S11; this record's shape is the contract they will produce entries
 * against.
 *
 * <p>When no planned start time is set on the tournament, {@code timeline} is empty (backward
 * compatible).
 *
 * <p>Inventory: E21S01 line 239. Named-interface sub-package placement by E33S04 (DEC-35 retrofit).
 * Legacy {@code de.vvwt.tm.domain.draft.DraftPreviewResult} remains active until E21S13.
 *
 * @param sections structural preview per section; never {@code null}; may be empty
 * @param timeline ordered timeline entries; empty when {@code plannedStartTime} is null
 * @see DraftPreviewSection
 * @see DraftTimelineEntryResponse
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction (DTO shape owner)</a>
 * @see <a href="E21S11">E21S11 — Timeline domain classes (future producer)</a>
 */
public record DraftPreviewResult(
        List<DraftPreviewSection> sections, List<DraftTimelineEntryResponse> timeline) {}
