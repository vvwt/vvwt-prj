// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { parseTimeToSeconds, formatTimeSeconds } from './timerApi.js';
import type { TimerScheduleEntry } from './timerApi.js';

// ── Ephemeral config types ─────────────────────────────────────────────────────

/** Per-phase ephemeral config override (AC5). */
export interface EphemeralPhaseConfig {
  /** Lap duration in minutes (must be > 0). */
  lapTimeMinutes: number;
  /** Break between laps in minutes (must be >= 0). */
  lapBreakTimeMinutes: number;
  /**
   * Section break after this phase in minutes (must be >= 0; ignored for last phase).
   *
   * E11S14 AC6: PhaseConfigRow no longer manages this value (PHASEN-PAUSE input removed).
   * The section break is now edited on the dedicated SECTION_BREAK schedule row. PhaseConfigRow
   * omits this field from its onUpdate call; the timeline recompute reads it from the
   * ephemeralBreakConfig for the SECTION_BREAK entry instead. Optional here so PhaseConfigRow
   * does not need to pass a placeholder value.
   */
  sectionBreakTimeMinutes?: number;
}

/** Per-break-entry ephemeral config override (AC6 — ADDITIONAL breaks). */
export interface EphemeralBreakConfig {
  /** Duration in minutes (must be > 0). */
  durationMinutes: number;
  /** Optional label override. */
  label?: string;
}

// ── Helpers ────────────────────────────────────────────────────────────────────

/** Convert minutes to seconds. */
function minutesToSeconds(minutes: number): number {
  return Math.round(minutes * 60);
}

/** Format seconds-since-midnight as HH:MM (the schedule time format). */
function secsToHHMM(secs: number): string {
  return formatTimeSeconds(secs);
}

// ── Core recompute ─────────────────────────────────────────────────────────────

/**
 * Recomputes the schedule's wall-clock times based on ephemeral operator edits.
 *
 * Algorithm (FE-port of DefaultDraftService Strategy-i):
 * 1. Determine the planned start time from the first entry with a startTime.
 *    If no entry has a startTime, the original schedule is returned as-is (no times configured).
 * 2. For each entry in order, compute its startTime based on:
 *    - For ROUND entries: previous ROUND end time + applicable lap-break gap
 *    - For BREAK entries: previous entry's end time
 *    - The end time of a ROUND entry = its start time + effective lap duration
 *    - The end time of a REGULAR BREAK = its start time + effective lap-break duration
 *    - The end time of an ADDITIONAL BREAK = its start time + effective additional-break duration
 * 3. Uses ephemeral phase config for each phase's lap/break/sectionBreak values;
 *    falls back to values derived from the original schedule if no override present.
 *
 * @param schedule Original schedule from the server
 * @param ephemeralPhaseConfig Map from phaseNumber → ephemeral phase config overrides
 * @param ephemeralBreakConfig Map from entryIndex → ephemeral break config overrides
 * @returns New schedule array with updated startTime/endTime; all other fields preserved
 */
