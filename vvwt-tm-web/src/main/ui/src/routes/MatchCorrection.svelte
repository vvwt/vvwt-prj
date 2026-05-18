<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Match score correction form for operator use — Story E48S25.
   *
   * Route: /tournaments/:tournamentId/phases/:phaseId/matches/:matchId/correction
   *
   * Features (AC-TEST-SVELTE-FE-CORRECTION-FLOW):
   *   - Form with editable set rows (team1Points, team2Points per set)
   *   - Optional reason text field (Brief D-6)
   *   - Submit button → confirmation dialog showing pre-vs-post values (Brief Q-6)
   *   - After confirm → POST /api/matches/:matchId/correction → result state
   *   - i18n keys from de.json/en.json (correction.* namespace)
   *
   * Entry point: PhaseList.svelte "Korrigieren" link per match row (phase ACTIVE only).
   *
   * E47 shell: registers title + back-arrow via pageHeader store.
   *
   * E66S05: column headers in the score-input table and the confirmation dialog now
   * show "Nr. {teamNumber} — {teamName}" instead of the generic "Team 1 / Team 2"
   * labels, using team1Label / team2Label derived from the preloaded match data.
   * When no team is assigned (teamNumber is null), the label degrades gracefully to
   * the correction.teamFallback i18n key — never "undefined", never blank.
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { push } from 'svelte-spa-router';
  import {
    listPhaseMatches,
    submitMatchCorrection,
    type SetScoreEntry,
  } from '../stores/correctionStore.js';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';

  // ── Props ─────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string; phaseId?: string; matchId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');
  const phaseId = $derived(params.phaseId ?? '');
  const matchId = $derived(params.matchId ?? '');

  // ── State ─────────────────────────────────────────────────────

  /**
   * The set-score rows the operator is editing.
   * Pre-loaded from the phase match list on mount (E48S26 AC-TEST-CORRECTION-FORM-PRELOAD-RED).
   * Falls back to an empty array when the match has no recorded sets (Nacherfassung, OPEN/ENABLED).
   */
  let sets = $state<SetScoreEntry[]>([]);

  let reason = $state<string>('');
  let submitting = $state(false);
  let submitError = $state<string | null>(null);
  let resultState = $state<string | null>(null);
  let resultAuditOnly = $state<boolean | null>(null);

  /** Whether the pre-load is in progress (suppresses form rendering until data is ready). */
  let preloading = $state(true);
  /** Set when the match-list pre-load fails (network error or match-not-found). */
  let preloadError = $state<string | null>(null);

  /** Whether the confirmation dialog is visible (Brief Q-6 deliberate-action UX). */
  let showConfirm = $state(false);

  /**
   * Team labels for the two sides of the match (E66S05 AC2 / AC3).
   *
   * Format when team is assigned: "Nr. {teamNumber} — {teamName}"
   * Format when no team assigned (teamNumber is null): correction.teamFallback i18n value
   *
   * Populated from the preloaded match data; updated after each successful preload.
   * Never "undefined" — guaranteed to be a non-empty string.
   */
  let team1Label = $state<string>('');
  let team2Label = $state<string>('');

  // ── Lifecycle ─────────────────────────────────────────────────

  onMount(async () => {
    pageHeader.set({
      title: get(_)('correction.pageTitle'),
      backTo: resolveParent(
        '/tournaments/:tournamentId/phases/:phaseId/matches/:matchId/correction',
        tournamentId,
        phaseId
      ),
      tournamentId: tournamentId || null,
      actions: [],
    });
    await preloadSets();
  });

  onDestroy(() => {
    resetPageHeader();
  });

  // ── Pre-load ──────────────────────────────────────────────────

  /**
   * Pre-loads the match's existing set results from GET /api/phases/{phaseId}/matches.
   *
   * Uses listPhaseMatches(phaseId) (the canonical projection already consumed by
   * MatchOverview.svelte) and selects the entry whose matchId matches the route param.
   * No single-match endpoint is introduced (E48S26 Out-of-Scope).
   *
   * Outcomes:
   *   - Match found + sets present: pre-fills the form with recorded scores (editable default).
   *   - Match found + no sets: leaves form empty (fresh-entry / Nacherfassung path).
   *   - Match not found: sets preloadError (distinguishable from empty-sets state per AC-ERR-...).
   *   - Network error: sets preloadError.
   */
  async function preloadSets(): Promise<void> {
    preloading = true;
    preloadError = null;
    try {
      const allMatches = await listPhaseMatches(phaseId);
      const found = allMatches.find(m => m.matchId === matchId);
      if (!found) {
        preloadError = get(_)('correction.matchNotFound');
        return;
      }
      // Map SetScore → SetScoreEntry (field names are identical: setIndex, team1Points, team2Points)
      sets = found.setScores.map(s => ({
        setIndex: s.setIndex,
        team1Points: s.team1Points,
        team2Points: s.team2Points,
      }));
      // E66S05 AC2/AC3: resolve team labels from teamNumber + teamName.
      // Graceful fallback per AC4: when teamNumber is null (no team assigned),
      // show correction.teamFallback — never "undefined", never blank.
      team1Label = buildTeamLabel(found.team1Number, found.team1Name);
      team2Label = buildTeamLabel(found.team2Number, found.team2Name);
    } catch (e: unknown) {
      preloadError = e instanceof Error ? e.message : get(_)('correction.preloadError');
    } finally {
      preloading = false;
    }
  }

  /**
   * Builds the display label for one side of a match (E66S05 AC2/AC4).
   *
   * - When teamNumber is non-null: returns "Nr. {teamNumber} — {teamName}"
   *   using the correction.teamNumberPrefix i18n key for the "Nr." prefix.
   * - When teamNumber is null (no team assigned): returns correction.teamFallback.
   *
   * The teamId UUID is never included in the output (DEC-9).
   *
   * @param teamNumber the human-readable team number, or null if unassigned
   * @param teamName the team display name (e.g. "Alpha FC")
   */
  function buildTeamLabel(teamNumber: number | null, teamName: string): string {
    if (teamNumber === null) {
      return get(_)('correction.teamFallback', { default: '–' });
    }
    const prefix = get(_)('correction.teamNumberPrefix', { default: 'Nr.' });
    return `${prefix} ${teamNumber} — ${teamName}`;
  }

  // ── Helpers ───────────────────────────────────────────────────

  /** Add a new set row after the last one. */
  function addSetRow(): void {
    const nextIndex = sets.length;
    sets = [...sets, { setIndex: nextIndex, team1Points: 0, team2Points: 0 }];
  }

  /** Remove the last set row (minimum 1 row). */
  function removeLastSetRow(): void {
    if (sets.length > 1) {
      sets = sets.slice(0, -1);
    }
  }

  /** Update a set entry field reactively. */
  function updateSet(index: number, field: 'team1Points' | 'team2Points', value: string): void {
    const parsed = parseInt(value, 10);
    const points = isNaN(parsed) ? 0 : Math.max(0, parsed);
    sets = sets.map((s, i) => (i === index ? { ...s, [field]: points } : s));
  }

  // ── Confirmation dialog (Brief Q-6 deliberate-action UX) ─────

  /** Opens the confirmation dialog before submitting. */
  function handleSubmitRequest(): void {
    showConfirm = true;
  }

  /** User cancelled the confirmation dialog. */
  function handleConfirmCancel(): void {
    showConfirm = false;
  }

  /** User confirmed — proceed with submission. */
  async function handleConfirmSubmit(): Promise<void> {
    showConfirm = false;
    submitting = true;
    submitError = null;
    resultState = null;
    resultAuditOnly = null;
    try {
      const result = await submitMatchCorrection(matchId, {
        tournamentId,
        phaseId,
        sets,
        reason: reason || null,
      });
      resultState = result.newMatchState;
      resultAuditOnly = result.auditOnly;
    } catch (e: unknown) {
      submitError = e instanceof Error ? e.message : get(_)('correction.submitError');
    } finally {
      submitting = false;
    }
  }
