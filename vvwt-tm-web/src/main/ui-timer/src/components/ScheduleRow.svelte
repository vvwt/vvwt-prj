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
   * E11S12 additions:
   * - AC1: Inline play/pause/stop micro-controls for ROUND and REGULAR-BREAK rows.
   *   ADDITIONAL-BREAK rows do NOT render inline controls.
   * - AC2: Inline play icon colour saturation: bright green when isAudioActive=true,
   *   pale green otherwise. Class: inline-play--active.
   * - AC3: Inline play triggers handleSkipTo(entryIndex) → skip-to this row.
   * - AC6: ADDITIONAL BREAK rows expose duration and label inline edit inputs.
   */
  import { _ } from 'svelte-i18n';
  import { formatTimeSeconds, parseTimeToSeconds } from '../lib/timerApi.js';
  import type { TimerScheduleEntry } from '../lib/timerApi.js';
  import type { EphemeralBreakConfig } from '../lib/timelineRecompute.js';

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
    /**
     * E11S12 AC1/AC2: Inline transport controls — provided for ROUND and REGULAR-BREAK rows.
     * Absent / undefined → no inline controls rendered (ADDITIONAL-BREAK rows).
     */
    onInlinePlay?: (entryIndex: number) => void;
    onInlinePause?: () => void;
    onInlineStop?: () => void;
    /**
     * E11S12 AC2: True when audio playback is currently active for this row.
     * Drives the inline play icon colour saturation (bright green vs pale green).
     */
    isAudioActive?: boolean;
    /**
     * E11S12 AC6: Ephemeral break config override for ADDITIONAL BREAK rows.
     * null/undefined when no override is set.
     */
    ephemeralBreakConfig?: EphemeralBreakConfig | null;
    /**
     * E11S12 AC6: Called when the operator saves an inline break edit.
     */
    onBreakEdit?: (entryIndex: number, cfg: EphemeralBreakConfig) => void;
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
    onInlinePlay,
    onInlinePause,
    onInlineStop,
    isAudioActive = false,
    ephemeralBreakConfig = null,
    onBreakEdit,
  }: ScheduleRowProps = $props();

  // ── Computed: whether to show inline transport controls (AC1) ─────────────

  /**
   * AC1: Inline controls rendered for ROUND rows and REGULAR BREAK rows.
   * ADDITIONAL BREAK rows do NOT get inline controls.
   */
  const showInlineControls = $derived(
    onInlinePlay !== undefined &&
    (entry.type === 'ROUND' || (entry.type === 'BREAK' && entry.breakType === 'REGULAR'))
  );

  /**
   * AC6: Inline break edit rendered for ADDITIONAL BREAK rows.
   */
  const showBreakEdit = $derived(
    entry.type === 'BREAK' && entry.breakType === 'ADDITIONAL' && onBreakEdit !== undefined
  );

  // ── Time editing state ──────────────────────────────────────────────────────

  let editing = $state(false);
  let editInput = $state('');
  let editError = $state('');

  // ── AC6: Inline break edit state ──────────────────────────────────────────

  let breakDurationInput = $state(ephemeralBreakConfig ? String(ephemeralBreakConfig.durationMinutes) : '');
  let breakLabelInput = $state(ephemeralBreakConfig?.label ?? entry.label ?? '');
  let breakDurationError = $state('');

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
   * Human-readable label for the entry (uses ephemeral label for ADDITIONAL BREAK if overridden).
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
    // ADDITIONAL BREAK: use ephemeral label if set
    return (ephemeralBreakConfig?.label ?? entry.label) ?? $_('timer.schedule.breakAdditional');
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

  // ── Time editing handlers ──────────────────────────────────────────────────

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

  // ── AC6: Inline break edit handlers ───────────────────────────────────────

  function saveBreakEdit(): void {
    breakDurationError = '';
    const dur = parseFloat(breakDurationInput);
    if (isNaN(dur) || dur <= 0) {
      breakDurationError = $_('timer.phaseConfig.errorLapTime');
      return;
    }
    onBreakEdit?.(entryIndex, {
      durationMinutes: dur,
      label: breakLabelInput.trim() || undefined,
    });
  }

  function handleBreakKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      saveBreakEdit();
      (event.currentTarget as HTMLElement).blur();
    }
  }

  // ── AC1/AC3: Inline transport handlers ────────────────────────────────────

  function handleInlinePlayClick(): void {
    onInlinePlay?.(entryIndex);
  }

  function handleInlinePauseClick(): void {
    onInlinePause?.();
  }

  function handleInlineStopClick(): void {
    onInlineStop?.();
  }
</script>

