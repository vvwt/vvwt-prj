/**
 * Countdown engine for the VVWT Timer SPA (E11S04).
 *
 * Responsibilities:
 *  - Resolve the effective schedule (server times + clock offset + user overrides, AC7)
 *  - Determine the current active event index and time remaining until it
 *  - Manage transport state: STOPPED / PLAYING / PAUSED
 *  - Fire audio events when event triggers are reached
 *  - Track round counter (AC6)
 *
 * Design principles:
 *  - No server polling (D-6): uses Date.now() + clockOffsetSeconds only
 *  - Pure logic — no DOM, no HTMLAudioElement; callers handle audio
 *  - All time values in seconds-since-midnight
 */

import { parseTimeToSeconds, applyClockOffset, formatTimeSeconds } from './timerApi.js';
import type { TimerScheduleEntry } from './timerApi.js';

// ── Types ─────────────────────────────────────────────────────────────────────

export type TransportState = 'STOPPED' | 'PLAYING' | 'PAUSED';

/** An audio event that the engine fires when a schedule entry becomes active. */
export type AudioEvent =
  | { type: 'START_SOUND' }      // ROUND entry start → play start sound
  | { type: 'END_SOUND' }        // ROUND entry end → play end sound
  | { type: 'PAUSE_MUSIC_START' } // REGULAR BREAK start → start pause music
  | { type: 'PAUSE_MUSIC_STOP' }  // REGULAR BREAK end → stop pause music
  | { type: 'NONE' };

/** Snapshot of the engine state at a given moment. */
export interface CountdownSnapshot {
  /** Transport state. */
  transportState: TransportState;
  /** Index of the currently active (next-to-fire or currently-playing) event in the effective schedule. */
  activeEventIndex: number;
  /** Seconds remaining until the active event fires. 0 if the event has fired. */
  secondsRemaining: number;
  /** Formatted countdown as "MM:SS". */
  countdownDisplay: string;
  /** Current round number (1-based; 0 when no ROUND event is active or all done). */
  currentRound: number;
  /** Total number of ROUND entries in the schedule. */
  totalRounds: number;
  /** Indices of events that have already fired (status=done). */
  doneIndices: ReadonlySet<number>;
  /** The index of the entry currently "playing" (between its start and the next entry's start). */
  playingIndex: number;
}

// ── Effective schedule resolution ─────────────────────────────────────────────

/**
 * Resolves the effective start time in seconds-since-midnight for a schedule entry.
 *
 * Priority: user override > server time + clock offset > null (no time).
 *
 * @param entry        Schedule entry from the server response
 * @param entryIndex   Index in the schedule array
 * @param overrides    Map of entryIndex → user-override time in seconds-since-midnight (AC7)
 * @param clockOffsetSeconds  Venue clock minus device clock (from ClockSyncDialog)
 * @returns Effective time in seconds-since-midnight, or null if no time is available
 */
export function resolveEffectiveTime(
  entry: TimerScheduleEntry,
  entryIndex: number,
  overrides: ReadonlyMap<number, number>,
  clockOffsetSeconds: number
): number | null {
  // AC7: user override takes priority
  const override = overrides.get(entryIndex);
  if (override !== undefined) return override;

  // Server-provided time with clock offset applied
  if (entry.startTime) {
    return parseTimeToSeconds(applyClockOffset(entry.startTime, clockOffsetSeconds));
  }

  return null;
}

/**
 * Resolves the effective end time for a schedule entry (used for break audio stop).
 */
export function resolveEffectiveEndTime(
  entry: TimerScheduleEntry,
  clockOffsetSeconds: number
): number | null {
  if (entry.endTime) {
    return parseTimeToSeconds(applyClockOffset(entry.endTime, clockOffsetSeconds));
  }
  return null;
}

// ── Audio event classification ─────────────────────────────────────────────────

