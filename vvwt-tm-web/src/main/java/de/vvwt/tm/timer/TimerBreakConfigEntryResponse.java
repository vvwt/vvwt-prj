// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer;

/**
 * Per-break entry in a phase's config sub-block (E11S14 AC1, AC5).
 *
 * <p>Represents one intra-phase break ({@link de.vvwt.tm.tournament.draft.DraftBreak}) carried in
 * the {@link TimerPhaseConfigResponse#getBreaks()} list. Provides {@code durationMinutes} and
 * optional {@code label} so the Timer SPA can pre-populate INTRA_PHASE_BREAK schedule-row inline
 * edit inputs (AC5).
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.TimerBreakConfigEntryResponse} per DEC-40 §2026-04-27
 * Clarification Pattern A (projection == wire shape; no Clause B (a)/(c)/(d) condition fires).
 *
 * @see TimerPhaseConfigResponse
 * @see TimerPhaseResponse
 * @see <a href="contexts/artefacts/stories/E11S14.story.md">Story E11S14</a>
 */
public class TimerBreakConfigEntryResponse {

    /** Duration of this break in minutes (must be &gt; 0). */
    private int durationMinutes;

    /** Optional operator-configured display label. May be {@code null}. */
    private String label;

    /** Default constructor for Jackson. */
    public TimerBreakConfigEntryResponse() {}

    public TimerBreakConfigEntryResponse(int durationMinutes, String label) {
        this.durationMinutes = durationMinutes;
        this.label = label;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