</script>

<main class="correction">
  <h2 class="correction__subtitle">{$_('correction.matchIdLabel')}: {matchId}</h2>

  {#if preloading}
    <p class="correction__loading" data-testid="correction-loading">{$_('correction.loading')}</p>
  {:else if preloadError !== null}
    <p class="correction__error" role="alert" data-testid="match-not-found-error">{preloadError}</p>
  {:else if resultState !== null}
    <!-- Success state -->
    <div class="correction__result">
      <p class="correction__result-state">
        {$_('correction.successLabel')}: <strong>{resultState}</strong>
        {#if resultAuditOnly}
          — {$_('correction.auditOnlyLabel')}
        {/if}
      </p>
      <button
        class="btn btn--secondary"
        onclick={() => push(`/tournaments/${tournamentId}/phases`)}
      >
        {$_('correction.backToPhaseList')}
      </button>
    </div>
  {:else}
    <!-- Correction form -->
    <form
      class="correction__form"
      onsubmit={(e) => { e.preventDefault(); handleSubmitRequest(); }}
    >
      <!-- Set rows -->
      <table class="correction__sets-table" data-testid="correction-sets-table">
        <thead>
          <tr>
            <th>{$_('correction.setIndex')}</th>
            <th data-testid="team1-header">{team1Label}</th>
            <th data-testid="team2-header">{team2Label}</th>
          </tr>
        </thead>
        <tbody>
          {#each sets as set, i (set.setIndex)}
            <tr>
              <td>{i + 1}</td>
              <td>
                <input
                  class="correction__points-input"
                  type="number"
                  min="0"
                  value={set.team1Points}
                  aria-label="{team1Label} {$_('correction.setIndex')} {i + 1}"
                  oninput={(e) => updateSet(i, 'team1Points', (e.target as HTMLInputElement).value)}
                  data-testid="team1-points-{i}"
                />
              </td>
              <td>
                <input
                  class="correction__points-input"
                  type="number"
                  min="0"
                  value={set.team2Points}
                  aria-label="{team2Label} {$_('correction.setIndex')} {i + 1}"
                  oninput={(e) => updateSet(i, 'team2Points', (e.target as HTMLInputElement).value)}
                  data-testid="team2-points-{i}"
                />
              </td>
            </tr>
          {/each}
        </tbody>
      </table>

      <!-- Add/Remove set row controls -->
      <div class="correction__row-controls">
        <button type="button" class="btn btn--secondary" onclick={addSetRow}>
          {$_('correction.addSetRow')}
        </button>
        {#if sets.length > 1}
          <button type="button" class="btn btn--secondary" onclick={removeLastSetRow}>
            {$_('correction.removeSetRow')}
          </button>
        {/if}
      </div>

      <!-- Optional reason -->
      <div class="correction__reason">
        <label for="correction-reason">{$_('correction.reasonLabel')}</label>
        <input
          id="correction-reason"
          class="correction__reason-input"
          type="text"
          bind:value={reason}
          placeholder={$_('correction.reasonPlaceholder')}
          data-testid="correction-reason"
        />
      </div>

      {#if submitError}
        <div class="correction__error" role="alert">{submitError}</div>
      {/if}

      <div class="correction__actions">
        <button
          class="btn btn--primary"
          type="submit"
          disabled={submitting}
          data-testid="correction-submit-btn"
        >
          {submitting ? $_(  'correction.submittingButton') : $_('correction.submitButton')}
        </button>
      </div>
    </form>

    <!-- Confirmation dialog (Brief Q-6 deliberate-action UX) -->
    {#if showConfirm}
      <div class="correction__overlay" role="dialog" aria-modal="true" aria-label={$_('correction.confirmTitle')} data-testid="confirm-dialog">
        <div class="correction__dialog">
          <h3 class="correction__dialog-title">{$_('correction.confirmTitle')}</h3>
          <p class="correction__dialog-body">{$_('correction.confirmBody')}</p>
          <!-- Pre-vs-post value summary (Brief Q-6) -->
          <table class="correction__confirm-table">
            <thead>
              <tr>
                <th>{$_('correction.setIndex')}</th>
                <th data-testid="confirm-team1-header">{team1Label}</th>
                <th data-testid="confirm-team2-header">{team2Label}</th>
              </tr>
            </thead>
            <tbody>
              {#each sets as set, i (set.setIndex)}
                <tr>
                  <td>{i + 1}</td>
                  <td>{set.team1Points}</td>
                  <td>{set.team2Points}</td>
                </tr>
              {/each}
            </tbody>
          </table>
          {#if reason}
            <p class="correction__confirm-reason">
              {$_('correction.reasonLabel')}: {reason}
            </p>
          {/if}
          <div class="correction__dialog-actions">
            <button
              class="btn btn--primary"
              onclick={handleConfirmSubmit}
              data-testid="confirm-submit-btn"
            >
              {$_('correction.confirmSubmitButton')}
            </button>
            <button
              class="btn btn--secondary"
              onclick={handleConfirmCancel}
              data-testid="confirm-cancel-btn"
            >
              {$_('correction.confirmCancelButton')}
            </button>
          </div>
        </div>
      </div>
    {/if}
  {/if}
</main>

<style>
  .correction {
    padding: 2rem;
    font-family: sans-serif;
  }

  .correction__subtitle {
    font-size: 0.95rem;
    color: #555;
    margin-bottom: 1.5rem;
    font-weight: normal;
  }

  .correction__sets-table {
    border-collapse: collapse;
    margin-bottom: 1rem;
    min-width: 300px;
  }

  .correction__sets-table th,
  .correction__sets-table td {
    text-align: left;
    padding: 0.4rem 0.75rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .correction__sets-table th {
    font-weight: 600;
    background: #f5f5f5;
  }

  .correction__points-input {
    width: 80px;
    padding: 0.3rem 0.5rem;
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    font-size: 1rem;
  }

  .correction__row-controls {
    margin-bottom: 1rem;
    display: flex;
    gap: 0.5rem;
  }

  .correction__reason {
    margin-bottom: 1rem;
    display: flex;
    flex-direction: column;
    gap: 0.3rem;
    max-width: 400px;
  }

  .correction__reason label {
    font-weight: 500;
    font-size: 0.9rem;
  }

  .correction__reason-input {
    padding: 0.4rem 0.5rem;
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    font-size: 0.95rem;
  }

  .correction__actions {
    margin-top: 1rem;
  }

  .correction__error {
    color: #c0392b;
    background: #fdecea;
    border: 1px solid #e74c3c;
    border-radius: 4px;
    padding: 0.5rem 1rem;
    margin-bottom: 1rem;
  }

  .correction__result {
    background: #d5f5e3;
    border: 1px solid #27ae60;
    border-radius: 4px;
    padding: 1rem 1.5rem;
    margin-bottom: 1rem;
  }

  .correction__result-state {
    margin-bottom: 1rem;
  }

  /* Confirmation dialog overlay (Brief Q-6) */
  .correction__overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.4);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 200;
  }

  .correction__dialog {
    background: #fff;
    border-radius: 6px;
    padding: 1.5rem 2rem;
    min-width: 320px;
    max-width: 500px;
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  }

  .correction__dialog-title {
    margin-bottom: 0.5rem;
    font-size: 1.1rem;
  }

  .correction__dialog-body {
    color: #555;
    margin-bottom: 1rem;
    font-size: 0.95rem;
  }

  .correction__confirm-table {
    border-collapse: collapse;
    margin-bottom: 1rem;
    width: 100%;
  }

  .correction__confirm-table th,
  .correction__confirm-table td {
    text-align: left;
    padding: 0.3rem 0.5rem;
    border-bottom: 1px solid #e0e0e0;
    font-size: 0.9rem;
  }

  .correction__confirm-reason {
    font-size: 0.9rem;
    color: #555;
    margin-bottom: 1rem;
  }

  .correction__dialog-actions {
    display: flex;
    gap: 0.5rem;
    justify-content: flex-end;
  }

  /* Buttons */
  .btn {
    display: inline-block;
    border: none;
    border-radius: 4px;
    padding: 0.4rem 1rem;
    font-size: 0.9rem;
    cursor: pointer;
  }

  .btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .btn--primary:hover:not(:disabled) {
    background: #1a6a9a;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }

  .btn--secondary:hover:not(:disabled) {
    background: #d6dbdf;
  }
</style>