/**
 * Determines what audio event to fire when the entry at `entryIndex` becomes active,
 * or when the entry transitions from active to done.
 *
 * Firing rules (D-8, AC3):
 *  - ROUND entry becomes active → START_SOUND
 *  - ROUND entry ends (next entry becomes active) → END_SOUND
 *  - REGULAR BREAK becomes active → PAUSE_MUSIC_START
 *  - REGULAR BREAK ends → PAUSE_MUSIC_STOP
 *  - ADDITIONAL break → NONE (both on start and end)
 */
export function getAudioEventOnActivate(entry: TimerScheduleEntry): AudioEvent {
  if (entry.type === 'ROUND') {
    return { type: 'START_SOUND' };
  }
  if (entry.type === 'BREAK' && entry.breakType === 'REGULAR') {
    return { type: 'PAUSE_MUSIC_START' };
  }
  return { type: 'NONE' };
}

export function getAudioEventOnDeactivate(entry: TimerScheduleEntry): AudioEvent {
  if (entry.type === 'ROUND') {
    return { type: 'END_SOUND' };
  }
  if (entry.type === 'BREAK' && entry.breakType === 'REGULAR') {
    return { type: 'PAUSE_MUSIC_STOP' };
  }
  return { type: 'NONE' };
}

// ── Current time helper ────────────────────────────────────────────────────────

/**
 * Returns the current adjusted time in seconds-since-midnight.
 * Applies the clock offset so the result matches venue wall-clock time.
 */
export function nowSeconds(clockOffsetSeconds: number): number {
  const now = new Date();
  const deviceSeconds = now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds()
    + now.getMilliseconds() / 1000;
  return ((deviceSeconds + clockOffsetSeconds) % 86400 + 86400) % 86400;
}

// ── Event index resolution ─────────────────────────────────────────────────────

/**
 * Finds the active event index and seconds remaining.
 *
 * Logic:
 *  - An event is "active" (playing) if now >= its effectiveTime AND now < next event's effectiveTime
 *  - An event is "next" (upcoming) if now < its effectiveTime
 *  - Returns the playing entry if one exists; otherwise the next upcoming entry
 *  - Returns index=-1 if all events are in the past (schedule complete)
 *
 * @param schedule        The full schedule array
 * @param overrides       User time overrides (AC7)
 * @param clockOffsetSeconds  Clock offset from ClockSyncDialog
 * @param currentNow      Current time in seconds-since-midnight (from nowSeconds())
 * @returns { activeEventIndex, secondsRemaining, playingIndex }
 */
export function resolveActiveEvent(
  schedule: TimerScheduleEntry[],
  overrides: ReadonlyMap<number, number>,
  clockOffsetSeconds: number,
  currentNow: number
): { activeEventIndex: number; secondsRemaining: number; playingIndex: number } {
  // Build list of effective times for all entries
  const effectiveTimes: (number | null)[] = schedule.map((entry, i) =>
    resolveEffectiveTime(entry, i, overrides, clockOffsetSeconds)
  );

  // Find the first entry whose time is in the future (next event)
  let nextIndex = -1;
  let nextTime = Infinity;
  for (let i = 0; i < effectiveTimes.length; i++) {
    const t = effectiveTimes[i];
    if (t !== null && t > currentNow && t < nextTime) {
      nextIndex = i;
      nextTime = t;
    }
  }

  // Find the entry currently "playing" (started, next not yet started)
  // An entry is playing if: its time <= now AND (next entry's time > now OR no next entry)
  let playingIndex = -1;
  for (let i = 0; i < effectiveTimes.length; i++) {
    const t = effectiveTimes[i];
    if (t !== null && t <= currentNow) {
      // Check if the NEXT entry with a time has not started yet
      let nextT: number | null = null;
      for (let j = i + 1; j < effectiveTimes.length; j++) {
        if (effectiveTimes[j] !== null) {
          nextT = effectiveTimes[j];
          break;
        }
      }
      if (nextT === null || nextT > currentNow) {
        playingIndex = i;
      }
    }
  }

  if (nextIndex === -1) {
    // All events are in the past
    return { activeEventIndex: -1, secondsRemaining: 0, playingIndex };
  }

  const secondsRemaining = Math.max(0, nextTime - currentNow);
  return { activeEventIndex: nextIndex, secondsRemaining, playingIndex };
}

