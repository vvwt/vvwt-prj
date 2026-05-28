<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * A single row in the timer schedule (E11S03 AC3, AC4).
   *
   * Renders one schedule entry (ROUND or BREAK) with:
   * - Entry label (phase/lap or break type)
   * - Trigger time (adjusted by clock offset, editable by the user)
   * - Status indicator (upcoming / playing / done)
   *
   * Time editing (AC4): tapping/clicking the time cell opens an inline HH:MM:SS input.
   * Confirming sets an override time for this entry. An asterisk (*) marks edited rows.
   *
   * AC4: "Edited times override the calculated time for that event."
   * AC4: "A visual indicator distinguishes edited from calculated times."
   */
  import { _ } from 'svelte-i18n';
  import { formatTimeSeconds, parseTimeToSeconds } from '../lib/timerApi.js';
  import type { TimerScheduleEntry } from '../lib/timerApi.js';

  interface ScheduleRowProps {
    entry: TimerScheduleEntry;
    /** Index of this entry in the schedule array (used as key for overrides). */
    entryIndex: number;
    /**
     * Clock offset in seconds (venue − device).
     * AC3/E11S09: no longer applied to the schedule display (startTime is Hallenuhr-domain);
     * retained in the props interface for backward compatibility — callers may still pass it.
     */
    clockOffsetSeconds: number;
    /** Whether the server response has wall-clock times. */
    hasStartTime: boolean;
    /** Override time in seconds-since-midnight, or null if not edited. */
    overrideTimeSeconds: number | null;
    /** Called when the user saves an edited time. */
    onTimeEdit: (entryIndex: number, newTimeSeconds: number) => void;
    /** Status of this entry. */
    status: 'upcoming' | 'playing' | 'done';
    /**
     * AC8/E11S09: Whether this entry is the earliest upcoming (next-to-fire) row.
     * Triggers a subtle visual accent distinguishing it from later upcoming rows.
     */
    isNext?: boolean;
  }

  let {
    entry,
    entryIndex,
    clockOffsetSeconds,
    hasStartTime,
    overrideTimeSeconds,
    onTimeEdit,
    status,
    isNext = false,
  }: ScheduleRowProps = $props();

  // ── Editing state ──────────────────────────────────────────────────────────

  let editing = $state(false);
  let editInput = $state('');
  let editError = $state('');

  // ── Derived display values ─────────────────────────────────────────────────

  /**
   * The displayed time string: override > server venue-clock time verbatim > placeholder.
   *
   * AC3/E11S09: schedule {@code startTime} is already in the Hallenuhr (venue) domain;
   * display it verbatim — do NOT apply the clock offset here. The offset is consumed
   * only by the countdown engine's {@code nowSeconds()} on the device side.
   */
  const displayTime = $derived((): string => {
    if (overrideTimeSeconds !== null) {
      return formatTimeSeconds(overrideTimeSeconds);
    }
    if (!hasStartTime || !entry.startTime) {
      return $_('timer.schedule.noTime');
    }
    // AC3: return Hallenuhr time verbatim (already in venue domain)
    return entry.startTime;
  });

  /**
   * True if this entry has an edited (override) time — shown as asterisk.
   */
  const isEdited = $derived(overrideTimeSeconds !== null);

  /**
   * Human-readable label for the entry.
   */
  const entryLabel = $derived((): string => {
    if (entry.type === 'ROUND') {
      const phase = entry.phaseNumber ?? 0;
      const lap = entry.lapNumber ?? 0;
      return `${$_('timer.schedule.phase')} ${phase} — ${$_('timer.schedule.roundLabel')} ${lap}`;
    }
    // BREAK
    if (entry.breakType === 'REGULAR') {
      return entry.label ?? $_('timer.schedule.breakRegular');
    }
    return entry.label ?? $_('timer.schedule.breakAdditional');
  });

  /**
   * CSS class modifier for the row status.
   * AC8/E11S09: adds schedule-row--next for the earliest upcoming row (isNext=true),
   * only when the row is not already playing or done.
   */
  const statusClass = $derived(
    status === 'playing' ? 'schedule-row--playing'
      : status === 'done' ? 'schedule-row--done'
        : isNext ? 'schedule-row--next'
          : ''
  );

  /**
   * CSS class modifier for the entry type.
   */
  const typeClass = $derived(entry.type === 'BREAK' ? 'schedule-row--break' : '');

  // ── Editing handlers ───────────────────────────────────────────────────────

  function startEdit(): void {
    // Pre-fill with the currently shown time.
    // AC3/E11S09: startTime is already Hallenuhr-domain — pre-fill verbatim (no offset).
    const shown = overrideTimeSeconds !== null
      ? formatTimeSeconds(overrideTimeSeconds)
      : (hasStartTime && entry.startTime)
        ? entry.startTime
        : '';
    editInput = shown;
    editError = '';
    editing = true;
  }

  function saveEdit(): void {
    editError = '';
    const seconds = parseTimeToSeconds(editInput.trim());
    if (seconds === null) {
      editError = $_('timer.clockSync.invalidTime');
      return;
    }
    editing = false;
    onTimeEdit(entryIndex, seconds);
  }

  function cancelEdit(): void {
    editing = false;
    editError = '';
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') saveEdit();
    if (event.key === 'Escape') cancelEdit();
  }
</script>

