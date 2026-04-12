<script lang="ts">
  /**
   * Match result correction view — Story E05S11 AC7–AC10, AC13.
   *
   * Route: /phases/:phaseId/matches/:matchId/correct
   *
   * Features:
   *   - Loads match detail from GET /api/matches/{matchId} on mount (AC7)
   *   - Shows all set results with "Edit" buttons (AC7)
   *   - Inline confirmation dialog before any correction (AC8)
   *   - Post-correction refresh with brief highlight (AC9)
   *   - "Add Set" section for entering the next unplayed set (AC3)
   *   - Validation error display from API (AC10)
   *   - All strings via $_() (AC13)
   */
  import { onMount } from 'svelte';
  import { _ } from 'svelte-i18n';
  import { apiFetch } from '../lib/api.js';

  // ── Types ─────────────────────────────────────────────────────────────────

  interface SetResultEntry {
    setIndex: number;
    team1Points: number;
    team2Points: number;
    setState: string;
  }

  interface MatchDetail {
    matchId: string;
    phaseId: string;
    tournamentId: string;
    team1Description: string;
    team2Description: string;
    refereeDescription: string;
    matchFormat: string;
    matchState: string;
    setLimit: number;
    setResults: SetResultEntry[];
  }

  // ── Props ─────────────────────────────────────────────────────────────────

  interface Props {
    params?: { phaseId?: string; matchId?: string };
  }
  let { params = {} }: Props = $props();
  const phaseId = $derived(params.phaseId ?? '');
  const matchId = $derived(params.matchId ?? '');

  // ── State ─────────────────────────────────────────────────────────────────

  let matchDetail = $state<MatchDetail | null>(null);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  /** Index of the set currently being edited (-1 = none). */
  let editingSetIndex = $state(-1);
  /** Draft scores while editing a set. */
  let editTeam1 = $state(0);
  let editTeam2 = $state(0);

  /** New set scores for "Add Set" section. */
  let newSetTeam1 = $state(0);
  let newSetTeam2 = $state(0);

  /**
   * Confirmation dialog state.
   * pendingAction: 'correct' | 'enter' | null
   */
  let pendingAction = $state<'correct' | 'enter' | null>(null);
  let pendingSetIndex = $state(0);
  let pendingTeam1 = $state(0);
  let pendingTeam2 = $state(0);
  let pendingOldTeam1 = $state<number | null>(null);
  let pendingOldTeam2 = $state<number | null>(null);

  let submitting = $state(false);
  let submitError = $state<string | null>(null);

  /** Brief highlight flag after successful update (AC9). */
  let updated = $state(false);

  // ── Data loading ──────────────────────────────────────────────────────────

  async function loadMatchDetail(): Promise<void> {
    if (!matchId) return;
    loading = true;
    loadError = null;
    try {
      const resp = await apiFetch(`/api/matches/${matchId}`);
      if (resp.ok) {
        matchDetail = await resp.json();
      } else if (resp.status === 404) {
        loadError = $_('correction.notFound');
      } else {
        loadError = $_('correction.loadError');
      }
    } catch {
      loadError = $_('correction.loadError');
    } finally {
      loading = false;
    }
  }

  // ── Edit flow ─────────────────────────────────────────────────────────────

  /** Start editing a set: open the inline edit form for the given set index. */
  function startEdit(set: SetResultEntry): void {
    editingSetIndex = set.setIndex;
    editTeam1 = set.team1Points;
    editTeam2 = set.team2Points;
    submitError = null;
  }

  function cancelEdit(): void {
    editingSetIndex = -1;
    submitError = null;
  }

  /** Request confirmation before submitting a set correction (AC8). */
  function requestCorrectConfirm(set: SetResultEntry): void {
    pendingAction = 'correct';
    pendingSetIndex = set.setIndex;
    pendingTeam1 = editTeam1;
    pendingTeam2 = editTeam2;
    pendingOldTeam1 = set.team1Points;
    pendingOldTeam2 = set.team2Points;
  }

  /** Request confirmation before entering a new set (AC8). */
  function requestEnterConfirm(): void {
    pendingAction = 'enter';
    pendingSetIndex = matchDetail?.setResults.length ?? 0;
    pendingTeam1 = newSetTeam1;
    pendingTeam2 = newSetTeam2;
    pendingOldTeam1 = null;
    pendingOldTeam2 = null;
  }

  function cancelPending(): void {
    pendingAction = null;
  }

  /** Execute the confirmed action after dialog OK (AC8 → submit). */
  async function confirmAndSubmit(): Promise<void> {
    if (!pendingAction) return;
    pendingAction = null;
    submitting = true;
    submitError = null;

    try {
      let resp: Response;
      if (pendingAction === null && editingSetIndex >= 0) {
        // This branch is unreachable after the above pendingAction = null assignment;
        // the actual action is encoded in the local copies of pending* variables.
        // We re-read the original pendingAction from the snapshot before clearing.
        // Refactored below to avoid this confusion.
      }

      // pendingAction was already set to null above; use a local capture.
      resp = await executeConfirmedAction();

      if (resp.ok) {
        matchDetail = await resp.json();
        editingSetIndex = -1;
        newSetTeam1 = 0;
        newSetTeam2 = 0;
        triggerHighlight();
      } else {
        const errorBody = await resp.json().catch(() => null);
        submitError = errorBody?.message ?? $_('correction.submitError');
      }
    } catch {
      submitError = $_('correction.submitError');
    } finally {
      submitting = false;
    }
  }

  // Helper: capture pending state into local vars before pendingAction is cleared.
  let capturedAction: 'correct' | 'enter' | null = null;
  let capturedSetIndex = 0;
  let capturedTeam1 = 0;
  let capturedTeam2 = 0;

  function requestCorrectConfirmSafe(set: SetResultEntry): void {
    capturedAction = 'correct';
    capturedSetIndex = set.setIndex;
    capturedTeam1 = editTeam1;
    capturedTeam2 = editTeam2;
    pendingAction = 'correct';
    pendingSetIndex = set.setIndex;
    pendingTeam1 = editTeam1;
    pendingTeam2 = editTeam2;
    pendingOldTeam1 = set.team1Points;
    pendingOldTeam2 = set.team2Points;
  }

  function requestEnterConfirmSafe(): void {
    capturedAction = 'enter';
    capturedSetIndex = matchDetail?.setResults.length ?? 0;
    capturedTeam1 = newSetTeam1;
    capturedTeam2 = newSetTeam2;
    pendingAction = 'enter';
    pendingSetIndex = capturedSetIndex;
    pendingTeam1 = capturedTeam1;
    pendingTeam2 = capturedTeam2;
    pendingOldTeam1 = null;
    pendingOldTeam2 = null;
  }

  async function executeConfirmedAction(): Promise<Response> {
    if (capturedAction === 'correct') {
      return apiFetch(`/api/matches/${matchId}/sets/${capturedSetIndex}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ team1Points: capturedTeam1, team2Points: capturedTeam2 }),
      });
    } else {
      return apiFetch(`/api/matches/${matchId}/sets`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ team1Points: capturedTeam1, team2Points: capturedTeam2 }),
      });
    }
  }

  async function onConfirmDialog(): Promise<void> {
    pendingAction = null;
    submitting = true;
    submitError = null;

    try {
      const resp = await executeConfirmedAction();
      if (resp.ok) {
        matchDetail = await resp.json();
        editingSetIndex = -1;
        newSetTeam1 = 0;
        newSetTeam2 = 0;
        triggerHighlight();
      } else {
        const errorBody = await resp.json().catch(() => null);
        submitError = errorBody?.message ?? $_('correction.submitError');
      }
    } catch {
      submitError = $_('correction.submitError');
    } finally {
      submitting = false;
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  function triggerHighlight(): void {
    updated = true;
    setTimeout(() => { updated = false; }, 1500);
  }

  function isTerminal(state: string): boolean {
    return state.startsWith('FINISHED_') || state === 'CANCELED';
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  onMount(() => {
    loadMatchDetail();
  });
</script>

<div class="match-correction" class:updated={updated}>
  <!-- Header -->
  <div class="correction-header">
    <h2>{$_('correction.title')}</h2>
    <a href="#/phases/{phaseId}/monitoring" class="btn btn-secondary">
      ← {$_('correction.backToMonitoring')}
    </a>
  </div>

  {#if loading}
    <p>{$_('correction.loading')}</p>
  {:else if loadError}
    <p class="error">{loadError}</p>
  {:else if matchDetail}

    <!-- Match header info (AC7) -->
    <div class="match-info">
      <div class="match-teams">
        <span class="team team1">{matchDetail.team1Description}</span>
        <span class="vs">vs</span>
        <span class="team team2">{matchDetail.team2Description}</span>
      </div>
      <div class="match-meta">
        <span>{$_('correction.matchState')}: <strong>{matchDetail.matchState}</strong></span>
        <span>{$_('correction.matchFormat')}: <strong>{matchDetail.matchFormat}</strong></span>
        <span>{$_('correction.setLimit')}: <strong>{matchDetail.setLimit}</strong></span>
      </div>
    </div>

    <!-- Error from last submit -->
    {#if submitError}
      <div class="submit-error">{submitError}</div>
    {/if}

    <!-- Confirmation dialog (AC8) -->
    {#if pendingAction !== null}
      <div class="confirm-overlay">
        <div class="confirm-dialog">
          <h3>{$_('correction.confirmDialog.title')}</h3>
          <p>
            {#if pendingOldTeam1 !== null}
              {$_('correction.confirmDialog.changeMessage', {
                values: {
                  n: pendingSetIndex + 1,
                  old1: pendingOldTeam1,
                  old2: pendingOldTeam2,
                  new1: pendingTeam1,
                  new2: pendingTeam2
                }
              })}
            {:else}
              {$_('correction.confirmDialog.enterMessage', {
                values: { n: pendingSetIndex + 1, team1: pendingTeam1, team2: pendingTeam2 }
              })}
            {/if}
          </p>
          <p class="confirm-warning">{$_('correction.confirmDialog.warning')}</p>
          <div class="confirm-buttons">
            <button class="btn btn-danger" onclick={onConfirmDialog} disabled={submitting}>
              {submitting ? $_('correction.submitting') : $_('correction.confirmButton')}
            </button>
            <button class="btn btn-secondary" onclick={cancelPending} disabled={submitting}>
              {$_('correction.cancelButton')}
            </button>
          </div>
        </div>
      </div>
    {/if}

    <!-- Set results list (AC7) -->
    <section class="sets-section">
      <h3>{$_('correction.setsTitle')}</h3>

      {#if matchDetail.setResults.length === 0}
        <p>{$_('correction.noSets')}</p>
      {:else}
        <table class="sets-table">
          <thead>
            <tr>
              <th>{$_('correction.setColumn')}</th>
              <th class="num">{$_('correction.team1Column')}: {matchDetail.team1Description}</th>
              <th class="num">{$_('correction.team2Column')}: {matchDetail.team2Description}</th>
              <th>{$_('correction.stateColumn')}</th>
              <th>{$_('correction.actionsColumn')}</th>
            </tr>
          </thead>
          <tbody>
            {#each matchDetail.setResults as set (set.setIndex)}
              <tr class:editing-row={editingSetIndex === set.setIndex}>
                <td>{$_('correction.setLabel', { values: { n: set.setIndex + 1 } })}</td>
                {#if editingSetIndex === set.setIndex}
                  <!-- Inline edit form -->
                  <td class="num">
                    <input
                      type="number"
                      min="0"
                      class="score-input"
                      bind:value={editTeam1}
                      aria-label="{$_('correction.team1Column')}"
                    />
                  </td>
                  <td class="num">
                    <input
                      type="number"
                      min="0"
                      class="score-input"
                      bind:value={editTeam2}
                      aria-label="{$_('correction.team2Column')}"
                    />
                  </td>
                  <td>{set.setState}</td>
                  <td class="action-cell">
                    <button
                      class="btn btn-primary btn-sm"
                      onclick={() => requestCorrectConfirmSafe(set)}
                      disabled={submitting || pendingAction !== null}
                    >
                      {$_('correction.saveButton')}
                    </button>
                    <button
                      class="btn btn-secondary btn-sm"
                      onclick={cancelEdit}
                      disabled={submitting}
                    >
                      {$_('correction.cancelButton')}
                    </button>
                  </td>
                {:else}
                  <!-- Display row -->
                  <td class="num score" class:changed={updated}>{set.team1Points}</td>
                  <td class="num score" class:changed={updated}>{set.team2Points}</td>
                  <td>{set.setState}</td>
                  <td class="action-cell">
                    <button
                      class="btn btn-secondary btn-sm"
                      onclick={() => startEdit(set)}
                      disabled={submitting || editingSetIndex >= 0 || pendingAction !== null}
                    >
                      {$_('correction.editButton')}
                    </button>
                  </td>
                {/if}
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </section>

    <!-- Add new set (AC3) -->
    {#if !isTerminal(matchDetail.matchState) && matchDetail.setResults.length < matchDetail.setLimit}
      <section class="new-set-section">
        <h3>{$_('correction.addSetTitle')}</h3>
        <div class="add-set-form">
          <label class="score-label">
            {matchDetail.team1Description}:
            <input
              type="number"
              min="0"
              class="score-input"
              bind:value={newSetTeam1}
              aria-label="{matchDetail.team1Description} {$_('correction.score')}"
            />
          </label>
          <label class="score-label">
            {matchDetail.team2Description}:
            <input
              type="number"
              min="0"
              class="score-input"
              bind:value={newSetTeam2}
              aria-label="{matchDetail.team2Description} {$_('correction.score')}"
            />
          </label>
          <button
            class="btn btn-primary"
            onclick={requestEnterConfirmSafe}
            disabled={submitting || editingSetIndex >= 0 || pendingAction !== null}
          >
            {$_('correction.addSetButton')}
          </button>
        </div>
      </section>
    {/if}

  {/if}
</div>

<style>
  .match-correction { max-width: 900px; margin: 0 auto; padding: 1rem; }

  /* AC9: highlight pulse on successful update */
  @keyframes highlightPulse {
    0%   { background-color: transparent; }
    30%  { background-color: #fffde7; }
    100% { background-color: transparent; }
  }
  .match-correction.updated { animation: highlightPulse 1.5s ease; }
  td.score.changed { animation: highlightPulse 1.5s ease; }

  /* Header */
  .correction-header {
    display: flex; align-items: center; justify-content: space-between;
    margin-bottom: 1.25rem;
  }
  .correction-header h2 { margin: 0; }

  /* Match info */
  .match-info {
    background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 6px;
    padding: 0.75rem 1rem; margin-bottom: 1.25rem;
  }
  .match-teams {
    display: flex; align-items: center; gap: 0.5rem; font-size: 1.05rem; font-weight: 600;
    margin-bottom: 0.5rem;
  }
  .team { flex: 1; }
  .team1 { text-align: left; }
  .team2 { text-align: right; }
  .vs { color: #888; font-size: 0.85rem; flex-shrink: 0; }
  .match-meta { display: flex; gap: 1.5rem; font-size: 0.875rem; color: #555; flex-wrap: wrap; }

  /* Submit error */
  .submit-error {
    background: #f8d7da; color: #721c24; border: 1px solid #f5c6cb;
    padding: 0.5rem 0.75rem; border-radius: 4px; margin-bottom: 1rem;
  }

  /* Confirmation dialog (AC8) */
  .confirm-overlay {
    position: fixed; inset: 0; background: rgba(0,0,0,0.45);
    display: flex; align-items: center; justify-content: center; z-index: 100;
  }
  .confirm-dialog {
    background: #fff; border-radius: 8px; padding: 1.5rem 2rem;
    max-width: 460px; width: 90%; box-shadow: 0 4px 24px rgba(0,0,0,0.2);
  }
  .confirm-dialog h3 { margin-top: 0; }
  .confirm-warning { color: #856404; font-size: 0.875rem; margin-bottom: 1rem; }
  .confirm-buttons { display: flex; gap: 0.75rem; justify-content: flex-end; }

  /* Sets table */
  .sets-section { margin-bottom: 2rem; }
  .sets-section h3 { margin-bottom: 0.75rem; }
  .sets-table { border-collapse: collapse; width: 100%; font-size: 0.875rem; }
  .sets-table th, .sets-table td {
    border: 1px solid #dee2e6; padding: 0.4rem 0.6rem; text-align: left;
  }
  .sets-table th { background: #f8f9fa; font-weight: 600; }
  .sets-table th.num, .sets-table td.num { text-align: right; }
  .editing-row { background: #fffbf0; }
  .action-cell { white-space: nowrap; }
  .score-input {
    width: 4rem; padding: 0.2rem 0.4rem; border: 1px solid #ced4da;
    border-radius: 3px; font-size: 0.875rem; text-align: right;
  }

  /* New set section */
  .new-set-section { margin-bottom: 2rem; }
  .new-set-section h3 { margin-bottom: 0.75rem; }
  .add-set-form {
    display: flex; align-items: flex-end; gap: 1rem; flex-wrap: wrap;
  }
  .score-label {
    display: flex; flex-direction: column; gap: 0.3rem;
    font-size: 0.875rem; font-weight: 600;
  }

  /* Buttons */
  .btn {
    padding: 0.35rem 0.8rem; border: none; border-radius: 4px;
    cursor: pointer; font-size: 0.875rem;
  }
  .btn-sm { padding: 0.2rem 0.5rem; font-size: 0.8rem; }
  .btn:disabled { opacity: 0.5; cursor: not-allowed; }
  .btn-primary { background: #0d6efd; color: #fff; }
  .btn-danger { background: #dc3545; color: #fff; }
  .btn-secondary { background: #6c757d; color: #fff; }

  .error { color: #721c24; }
</style>