export function recomputeSchedule(
  schedule: TimerScheduleEntry[],
  ephemeralPhaseConfig: ReadonlyMap<number, EphemeralPhaseConfig>,
  userEditedBreakConfig: ReadonlyMap<number, EphemeralBreakConfig>,
): TimerScheduleEntry[] {
  if (schedule.length === 0) return [];

  // If no entries have startTimes, no recompute is possible — return as-is.
  const firstWithTime = schedule.find(e => e.startTime != null);
  if (!firstWithTime || !firstWithTime.startTime) return [...schedule];

  // Parse planned start time (first entry's startTime as baseline).
  const baselineSecs = parseTimeToSeconds(firstWithTime.startTime);
  if (baselineSecs === null) return [...schedule];

  // Build derived config: for each phase, compute effective lap/break/section times.
  // Use ephemeral override if present, otherwise derive from original schedule.
  const phaseNums = [...new Set(
    schedule.filter(e => e.type === 'ROUND').map(e => e.phaseNumber ?? 0)
  )].sort((a, b) => a - b);

  // Derive per-phase original durations from the original schedule if no override.
  // We approximate from the first ROUND entry's start/end time difference if available.
  function getEffectiveConfig(phaseNumber: number): EphemeralPhaseConfig {
    const override = ephemeralPhaseConfig.get(phaseNumber);
    if (override) return override;
    // Derive from original schedule: find lap duration from first ROUND entry's end-start diff.
    const roundEntries = schedule.filter(e => e.type === 'ROUND' && e.phaseNumber === phaseNumber);
    let lapTimeMinutes = 15; // default
    if (roundEntries.length > 0) {
      const first = roundEntries[0];
      if (first.startTime && first.endTime) {
        const s = parseTimeToSeconds(first.startTime);
        const e = parseTimeToSeconds(first.endTime);
        if (s !== null && e !== null && e > s) {
          lapTimeMinutes = (e - s) / 60;
        }
      }
    }
    // Derive lap break time from the first REGULAR BREAK in this phase.
    let lapBreakTimeMinutes = 0;
    const breakEntries = schedule.filter(
      e => e.type === 'BREAK' && e.breakType === 'REGULAR' &&
      (() => {
        // Regular breaks between ROUND entries of the same phase
        const idx = schedule.indexOf(e);
        const prevRound = schedule.slice(0, idx).reverse().find(x => x.type === 'ROUND');
        const nextRound = schedule.slice(idx + 1).find(x => x.type === 'ROUND');
        return prevRound?.phaseNumber === phaseNumber && nextRound?.phaseNumber === phaseNumber;
      })()
    );
    if (breakEntries.length > 0) {
      const b = breakEntries[0];
      if (b.startTime && b.endTime) {
        const s = parseTimeToSeconds(b.startTime);
        const e = parseTimeToSeconds(b.endTime);
        if (s !== null && e !== null && e > s) {
          lapBreakTimeMinutes = (e - s) / 60;
        }
      }
    }
    // Derive section break from the ADDITIONAL break after the last round of this phase.
    let sectionBreakTimeMinutes = 0;
    const lastRoundIdx = schedule.map((e, i) => ({ e, i })).filter(
      ({ e }) => e.type === 'ROUND' && e.phaseNumber === phaseNumber
    ).at(-1)?.i ?? -1;
    if (lastRoundIdx >= 0 && lastRoundIdx + 1 < schedule.length) {
      const nextEntry = schedule[lastRoundIdx + 1];
      if (nextEntry && nextEntry.type === 'BREAK' && nextEntry.breakType === 'ADDITIONAL') {
        if (nextEntry.startTime && nextEntry.endTime) {
          const s = parseTimeToSeconds(nextEntry.startTime);
          const e = parseTimeToSeconds(nextEntry.endTime);
          if (s !== null && e !== null && e > s) {
            sectionBreakTimeMinutes = (e - s) / 60;
          }
        }
      }
    }
    return { lapTimeMinutes, lapBreakTimeMinutes, sectionBreakTimeMinutes };
  }

  // Build result array — start from baseline, walk forward.
  const result: TimerScheduleEntry[] = [];
  let cursor = baselineSecs; // current wall-clock position in seconds

  for (let i = 0; i < schedule.length; i++) {
    const entry = schedule[i];
    const cfg = entry.phaseNumber ? getEffectiveConfig(entry.phaseNumber) : getEffectiveConfig(0);

    if (entry.type === 'ROUND') {
      // ROUND entry: start at cursor, duration = lapTimeMinutes
      const lapSecs = minutesToSeconds(cfg.lapTimeMinutes);
      const startTime = secsToHHMM(cursor);
      const endTime = secsToHHMM(cursor + lapSecs);
      result.push({ ...entry, startTime, endTime });
      cursor = cursor + lapSecs;
    } else if (entry.type === 'BREAK') {
      if (entry.breakType === 'REGULAR') {
        // Lap break: duration = lapBreakTimeMinutes
        const breakSecs = minutesToSeconds(cfg.lapBreakTimeMinutes);
        const startTime = secsToHHMM(cursor);
        const endTime = secsToHHMM(cursor + breakSecs);
        result.push({ ...entry, startTime, endTime });
        cursor = cursor + breakSecs;
      } else {
        // ADDITIONAL break: use user-edited override if present, else original duration
        const breakOverride = userEditedBreakConfig.get(i);
        let breakSecs: number;
        if (breakOverride) {
          breakSecs = minutesToSeconds(breakOverride.durationMinutes);
        } else {
          // Derive from original entry
          if (entry.startTime && entry.endTime) {
            const s = parseTimeToSeconds(entry.startTime);
            const e = parseTimeToSeconds(entry.endTime);
            if (s !== null && e !== null && e > s) {
              breakSecs = e - s;
            } else {
              breakSecs = minutesToSeconds(cfg.sectionBreakTimeMinutes ?? 0);
            }
          } else {
            breakSecs = minutesToSeconds(cfg.sectionBreakTimeMinutes);
          }
        }
        const label = breakOverride?.label ?? entry.label;
        const startTime = secsToHHMM(cursor);
        const endTime = secsToHHMM(cursor + breakSecs);
        result.push({ ...entry, startTime, endTime, label });
        cursor = cursor + breakSecs;
      }
    } else {
      // Unknown type — pass through unchanged
      result.push({ ...entry });
    }
  }

  return result;
}

/**
 * Returns true if operator-authored edits are present (phase config or break config).
 *
 * E11S16 AC2: Only checks user-edit maps, NOT the baseline pre-population map
 * (ephemeralBreakConfig). The baseline pre-population map is used for display
 * only (ScheduleRow input values) and does NOT constitute an operator override.
 * This ensures effectiveSchedule() passes through backend schedule verbatim on
 * fresh page load when no inline edits have been made.
 *
 * @param ephemeralPhaseConfig User-edited phase config overrides (set by handleInlinePhaseConfigUpdate)
 * @param userEditedBreakConfig User-edited break config overrides (set by handleInlineBreakUpdate)
 */
export function hasEphemeralOverrides(
  ephemeralPhaseConfig: ReadonlyMap<number, EphemeralPhaseConfig>,
  userEditedBreakConfig: ReadonlyMap<number, EphemeralBreakConfig>,
): boolean {
  return ephemeralPhaseConfig.size > 0 || userEditedBreakConfig.size > 0;
}