<tr class="schedule-row {statusClass} {typeClass}" aria-current={status === 'playing' ? 'true' : undefined}>
  <!-- Entry label column -->
  <td class="schedule-row__label">
    {entryLabel()}
  </td>

  <!-- Time column (editable) -->
  <td class="schedule-row__time">
    {#if editing}
      <div class="schedule-row__edit">
        <input
          class="schedule-row__time-input"
          type="text"
          inputmode="numeric"
          placeholder="HH:MM"
          maxlength="8"
          bind:value={editInput}
          onkeydown={handleKeydown}
          aria-label={$_('timer.schedule.editTimeHint')}
          aria-invalid={editError ? 'true' : undefined}
        />
        <button class="schedule-row__edit-save" onclick={saveEdit} aria-label="Speichern">✓</button>
        <button class="schedule-row__edit-cancel" onclick={cancelEdit} aria-label="Abbrechen">✕</button>
      </div>
      {#if editError}
        <span class="schedule-row__edit-error" role="alert">{editError}</span>
      {/if}
    {:else}
      <button
        class="schedule-row__time-btn"
        class:schedule-row__time-btn--edited={isEdited}
        onclick={startEdit}
        title={$_('timer.schedule.editTimeHint')}
        aria-label="{displayTime()} — {$_('timer.schedule.editTimeHint')}"
      >
        {displayTime()}
        {#if isEdited}
          <span class="schedule-row__edited-mark" aria-label="angepasst">
            {$_('timer.schedule.editedIndicator')}
          </span>
        {/if}
      </button>
    {/if}
  </td>

  <!-- Status column -->
  <td class="schedule-row__status">
    <span class="schedule-row__status-badge schedule-row__status-badge--{status}">
      {#if status === 'playing'}
        {$_('timer.schedule.statusPlaying')}
      {:else if status === 'done'}
        {$_('timer.schedule.statusDone')}
      {:else}
        {$_('timer.schedule.statusUpcoming')}
      {/if}
    </span>
  </td>
</tr>

<style>
  .schedule-row {
    border-bottom: 1px solid #e8ecf0;
    transition: background 0.15s;
  }

  .schedule-row:hover {
    background: #f7f9fb;
  }

  .schedule-row--playing {
    background: #eaf6ff;
    font-weight: 600;
  }

  .schedule-row--done {
    opacity: 0.5;
  }

  /* AC8/E11S09: subtle visual accent for the next-upcoming row (earliest future event).
     Less dominant than --playing (which uses a blue fill + bold); just a left border
     accent and a very slight tint to signal "this fires next". */
  .schedule-row--next {
    border-left: 3px solid #2980b9;
    background: #f5faff;
  }

  .schedule-row--break td {
    background: #fafafa;
    font-style: italic;
    color: #666;
  }

  .schedule-row__label,
  .schedule-row__time,
  .schedule-row__status {
    padding: 0.6rem 0.75rem;
    vertical-align: middle;
    font-size: 0.95rem;
  }

  .schedule-row__label {
    color: #333;
  }

  .schedule-row__time {
    white-space: nowrap;
    min-width: 6rem;
  }

  .schedule-row__time-btn {
    background: none;
    border: 1px solid transparent;
    padding: 0.25rem 0.5rem;
    border-radius: 3px;
    cursor: pointer;
    font-size: 0.95rem;
    font-variant-numeric: tabular-nums;
    color: #1a2332;
    transition: border-color 0.15s, background 0.15s;
  }

  .schedule-row__time-btn:hover {
    border-color: #2980b9;
    background: #eaf4fb;
  }

  .schedule-row__time-btn--edited {
    color: #2980b9;
    font-weight: 600;
  }

  .schedule-row__edited-mark {
    color: #e67e22;
    font-size: 0.8em;
    margin-left: 2px;
  }

  .schedule-row__edit {
    display: flex;
    align-items: center;
    gap: 0.25rem;
  }

  .schedule-row__time-input {
    width: 7rem;
    font-size: 0.95rem;
    padding: 0.25rem 0.4rem;
    border: 1.5px solid #2980b9;
    border-radius: 3px;
    font-variant-numeric: tabular-nums;
    outline: none;
  }

  .schedule-row__time-input:focus {
    box-shadow: 0 0 0 2px rgba(41, 128, 185, 0.25);
  }

  .schedule-row__edit-save,
  .schedule-row__edit-cancel {
    background: none;
    border: none;
    cursor: pointer;
    font-size: 1rem;
    padding: 0.1rem 0.3rem;
    border-radius: 3px;
  }

  .schedule-row__edit-save {
    color: #27ae60;
  }

  .schedule-row__edit-save:hover {
    background: #eafaf1;
  }

  .schedule-row__edit-cancel {
    color: #c0392b;
  }

  .schedule-row__edit-cancel:hover {
    background: #fdf2f2;
  }

  .schedule-row__edit-error {
    display: block;
    font-size: 0.8rem;
    color: #c0392b;
    margin-top: 0.2rem;
  }

  .schedule-row__status {
    text-align: center;
  }

  .schedule-row__status-badge {
    display: inline-block;
    padding: 0.2rem 0.5rem;
    border-radius: 12px;
    font-size: 0.8rem;
    font-weight: 500;
  }

  .schedule-row__status-badge--upcoming {
    background: #f0f3f7;
    color: #666;
  }

  .schedule-row__status-badge--playing {
    background: #2980b9;
    color: #fff;
  }

  .schedule-row__status-badge--done {
    background: #e8f5e9;
    color: #2e7d32;
  }
</style>
