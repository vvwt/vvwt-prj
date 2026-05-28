<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Inline per-phase config editor (E11S12 AC5).
   *
   * Renders a config strip above the first ROUND row of each phase, exposing
   * lapTimeMinutes, lapBreakTimeMinutes, and sectionBreakTimeMinutes as
   * inline-editable numeric inputs.
   *
   * Edits are ephemeral — no saveDraft call, no backend interaction (AC7).
   * On blur or Enter, fires onUpdate with the new values.
   */
  import { _ } from 'svelte-i18n';
  import type { EphemeralPhaseConfig } from '../lib/timelineRecompute.js';

  interface PhaseConfigRowProps {
    phaseNumber: number;
    lapTimeMinutes: number;
    lapBreakTimeMinutes: number;
    sectionBreakTimeMinutes: number;
    /** True for the last phase — sectionBreakTimeMinutes has no effect (AC5). */
    isLastPhase: boolean;
    onUpdate: (cfg: EphemeralPhaseConfig) => void;
  }

  let {
    phaseNumber,
    lapTimeMinutes,
    lapBreakTimeMinutes,
    sectionBreakTimeMinutes,
    isLastPhase,
    onUpdate,
  }: PhaseConfigRowProps = $props();

  // ── Local editable state ───────────────────────────────────────────────────

  let lapTimeInput = $state(String(lapTimeMinutes));
  let lapBreakInput = $state(String(lapBreakTimeMinutes));
  let sectionBreakInput = $state(String(sectionBreakTimeMinutes));

  let lapTimeError = $state('');
  let lapBreakError = $state('');
  let sectionBreakError = $state('');

  // ── Validation and commit ──────────────────────────────────────────────────

  function validateAndCommit(): void {
    const lapTime = parseFloat(lapTimeInput);
    const lapBreak = parseFloat(lapBreakInput);
    const sectionBreak = parseFloat(sectionBreakInput);

    lapTimeError = '';
    lapBreakError = '';
    sectionBreakError = '';

    let valid = true;

    if (isNaN(lapTime) || lapTime <= 0) {
      lapTimeError = $_('timer.phaseConfig.errorLapTime');
      valid = false;
    }
    if (isNaN(lapBreak) || lapBreak < 0) {
      lapBreakError = $_('timer.phaseConfig.errorNegative');
      valid = false;
    }
    if (!isLastPhase && (isNaN(sectionBreak) || sectionBreak < 0)) {
      sectionBreakError = $_('timer.phaseConfig.errorNegative');
      valid = false;
    }

    if (!valid) return;

    onUpdate({
      lapTimeMinutes: lapTime,
      lapBreakTimeMinutes: lapBreak,
      sectionBreakTimeMinutes: isLastPhase ? 0 : sectionBreak,
    });
  }

  function handleLapTimeBlur(): void { validateAndCommit(); }
  function handleLapBreakBlur(): void { validateAndCommit(); }
  function handleSectionBreakBlur(): void { validateAndCommit(); }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      validateAndCommit();
      (event.currentTarget as HTMLElement).blur();
    }
  }
</script>

<tr class="phase-config-row" aria-label="{$_('timer.phaseConfig.label')} {phaseNumber}">
  <td class="phase-config-row__cell" colspan="3">
    <div class="phase-config-row__fields">
      <!-- Lap time -->
      <div class="phase-config-row__field">
        <label class="phase-config-row__label" for="lap-time-{phaseNumber}">
          {$_('timer.phaseConfig.lapTime')}
        </label>
        <input
          id="lap-time-{phaseNumber}"
          class="phase-config-row__input"
          class:phase-config-row__input--error={lapTimeError !== ''}
          type="number"
          min="1"
          step="0.5"
          bind:value={lapTimeInput}
          onblur={handleLapTimeBlur}
          onkeydown={handleKeydown}
          aria-label="{$_('timer.phaseConfig.lapTime')} ({$_('timer.phaseConfig.minutes')})"
          aria-invalid={lapTimeError !== '' ? 'true' : undefined}
        />
        {#if lapTimeError}
          <span class="phase-config-row__error" role="alert">{lapTimeError}</span>
        {/if}
      </div>

      <!-- Lap break -->
      <div class="phase-config-row__field">
        <label class="phase-config-row__label" for="lap-break-{phaseNumber}">
          {$_('timer.phaseConfig.lapBreak')}
        </label>
        <input
          id="lap-break-{phaseNumber}"
          class="phase-config-row__input"
          class:phase-config-row__input--error={lapBreakError !== ''}
          type="number"
          min="0"
          step="0.5"
          bind:value={lapBreakInput}
          onblur={handleLapBreakBlur}
          onkeydown={handleKeydown}
          aria-label="{$_('timer.phaseConfig.lapBreak')} ({$_('timer.phaseConfig.minutes')})"
          aria-invalid={lapBreakError !== '' ? 'true' : undefined}
        />
        {#if lapBreakError}
          <span class="phase-config-row__error" role="alert">{lapBreakError}</span>
        {/if}
      </div>

      <!-- Section break (disabled for last phase) -->
      <div class="phase-config-row__field">
        <label
          class="phase-config-row__label"
          class:phase-config-row__label--disabled={isLastPhase}
          for="section-break-{phaseNumber}"
        >
          {$_('timer.phaseConfig.sectionBreak')}
        </label>
        <input
          id="section-break-{phaseNumber}"
          class="phase-config-row__input"
          class:phase-config-row__input--error={sectionBreakError !== ''}
          type="number"
          min="0"
          step="0.5"
          disabled={isLastPhase}
          title={isLastPhase ? $_('timer.phaseConfig.lastPhaseNoSectionBreak') : undefined}
          bind:value={sectionBreakInput}
          onblur={handleSectionBreakBlur}
          onkeydown={handleKeydown}
          aria-label="{$_('timer.phaseConfig.sectionBreak')} ({$_('timer.phaseConfig.minutes')})"
          aria-invalid={sectionBreakError !== '' ? 'true' : undefined}
        />
        {#if sectionBreakError}
          <span class="phase-config-row__error" role="alert">{sectionBreakError}</span>
        {/if}
      </div>
    </div>
  </td>
</tr>

<style>
  .phase-config-row {
    background: #eef2f7;
    border-bottom: 1px solid #d0d9e5;
  }

  .phase-config-row__cell {
    padding: 0.4rem 0.75rem;
  }

  .phase-config-row__fields {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
    align-items: flex-start;
  }

  .phase-config-row__field {
    display: flex;
    flex-direction: column;
    gap: 0.15rem;
    min-width: 7rem;
  }

  .phase-config-row__label {
    font-size: 0.72rem;
    color: #555;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.03em;
  }

  .phase-config-row__label--disabled {
    color: #999;
  }

  .phase-config-row__input {
    width: 6rem;
    padding: 0.2rem 0.4rem;
    font-size: 0.9rem;
    border: 1px solid #b0bec5;
    border-radius: 3px;
    font-variant-numeric: tabular-nums;
    background: #fff;
  }

  .phase-config-row__input:focus {
    outline: none;
    border-color: #2980b9;
    box-shadow: 0 0 0 2px rgba(41, 128, 185, 0.2);
  }

  .phase-config-row__input--error {
    border-color: #e53935;
  }

  .phase-config-row__input:disabled {
    background: #f5f5f5;
    color: #aaa;
    cursor: not-allowed;
  }

  .phase-config-row__error {
    font-size: 0.7rem;
    color: #c0392b;
  }
</style>
