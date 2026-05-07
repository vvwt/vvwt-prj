<script lang="ts">
  /**
   * Phase overview list for a tournament — Story E48S05 + E48S06 + E48S17 + E48S19.
   *
   * Displays all phases of a tournament with their:
   *   - sequenceNumber, description, status badge, gameMode, currentLapNumber, match counts
   *   - Status-conditional lifecycle buttons (E48S06, E48S17):
   *       PENDING:   "Vorbereiten" (→ PREPARED, E48S17)
   *       PREPARED:  "Phase starten" (→ ACTIVE, E48S17 refactor)
   *       ACTIVE:    "Phase abschließen" (disabled when unfinished matches > 0, with tooltip)
   *                  "Notabschluss" (with confirmation, visible only in ACTIVE)
   *
   * Navigation entry point: Tournaments.svelte "Phasen" button (AC-FRONTEND-NAV-FROM-TOURNAMENTS).
   * E47 shell mechanism (AC-FRONTEND-E47-HEADER-INTEGRATION): registers title + back-button via
   * pageHeader store; no per-page __header block.
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { push } from 'svelte-spa-router';
  import {
    listPhases,
    startPhase,
    completePhase,
    forceCompletePhase,
    type PhaseOverview,
  } from '../stores/phaseStore.js';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';

  // ── Props ─────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── State ─────────────────────────────────────────────────────
  let phases = $state<PhaseOverview[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let actionError = $state<string | null>(null);
  let actionInProgress = $state<string | null>(null); // phaseId of in-flight action

  // ── Lifecycle ─────────────────────────────────────────────────
  onMount(async () => {
    // AC-FRONTEND-E47-HEADER-INTEGRATION: register title + back-button via E47 shell
    pageHeader.set({
      title: get(_)('phases.pageTitle'),
      backTo: resolveParent('/tournaments/:tournamentId/phases', tournamentId),
      tournamentId: tournamentId || null,
      actions: [],
    });
    await loadPhases();
  });

  onDestroy(() => {
    resetPageHeader();
  });

  async function loadPhases(): Promise<void> {
    loading = true;
    loadError = null;
    try {
      phases = await listPhases(tournamentId);
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  /** Returns the CSS class for a phase status badge (AC-FRONTEND-STATUS-BADGE). */
  function statusBadgeClass(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'badge badge--active';
      case 'COMPLETED':
        return 'badge badge--completed';
      case 'PREPARED':
        return 'badge badge--prepared';
      case 'PENDING':
      default:
        return 'badge badge--pending';
    }
  }

  /** Formats a gameMode string for display. Returns "—" when null/absent (AC-PHASE-LIST-DEFENSIVE). */
  function formatGameMode(gameMode: string | null | undefined): string {
    if (!gameMode) return '—';
    return gameMode;
  }

  /** Counts finished matches across all finish states. */
  function finishedCount(phase: PhaseOverview): number {
    const s = phase.matchCountsByState;
    return (s.FINISHED_WINNER1 ?? 0) + (s.FINISHED_WINNER2 ?? 0) + (s.FINISHED_STANDOFF ?? 0);
  }

  /** Counts total matches for a phase (all states except CANCELED). */
  function totalCount(phase: PhaseOverview): number {
    const s = phase.matchCountsByState;
    return (s.OPEN ?? 0) + (s.ENABLED ?? 0) + (s.INPROGRESS ?? 0) + (s.ONCHECK ?? 0)
        + finishedCount(phase);
  }

  /**
   * AC-FRONTEND-COMPLETE-DISABLED-LOGIC: counts unfinished matches for a phase.
   * Unfinished = OPEN + ENABLED + INPROGRESS + ONCHECK.
   */
  function unfinishedCount(phase: PhaseOverview): number {
    const s = phase.matchCountsByState;
    return (s.OPEN ?? 0) + (s.ENABLED ?? 0) + (s.INPROGRESS ?? 0) + (s.ONCHECK ?? 0);
  }

  // ── Action handlers ───────────────────────────────────────────

  /**
   * E48S19: Navigates to the PhasePreparation Drag&Drop UI (PENDING → Vorbereiten-Route).
   *
   * Fixes the E48S17 bug where handlePrepare called preparePhase() API directly,
   * bypassing the PhasePreparation.svelte route delivered by E48S18.
   * Now follows AC-FRONTEND-PHASE-OVERVIEW-BUTTONS-EXTENDED: PENDING button navigates to
   * /tournaments/:id/phases/:phaseId/prepare (no direct API call here).
   *
   * AC-ERROR-HANDLING-INVALID-NAVIGATION-CONTEXT: guards against falsy tournamentId or phaseId.
   */
  function handlePrepare(phaseId: string): void {
    if (!tournamentId || !phaseId) {
      actionError = get(_)('phases.lifecycleError');
      return;
    }
    push(`/tournaments/${tournamentId}/phases/${phaseId}/prepare`);
  }

  async function handleStart(phaseId: string): Promise<void> {
    actionInProgress = phaseId;
    actionError = null;
    try {
      await startPhase(phaseId);
      await loadPhases();
    } catch (e: unknown) {
      actionError = e instanceof Error ? e.message : get(_)('phases.lifecycleError');
    } finally {
      actionInProgress = null;
    }
  }

  async function handleComplete(phaseId: string): Promise<void> {
    actionInProgress = phaseId;
    actionError = null;
    try {
      await completePhase(phaseId);
      await loadPhases();
      // AC-FRONTEND-ROUTING-HOOK: after success, navigate to transition route for next phase
      // (unless this was the last phase — then stay on phases overview).
      navigateAfterComplete(phaseId);
    } catch (e: unknown) {
      actionError = e instanceof Error ? e.message : get(_)('phases.lifecycleError');
    } finally {
      actionInProgress = null;
    }
  }

  async function handleForceComplete(phase: PhaseOverview): Promise<void> {
    const unfinished = unfinishedCount(phase);
    const confirmMsg = get(_)('phases.forceCompleteConfirm').replace(
      '{count}',
      String(unfinished)
    );
    if (!window.confirm(confirmMsg)) return;

    actionInProgress = phase.id;
    actionError = null;
    try {
      await forceCompletePhase(phase.id);
      await loadPhases();
      // AC-FRONTEND-ROUTING-HOOK: same logic as handleComplete
      navigateAfterComplete(phase.id);
    } catch (e: unknown) {
      actionError = e instanceof Error ? e.message : get(_)('phases.lifecycleError');
    } finally {
      actionInProgress = null;
    }
  }

  /**
   * AC-FRONTEND-ROUTING-HOOK: After a successful phase completion, navigate to the
   * drag-and-drop transition route for the NEXT phase (sequenceNumber + 1).
   * If the completed phase was the last phase, navigate to the phases overview.
   *
   * Implementation note: this runs AFTER loadPhases() so `phases` is refreshed.
   * The completed phase is looked up by phaseId; the next phase is found by sequenceNumber.
   */
  function navigateAfterComplete(completedPhaseId: string): void {
    const completedPhase = phases.find(p => p.id === completedPhaseId);
    if (!completedPhase) return;

    const nextPhase = phases.find(
      p => p.sequenceNumber === completedPhase.sequenceNumber + 1
    );

    if (nextPhase) {
      // Non-last phase: navigate to drag-and-drop transition for next phase
      push(`/tournaments/${tournamentId}/phases/${nextPhase.id}/transition`);
    } else {
      // Last phase: navigate to phases overview (Tournament awaits manual ACTIVE→COMPLETED)
      push(`/tournaments/${tournamentId}/phases`);
    }
  }