// ── Round counter ──────────────────────────────────────────────────────────────

/**
 * Computes the current round number and total rounds.
 *
 * Current round = number of ROUND entries that have started (playingIndex >= their index) + 1
 * if a round is currently active; otherwise the count of completed rounds.
 *
 * @param schedule     Full schedule
 * @param playingIndex Index of the currently-playing entry (-1 if none)
 * @returns { currentRound, totalRounds }
 */
export function computeRoundCounter(
  schedule: TimerScheduleEntry[],
  playingIndex: number
): { currentRound: number; totalRounds: number } {
  const roundIndices = schedule
    .map((entry, i) => ({ entry, i }))
    .filter(({ entry }) => entry.type === 'ROUND')
    .map(({ i }) => i);

  const totalRounds = roundIndices.length;

  if (playingIndex === -1 || totalRounds === 0) {
    return { currentRound: 0, totalRounds };
  }

  // Count how many rounds have started (their index <= playingIndex)
  const startedRounds = roundIndices.filter(i => i <= playingIndex).length;
  const currentRound = startedRounds > 0 ? startedRounds : 0;
  return { currentRound, totalRounds };
}

// ── Done indices ───────────────────────────────────────────────────────────────

/**
 * Returns the set of entry indices that are "done" (in the past).
 *
 * An entry is done if:
 *  - Its effective start time <= currentNow, AND
 *  - The NEXT entry with a time also <= currentNow (i.e., the entry is no longer playing)
 *
 * @param schedule   Full schedule
 * @param overrides  User overrides
 * @param clockOffsetSeconds  Clock offset
 * @param currentNow Current time in seconds
 * @param playingIndex The currently playing index (never "done")
 */
export function computeDoneIndices(
  schedule: TimerScheduleEntry[],
  overrides: ReadonlyMap<number, number>,
  clockOffsetSeconds: number,
  currentNow: number,
  playingIndex: number
): Set<number> {
  const done = new Set<number>();
  const effectiveTimes = schedule.map((entry, i) =>
    resolveEffectiveTime(entry, i, overrides, clockOffsetSeconds)
  );

  for (let i = 0; i < schedule.length; i++) {
    const t = effectiveTimes[i];
    if (t !== null && t <= currentNow && i !== playingIndex) {
      done.add(i);
    }
  }
  return done;
}

// ── Snapshot builder ───────────────────────────────────────────────────────────

/**
 * Builds a complete CountdownSnapshot for the given moment in time.
 *
 * @param schedule         Full schedule from server
 * @param overrides        User time overrides map (AC7)
 * @param clockOffsetSeconds  Clock offset from ClockSyncDialog
 * @param transportState   Current transport state
 * @param pausedAt         The nowSeconds() value at which Pause was triggered (null if not paused)
 */
export function buildSnapshot(
  schedule: TimerScheduleEntry[],
  overrides: ReadonlyMap<number, number>,
  clockOffsetSeconds: number,
  transportState: TransportState,
  pausedAt: number | null
): CountdownSnapshot {
  // When PAUSED, use the frozen time at which pause was triggered
  const currentNow = transportState === 'PAUSED' && pausedAt !== null
    ? pausedAt
    : nowSeconds(clockOffsetSeconds);

  const { activeEventIndex, secondsRemaining, playingIndex } =
    resolveActiveEvent(schedule, overrides, clockOffsetSeconds, currentNow);

  const { currentRound, totalRounds } = computeRoundCounter(schedule, playingIndex);
  const doneIndices = computeDoneIndices(schedule, overrides, clockOffsetSeconds, currentNow, playingIndex);

  const totalSecs = Math.max(0, Math.round(secondsRemaining));
  const mm = String(Math.floor(totalSecs / 60)).padStart(2, '0');
  const ss = String(totalSecs % 60).padStart(2, '0');
  const countdownDisplay = activeEventIndex === -1 ? '--:--' : `${mm}:${ss}`;

  return {
    transportState,
    activeEventIndex,
    secondsRemaining,
    countdownDisplay,
    currentRound,
    totalRounds,
    doneIndices,
    playingIndex,
  };
}