<tr class="schedule-row {statusClass} {typeClass}" aria-current={status === 'playing' ? 'true' : undefined}>
  <!-- Entry label column -->
  <td class="schedule-row__label">
    <div class="schedule-row__label-content">
      <span>{entryLabel()}</span>

      <!-- E11S12 AC1: Inline transport controls (ROUND + REGULAR BREAK only) -->
      {#if showInlineControls}
        <div class="schedule-row__inline-controls" role="group" aria-label={$_('timer.transport.label')}>
          <!-- AC2: inline play icon — bright green when isAudioActive, pale green otherwise -->
          <button
            class="inline-ctrl inline-ctrl--play"
            class:inline-play--active={isAudioActive}
            onclick={handleInlinePlayClick}
            title={$_('timer.inline.play')}
            aria-label={$_('timer.inline.play')}
          >▶</button>
          <button
            class="inline-ctrl inline-ctrl--pause"
            onclick={handleInlinePauseClick}
            title={$_('timer.inline.pause')}
            aria-label={$_('timer.inline.pause')}
          >⏸</button>
          <button
            class="inline-ctrl inline-ctrl--stop"
            onclick={handleInlineStopClick}
            title={$_('timer.inline.stop')}
            aria-label={$_('timer.inline.stop')}
          >⏹</button>
        </div>
      {/if}

      <!-- E11S12 AC6: Inline break duration/label edit (ADDITIONAL BREAK only) -->
      {#if showBreakEdit}
        <div class="schedule-row__break-edit">
          <input
            class="schedule-row__break-input"
            class:schedule-row__break-input--error={breakDurationError !== ''}
            type="number"
            min="0.5"
            step="0.5"
            placeholder={$_('timer.inlineBreak.duration')}
            bind:value={breakDurationInput}
            onblur={saveBreakEdit}
            onkeydown={handleBreakKeydown}
            aria-label={$_('timer.inlineBreak.duration')}
            aria-invalid={breakDurationError !== '' ? 'true' : undefined}
          />
          <input
            class="schedule-row__break-label-input"
            type="text"
            placeholder={$_('timer.inlineBreak.label')}
            bind:value={breakLabelInput}
            onblur={saveBreakEdit}
            onkeydown={handleBreakKeydown}
            aria-label={$_('timer.inlineBreak.label')}
          />
          {#if breakDurationError}
            <span class="schedule-row__break-error" role="alert">{breakDurationError}</span>
          {/if}
        </div>
      {/if}
    </div>
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

  /* E11S12: label cell now wraps label text + inline controls */
  .schedule-row__label-content {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    flex-wrap: wrap;
  }

  /* E11S12 AC1: Inline transport controls */
  .schedule-row__inline-controls {
    display: flex;
    align-items: center;
    gap: 0.2rem;
    margin-left: 0.25rem;
  }

  .inline-ctrl {
    width: 1.4rem;
    height: 1.4rem;
    border: none;
    border-radius: 50%;
    cursor: pointer;
    font-size: 0.55rem;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: background 0.12s, opacity 0.12s;
    padding: 0;
    line-height: 1;
  }

  /* AC2: Inline play icon — pale green default, bright green when audio active */
  .inline-ctrl--play {
    /* Pale green (desaturated) — inactive */
    background: #a8d5a2;
    color: #fff;
  }

  .inline-ctrl--play.inline-play--active {
    /* Bright green — audio currently playing for this row */
    background: #27ae60;
    color: #fff;
  }

  .inline-ctrl--play:hover {
    background: #27ae60;
    opacity: 0.9;
  }

  .inline-ctrl--pause {
    background: #f0c060;
    color: #fff;
  }

  .inline-ctrl--pause:hover {
    background: #f39c12;
  }

  .inline-ctrl--stop {
    background: #e8a09a;
    color: #fff;
  }

  .inline-ctrl--stop:hover {
    background: #c0392b;
  }

  /* E11S12 AC6: Inline break edit controls */
  .schedule-row__break-edit {
    display: flex;
    align-items: center;
    gap: 0.25rem;
    flex-wrap: wrap;
    margin-left: 0.25rem;
  }

  .schedule-row__break-input {
    width: 4.5rem;
    font-size: 0.85rem;
    padding: 0.15rem 0.3rem;
    border: 1px solid #b0bec5;
    border-radius: 3px;
  }

  .schedule-row__break-input--error {
    border-color: #e53935;
  }

  .schedule-row__break-label-input {
    width: 8rem;
    font-size: 0.85rem;
    padding: 0.15rem 0.3rem;
    border: 1px solid #b0bec5;
    border-radius: 3px;
  }

  .schedule-row__break-error {
    font-size: 0.75rem;
    color: #c0392b;
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