</script>

<main class="phases">

  {#if loading}
    <p class="phases__loading">…</p>
  {:else if loadError}
    <p class="phases__error">{loadError}</p>
  {:else if phases.length === 0}
    <p class="phases__empty">{$_('phases.empty')}</p>
  {:else}
    {#if actionError}
      <div class="phases__action-error">{actionError}</div>
    {/if}
    <table class="phases__table">
      <thead>
        <tr>
          <th>{$_('phases.columns.sequenceNumber')}</th>
          <th>{$_('phases.columns.description')}</th>
          <th>{$_('phases.columns.status')}</th>
          <th>{$_('phases.columns.gameMode')}</th>
          <th>{$_('phases.columns.currentLap')}</th>
          <th>{$_('phases.columns.matches')}</th>
          <th>{$_('phases.columns.actions')}</th>
        </tr>
      </thead>
      <tbody>
        {#each phases as phase (phase.id)}
          <tr>
            <td>{phase.sequenceNumber}</td>
            <td>{phase.description}</td>
            <td>
              <span class={statusBadgeClass(phase.status)}>
                {$_(`phases.status.${phase.status}`, { default: phase.status })}
              </span>
            </td>
            <td>{formatGameMode(phase.gameMode)}</td>
            <td>{phase.currentLapNumber}</td>
            <td>{finishedCount(phase)}&thinsp;/&thinsp;{totalCount(phase)}</td>
            <td class="phases__actions">
              {#if phase.status === 'PENDING'}
                <!-- E48S19: PENDING → "Vorbereiten" (navigates to PhasePreparation.svelte route, AC-FRONTEND-PHASE-OVERVIEW-BUTTONS-EXTENDED) -->
                <button
                  class="btn btn--primary"
                  disabled={actionInProgress === phase.id}
                  onclick={() => handlePrepare(phase.id)}
                >
                  {$_('phases.prepareButton')}
                </button>
              {:else if phase.status === 'PREPARED'}
                <!-- E48S17: PREPARED → "Phase starten" (calls start endpoint) -->
                <button
                  class="btn btn--primary"
                  disabled={actionInProgress === phase.id}
                  onclick={() => handleStart(phase.id)}
                >
                  {$_('phases.startButton')}
                </button>
              {:else if phase.status === 'ACTIVE'}
                <!-- AC-FRONTEND-PHASE-LIFECYCLE-BUTTONS: ACTIVE → "Phase abschließen" (disabled if unfinished) -->
                <!-- AC-FRONTEND-COMPLETE-DISABLED-LOGIC: disabled when unfinished > 0 -->
                <button
                  class="btn btn--secondary"
                  disabled={actionInProgress === phase.id || unfinishedCount(phase) > 0}
                  title={unfinishedCount(phase) > 0
                    ? $_('phases.completeDisabledTooltip')
                    : undefined}
                  onclick={() => handleComplete(phase.id)}
                >
                  {$_('phases.completeButton')}
                </button>
                <!-- AC-FRONTEND-PHASE-LIFECYCLE-BUTTONS: ACTIVE → "Notabschluss" (with confirmation) -->
                <!-- AC-FRONTEND-FORCE-COMPLETE-CONFIRMATION: opens confirmation dialog -->
                <button
                  class="btn btn--danger"
                  disabled={actionInProgress === phase.id}
                  onclick={() => handleForceComplete(phase)}
                >
                  {$_('phases.forceCompleteButton')}
                </button>
              {/if}
              <!-- COMPLETED: no buttons per AC-FRONTEND-PHASE-LIFECYCLE-BUTTONS -->
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</main>

<style>
  .phases {
    padding: 2rem;
    font-family: sans-serif;
  }

  .phases__table {
    width: 100%;
    border-collapse: collapse;
  }

  .phases__table th,
  .phases__table td {
    text-align: left;
    padding: 0.5rem 0.75rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .phases__table th {
    font-weight: 600;
    background: #f5f5f5;
  }

  .phases__error {
    color: #c0392b;
    margin-bottom: 1rem;
  }

  .phases__action-error {
    color: #c0392b;
    background: #fdecea;
    border: 1px solid #e74c3c;
    border-radius: 4px;
    padding: 0.5rem 1rem;
    margin-bottom: 1rem;
  }

  .phases__loading,
  .phases__empty {
    color: #666;
  }

  .phases__actions {
    white-space: nowrap;
  }

  /* AC-FRONTEND-STATUS-BADGE: color-coding per Brief Q-3 */
  .badge {
    display: inline-block;
    border-radius: 4px;
    padding: 0.15rem 0.5rem;
    font-size: 0.8rem;
    font-weight: 600;
  }

  .badge--pending {
    background: #ecf0f1;
    color: #555;
  }

  /* E48S17: PREPARED status — amber/yellow tone to distinguish from PENDING and ACTIVE */
  .badge--prepared {
    background: #fef9e7;
    color: #9a7d0a;
    border: 1px solid #f9e79f;
  }

  .badge--active {
    background: #d5f5e3;
    color: #1e8449;
  }

  .badge--completed {
    background: #d6eaf8;
    color: #1a5276;
  }

  /* Lifecycle buttons */
  .btn {
    display: inline-block;
    border: none;
    border-radius: 4px;
    padding: 0.3rem 0.75rem;
    font-size: 0.85rem;
    cursor: pointer;
    margin-right: 0.25rem;
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
    background: #27ae60;
    color: #fff;
  }

  .btn--secondary:hover:not(:disabled) {
    background: #1e8449;
  }

  .btn--danger {
    background: #e74c3c;
    color: #fff;
  }

  .btn--danger:hover:not(:disabled) {
    background: #c0392b;
  }
</style>
