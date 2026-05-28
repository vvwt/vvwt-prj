// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer;

/**
 * Phase summary response for the timer data endpoint (AC5 — E11S02 / E26S01).
 *
 * <p>Provides structural information about each phase so the timer UI can render its current
 * position within the tournament.
 *
 * <p>Extended by E11S14 AC1 with a nullable {@code config} sub-block ({@link
 * TimerPhaseConfigResponse}) carrying the operator-configured per-phase values from {@link
 * de.vvwt.tm.tournament.draft.DraftSection} ({@code lapTimeMinutes}, {@code lapBreakTimeMinutes},
 * {@code sectionBreakTimeMinutes}, {@code breaks}). {@code null} when the DraftConfig does not have
 * a matching section for this phase (AC15 partial-DraftConfig path (a)).
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.TimerPhaseResponse} per DEC-40 §2026-04-27
 * Clarification Pattern A (AC-RED-FIRST-TIMER-PHASE-RESPONSE — projection == wire shape; no Clause
 * B (a)/(c)/(d) condition fires; Decision Rule §289-296). Reconstruction of legacy timer phase
 * response DTO (formerly at {@code infrastructure.web.timer.dto}) via D-7 Option γ (E26S01 authors
 * at bounded-context module root per Pattern A).
 *
 * @see TimerDataResponse
 * @see TimerPhaseConfigResponse
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 * @see <a href="contexts/artefacts/stories/E11S14.story.md">Story E11S14</a>
 */
public class TimerPhaseResponse {

    /** 1-based phase sequence number. */
    private int phaseNumber;

    /** Human-readable phase label (e.g., "Phase 1", "Vorrunde"). */
    private String description;

    /** Phase lifecycle status: PENDING, ACTIVE, or COMPLETED. */
    private String status;

    /** Number of laps (match rounds) in this phase. Derived from match data. */
    private int lapCount;

    /**
     * Operator-configured per-phase parameters (E11S14 AC1). {@code null} when the DraftConfig does
     * not have a matching section for this phase (AC15 path (a) / partial-DraftConfig fallback).
     *
     * <p>The Timer SPA hides {@code PhaseConfigRow} for phases where this is {@code null} (AC13
     * fallback option (b)).
     */
    private TimerPhaseConfigResponse config;

    /** Default constructor for Jackson. */
    public TimerPhaseResponse() {}

    public TimerPhaseResponse(int phaseNumber, String description, String status, int lapCount) {
        this.phaseNumber = phaseNumber;
        this.description = description;
        this.status = status;
        this.lapCount = lapCount;
    }

    public int getPhaseNumber() {
        return phaseNumber;
    }

    public void setPhaseNumber(int phaseNumber) {
        this.phaseNumber = phaseNumber;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getLapCount() {
        return lapCount;
    }

    public void setLapCount(int lapCount) {
        this.lapCount = lapCount;
    }

    /**
     * Returns the operator-configured per-phase parameters, or {@code null} if unavailable (E11S14
     * AC1, AC13, AC15).
     */
    public TimerPhaseConfigResponse getConfig() {
        return config;
    }

    public void setConfig(TimerPhaseConfigResponse config) {
        this.config = config;
    }
}
