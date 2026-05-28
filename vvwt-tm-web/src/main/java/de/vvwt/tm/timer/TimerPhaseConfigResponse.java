// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer;

import java.util.List;

/**
 * Per-phase operator-configured parameters sub-block for the timer data response (E11S14 AC1).
 *
 * <p>Nested in {@link TimerPhaseResponse#getConfig()}. Carries the operator-configured values from
 * {@link de.vvwt.tm.tournament.draft.DraftSection} so the Timer SPA can pre-populate the {@code
 * PhaseConfigRow} inputs (AC2, AC3) and the SECTION_BREAK / INTRA_PHASE_BREAK inline-edit {@code
 * Dauer} inputs (AC4, AC5).
 *
 * <p>This field is {@code null} on the containing {@link TimerPhaseResponse} when the {@link
 * de.vvwt.tm.tournament.draft.DraftConfig} does not have a matching section for this phase
 * (partial-DraftConfig case, AC15 path (a)) or when the phase is the zero-lap siegerehrung and the
 * config cannot be reliably inferred. The Timer SPA hides the {@code PhaseConfigRow} for phases
 * with {@code config == null} (AC13 fallback option (b)).
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.TimerPhaseConfigResponse} per DEC-40 §2026-04-27
 * Clarification Pattern A (projection == wire shape; no Clause B (a)/(c)/(d) condition fires).
 *
 * @see TimerPhaseResponse
 * @see TimerBreakConfigEntryResponse
 * @see <a href="contexts/artefacts/stories/E11S14.story.md">Story E11S14</a>
 */
public class TimerPhaseConfigResponse {

    /**
     * Duration of each lap (match round) in minutes. From {@link
     * de.vvwt.tm.tournament.draft.DraftSection#getLapTimeMinutes()}.
     */
    private int lapTimeMinutes;

    /**
     * Break between consecutive laps in minutes. From {@link
     * de.vvwt.tm.tournament.draft.DraftSection#getLapBreakTimeMinutes()}.
     */
    private int lapBreakTimeMinutes;

    /**
     * Break after this phase and before the next, in minutes. From {@link
     * de.vvwt.tm.tournament.draft.DraftSection#getSectionBreakTimeMinutes()}.
     *
     * <p>For the last phase this is typically 0 (no following phase). The Timer SPA hides the
     * dedicated Phasen-Pause row for the last phase.
     */
    private int sectionBreakTimeMinutes;

    /**
     * Intra-phase breaks for this phase. From {@link
     * de.vvwt.tm.tournament.draft.DraftSection#getBreaks()}.
     *
     * <p>Each entry provides {@code durationMinutes} and optional {@code label} for the
     * INTRA_PHASE_BREAK schedule rows (AC5). May be empty; never {@code null}.
     */
    private List<TimerBreakConfigEntryResponse> breaks;

    /** Default constructor for Jackson. */
    public TimerPhaseConfigResponse() {}

    public TimerPhaseConfigResponse(
            int lapTimeMinutes,
            int lapBreakTimeMinutes,
            int sectionBreakTimeMinutes,
            List<TimerBreakConfigEntryResponse> breaks) {
        this.lapTimeMinutes = lapTimeMinutes;
        this.lapBreakTimeMinutes = lapBreakTimeMinutes;
        this.sectionBreakTimeMinutes = sectionBreakTimeMinutes;
        this.breaks = breaks;
    }

    public int getLapTimeMinutes() {
        return lapTimeMinutes;
    }

    public void setLapTimeMinutes(int lapTimeMinutes) {
        this.lapTimeMinutes = lapTimeMinutes;
    }

    public int getLapBreakTimeMinutes() {
        return lapBreakTimeMinutes;
    }

    public void setLapBreakTimeMinutes(int lapBreakTimeMinutes) {
        this.lapBreakTimeMinutes = lapBreakTimeMinutes;
    }

    public int getSectionBreakTimeMinutes() {
        return sectionBreakTimeMinutes;
    }

    public void setSectionBreakTimeMinutes(int sectionBreakTimeMinutes) {
        this.sectionBreakTimeMinutes = sectionBreakTimeMinutes;
    }

    public List<TimerBreakConfigEntryResponse> getBreaks() {
        return breaks;
    }

    public void setBreaks(List<TimerBreakConfigEntryResponse> breaks) {
        this.breaks = breaks;
    }
}
