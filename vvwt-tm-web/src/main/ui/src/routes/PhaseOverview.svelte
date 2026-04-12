<script lang="ts">
  /**
   * Phase overview and lifecycle management — Story E05S07 AC7–AC11, AC14.
   * Story E05S08 — adds "Map Teams" button for PENDING phases with sequenceNumber > 1.
   *
   * Receives the selected tournament ID via route parameter `params.tournamentId`.
   *
   * The organizer can:
   *   - View all phases with status badges, lap indicators, and match progress (AC7)
   *   - Trigger phase preparation (steps 1–3) and see step-by-step results (AC8)
   *   - View the generated match schedule as a grid (AC9)
   *   - Start a prepared phase (PENDING → ACTIVE) (AC4)
   *   - Manually advance the current lap, with force-override confirmation (AC5)
   *   - See real-time lap and match state updates via WebSocket (AC10)
   *   - See tournament completion state when all phases finish (AC11)
   *   - Navigate to team mapping view for Phase 2+ (E05S08)
   *
   * All visible strings use the svelte-i18n `$_()` function (AC14).
   */
  import { onMount, onDestroy } from 'svelte';
  import { push } from 'svelte-spa-router';
  import { _ } from 'svelte-i18n';
  import { apiFetch } from '../lib/api.js';
  import { Client } from '@stomp/stompjs';

  // ── Types ─────────────────────────────────────────────────────────────────

  interface MatchCounts {
    open: number;
    enabled: number;
    inProgress: number;
    finishedStandoff: number;
    finishedWinner1: number;
    finishedWinner2: number;
    canceled: number;
    total: number;
  }

  interface PhaseResponse {
    id: string;
    tournamentId: string;
    sequenceNumber: number;
    description: string;
    status: 'PENDING' | 'ACTIVE' | 'COMPLETED';
    currentLapNumber: number;
    totalLapCount: number;
    sortType: string;
    groupCount: number;
    matchCounts: MatchCounts;
  }

  interface PreparationStepResult {
    success: boolean;
    message: string;
  }

  interface PreparationResult {
    generateMatchesSuccess: boolean;
    generateMatchesMessage: string;
    optimizeSlotsSuccess: boolean;
    optimizeSlotsMessage: string;
    assignRefereesSuccess: boolean;
    assignRefereesMessage: string;
    success: boolean;
  }

  interface SetResultSummary {
    setIndex: number;
    team1Points: number;
    team2Points: number;
  }

  interface ScheduleMatch {
    matchId: string;
    fieldNumber: number | null;
    team1Description: string;
    team2Description: string;
    refereeDescription: string;
    matchState: string;
    setResults: SetResultSummary[];
  }

  interface ScheduleLap {
    lapNumber: number;
    matches: ScheduleMatch[];
  }

  interface PhaseScheduleResponse {
    phaseId: string;
    laps: ScheduleLap[];
  }

  // ── Props ─────────────────────────────────────────────────────────────────

  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── State ─────────────────────────────────────────────────────────────────

  let phases = $state<PhaseResponse[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let tournamentCompleted = $state(false);

  /** Per-phase preparation results — keyed by phaseId. */
  let preparationResults = $state<Record<string, PreparationResult | null>>({});
  let preparing = $state<Record<string, boolean>>({});
  let starting = $state<Record<string, boolean>>({});
  let advancingLap = $state<Record<string, boolean>>({});

  /** Per-phase schedule display state. */
  let schedules = $state<Record<string, PhaseScheduleResponse | null>>({});
  let scheduleLoading = $state<Record<string, boolean>>({});
  let showSchedule = $state<Record<string, boolean>>({});

  /** Force-advance lap confirmation state. */
  let forceAdvanceConfirm = $state<Record<string, boolean>>({});

  let actionError = $state<string | null>(null);

  // ── WebSocket ─────────────────────────────────────────────────────────────

  let stompClient: Client | null = null;

  function connectWebSocket(): void {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${wsProtocol}//${window.location.host}/ws`;

    stompClient = new Client({
      brokerURL: wsUrl,
      onConnect: () => {
        stompClient?.subscribe('/topic/events', () => {
          // AC10: on any domain event, refresh phases to get updated state
          if (tournamentId) {
            loadPhases();
          }
        });
      },
      onDisconnect: () => {
        // Reconnect is handled automatically by @stomp/stompjs
      },
    });
    stompClient.activate();
  }

  // ── Data loading ──────────────────────────────────────────────────────────

  async function loadPhases(): Promise<void> {
    if (!tournamentId) return;
    try {
      const resp = await apiFetch(`/api/tournaments/${tournamentId}/phases`);
      if (!resp.ok) {
        loadError = $_('phases.loadError');
        return;
      }
      const data: PhaseResponse[] = await resp.json();
      phases = data;

      // AC11: check tournament completion
      const allCompleted = data.length > 0 && data.every(p => p.status === 'COMPLETED');
      tournamentCompleted = allCompleted;

      loadError = null;
    } catch {
      loadError = $_('phases.loadError');
    } finally {
      loading = false;
    }
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  async function preparePhase(phaseId: string): Promise<void> {
    preparing = { ...preparing, [phaseId]: true };
    actionError = null;
    preparationResults = { ...preparationResults, [phaseId]: null };
    try {
      const resp = await apiFetch(`/api/phases/${phaseId}/prepare`, { method: 'POST' });
      const result: PreparationResult = await resp.json();
      preparationResults = { ...preparationResults, [phaseId]: result };
      if (result.success) {
        await loadPhases();
      }
    } catch {
      actionError = $_('phases.prepareError');
    } finally {
      preparing = { ...preparing, [phaseId]: false };
    }
  }

  async function startPhase(phaseId: string): Promise<void> {
    starting = { ...starting, [phaseId]: true };
    actionError = null;
    try {
      const resp = await apiFetch(`/api/phases/${phaseId}/start`, { method: 'POST' });
      if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        actionError = (err as any)?.message ?? $_('phases.startError');
      } else {
        await loadPhases();
      }
    } catch {
      actionError = $_('phases.startError');
    } finally {
      starting = { ...starting, [phaseId]: false };
    }
  }

  async function advanceLap(phaseId: string, force: boolean): Promise<void> {
    advancingLap = { ...advancingLap, [phaseId]: true };
    actionError = null;
    forceAdvanceConfirm = { ...forceAdvanceConfirm, [phaseId]: false };
    try {
      const resp = await apiFetch(`/api/phases/${phaseId}/advance-lap`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ force }),
      });
      if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        if (resp.status === 409 && !force) {
          // Unfinished matches — prompt for force confirm
          forceAdvanceConfirm = { ...forceAdvanceConfirm, [phaseId]: true };
        } else {
          actionError = (err as any)?.message ?? $_('phases.advanceLapError');
        }
      } else {
        await loadPhases();
      }
    } catch {
      actionError = $_('phases.advanceLapError');
    } finally {
      advancingLap = { ...advancingLap, [phaseId]: false };
    }
  }

  async function loadSchedule(phaseId: string): Promise<void> {
    scheduleLoading = { ...scheduleLoading, [phaseId]: true };
    try {
      const resp = await apiFetch(`/api/phases/${phaseId}/schedule`);
      if (resp.ok) {
        schedules = { ...schedules, [phaseId]: await resp.json() };
      }
    } catch {
      // Ignore — schedule not critical if it fails to load
    } finally {
      scheduleLoading = { ...scheduleLoading, [phaseId]: false };
    }
  }

  function toggleSchedule(phaseId: string): void {
    const showing = showSchedule[phaseId];
    showSchedule = { ...showSchedule, [phaseId]: !showing };
    if (!showing && !schedules[phaseId]) {
      loadSchedule(phaseId);
    }
  }

  function finishedMatchCount(counts: MatchCounts): number {
    return counts.finishedStandoff + counts.finishedWinner1 + counts.finishedWinner2 + counts.canceled;
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  onMount(() => {
    loadPhases();
    connectWebSocket();
  });

  onDestroy(() => {
    stompClient?.deactivate();
  });
</script>

<div class="phase-overview">
  <h2>{$_('phases.title')}</h2>

  {#if loading}
    <p>{$_('phases.loading')}</p>
  {:else if loadError}
    <p class="error">{loadError}</p>
  {:else if tournamentCompleted}
    <!-- AC11: Tournament completion state -->
    <div class="tournament-completed">
      <h3>{$_('phases.tournamentCompleted')}</h3>
      <p>{$_('phases.tournamentCompletedMessage')}</p>
    </div>
  {/if}

  {#if actionError}
    <p class="error">{actionError}</p>
  {/if}

  {#each phases as phase (phase.id)}
    <div class="phase-card" class:active={phase.status === 'ACTIVE'}>
      <div class="phase-header">
        <h3>{phase.description || $_('phases.phaseLabel', { values: { n: phase.sequenceNumber } })}</h3>
        <!-- AC7: Status badge -->
        <span class="status-badge status-{phase.status.toLowerCase()}">{$_('phases.status.' + phase.status)}</span>
      </div>

      <!-- AC7: Current lap indicator -->
      {#if phase.status !== 'PENDING' || phase.totalLapCount > 0}
        <div class="lap-indicator">
          {$_('phases.lapIndicator', { values: { current: phase.currentLapNumber, total: phase.totalLapCount } })}
        </div>
      {/if}

      <!-- AC7: Match progress bar -->
      {#if phase.matchCounts.total > 0}
        <div class="progress-section">
          <div class="progress-bar-label">
            {$_('phases.matchProgress', {
              values: {
                finished: finishedMatchCount(phase.matchCounts),
                total: phase.matchCounts.total
              }
            })}
          </div>
          <div class="progress-bar">
            <div
              class="progress-fill"
              style="width: {Math.round((finishedMatchCount(phase.matchCounts) / phase.matchCounts.total) * 100)}%"
            ></div>
          </div>
        </div>
      {/if}

      <!-- AC8: Preparation result display -->
      {#if preparationResults[phase.id]}
        {@const result = preparationResults[phase.id]!}
        <div class="prep-results" class:success={result.success} class:failure={!result.success}>
          <div class:step-ok={result.generateMatchesSuccess} class:step-fail={!result.generateMatchesSuccess}>
            {$_('phases.prep.generateMatches')}: {result.generateMatchesMessage}
          </div>
          <div class:step-ok={result.optimizeSlotsSuccess} class:step-fail={!result.optimizeSlotsSuccess}>
            {$_('phases.prep.optimizeSlots')}: {result.optimizeSlotsMessage}
          </div>
          <div class:step-ok={result.assignRefereesSuccess} class:step-fail={!result.assignRefereesSuccess}>
            {$_('phases.prep.assignReferees')}: {result.assignRefereesMessage}
          </div>
        </div>
      {/if}

      <!-- AC7: Action buttons based on phase status -->
      <div class="phase-actions">
        {#if phase.status === 'PENDING' && phase.matchCounts.total === 0}
          <!-- Phase not yet prepared: show Prepare button -->
          <!-- E05S08: Phase 2+ also gets a "Map Teams" button -->
          {#if phase.sequenceNumber > 1}
            <button
              class="btn btn-primary"
              onclick={() => push(`/tournaments/${tournamentId}/phases/${phase.id}/mapping`)}
            >
              {$_('phases.mapTeamsButton')}
            </button>
          {/if}
          <button
            class="btn btn-primary"
            disabled={preparing[phase.id]}
            onclick={() => preparePhase(phase.id)}
          >
            {preparing[phase.id] ? $_('phases.preparing') : $_('phases.prepareButton')}
          </button>
        {/if}

        {#if phase.status === 'PENDING' && phase.matchCounts.total > 0}
          <!-- Phase prepared but not started: show Start and Prepare (re-prepare) buttons -->
          <button
            class="btn btn-success"
            disabled={starting[phase.id]}
            onclick={() => startPhase(phase.id)}
          >
            {starting[phase.id] ? $_('phases.starting') : $_('phases.startButton')}
          </button>
          <button
            class="btn btn-secondary"
            disabled={preparing[phase.id]}
            onclick={() => preparePhase(phase.id)}
          >
            {preparing[phase.id] ? $_('phases.preparing') : $_('phases.reprepareButton')}
          </button>
          <!-- AC9: View schedule button after preparation -->
          <button class="btn btn-link" onclick={() => toggleSchedule(phase.id)}>
            {showSchedule[phase.id] ? $_('phases.hideScheduleButton') : $_('phases.viewScheduleButton')}
          </button>
        {/if}

        {#if phase.status === 'ACTIVE'}
          <!-- AC10: Active phase — advance lap + view schedule -->
          <button
            class="btn btn-warning"
            disabled={advancingLap[phase.id]}
            onclick={() => advanceLap(phase.id, false)}
          >
            {advancingLap[phase.id] ? $_('phases.advancingLap') : $_('phases.advanceLapButton')}
          </button>
          <button class="btn btn-link" onclick={() => toggleSchedule(phase.id)}>
            {showSchedule[phase.id] ? $_('phases.hideScheduleButton') : $_('phases.viewScheduleButton')}
          </button>
        {/if}

        {#if phase.status === 'COMPLETED'}
          <!-- Completed phase — view schedule only -->
          <button class="btn btn-link" onclick={() => toggleSchedule(phase.id)}>
            {showSchedule[phase.id] ? $_('phases.hideScheduleButton') : $_('phases.viewScheduleButton')}
          </button>
        {/if}
      </div>

      <!-- AC5: Force advance lap confirmation dialog -->
      {#if forceAdvanceConfirm[phase.id]}
        <div class="confirm-dialog">
          <p>{$_('phases.forceAdvanceLapConfirm')}</p>
          <button class="btn btn-danger" onclick={() => advanceLap(phase.id, true)}>
            {$_('phases.forceAdvanceLapConfirmButton')}
          </button>
          <button class="btn btn-secondary" onclick={() => { forceAdvanceConfirm = { ...forceAdvanceConfirm, [phase.id]: false }; }}>
            {$_('phases.cancelButton')}
          </button>
        </div>
      {/if}

      <!-- AC9: Schedule grid (shown after preparation) -->
      {#if showSchedule[phase.id]}
        <div class="schedule-section">
          <h4>{$_('phases.schedule.title')}</h4>
          {#if scheduleLoading[phase.id]}
            <p>{$_('phases.schedule.loading')}</p>
          {:else if schedules[phase.id] && schedules[phase.id]!.laps.length > 0}
            <table class="schedule-grid">
              <thead>
                <tr>
                  <th>{$_('phases.schedule.lapColumn')}</th>
                  <th>{$_('phases.schedule.fieldColumn')}</th>
                  <th>{$_('phases.schedule.team1Column')}</th>
                  <th>{$_('phases.schedule.team2Column')}</th>
                  <th>{$_('phases.schedule.refereeColumn')}</th>
                  <th>{$_('phases.schedule.stateColumn')}</th>
                </tr>
              </thead>
              <tbody>
                {#each schedules[phase.id]!.laps as lap (lap.lapNumber)}
                  {#each lap.matches as match, matchIdx (match.matchId)}
                    <tr class="match-row state-{match.matchState.toLowerCase()}">
                      {#if matchIdx === 0}
                        <td rowspan={lap.matches.length} class="lap-cell">
                          {$_('phases.schedule.lapLabel', { values: { n: lap.lapNumber } })}
                        </td>
                      {/if}
                      <td>{match.fieldNumber ?? '—'}</td>
                      <td>{match.team1Description}</td>
                      <td>{match.team2Description}</td>
                      <td>{match.refereeDescription}</td>
                      <td>{match.matchState}</td>
                    </tr>
                  {/each}
                {/each}
              </tbody>
            </table>
          {:else}
            <p>{$_('phases.schedule.empty')}</p>
          {/if}
        </div>
      {/if}
    </div>
  {/each}

  {#if !loading && phases.length === 0}
    <p>{$_('phases.noPhases')}</p>
  {/if}
</div>

<style>
  .phase-overview { max-width: 900px; margin: 0 auto; padding: 1rem; }
  .phase-card { border: 1px solid #ddd; border-radius: 6px; padding: 1rem; margin-bottom: 1rem; }
  .phase-card.active { border-color: #4caf50; background: #f9fff9; }
  .phase-header { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.5rem; }
  .phase-header h3 { margin: 0; }
  .status-badge { padding: 0.2rem 0.6rem; border-radius: 4px; font-size: 0.8rem; font-weight: 600; }
  .status-pending { background: #fff3cd; color: #856404; }
  .status-active { background: #d4edda; color: #155724; }
  .status-completed { background: #d1ecf1; color: #0c5460; }
  .lap-indicator { font-size: 0.9rem; color: #555; margin-bottom: 0.4rem; }
  .progress-section { margin-bottom: 0.5rem; }
  .progress-bar-label { font-size: 0.8rem; color: #666; margin-bottom: 0.2rem; }
  .progress-bar { height: 8px; background: #e9ecef; border-radius: 4px; overflow: hidden; }
  .progress-fill { height: 100%; background: #4caf50; transition: width 0.3s; }
  .phase-actions { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-top: 0.75rem; }
  .btn { padding: 0.4rem 0.9rem; border: none; border-radius: 4px; cursor: pointer; font-size: 0.9rem; }
  .btn:disabled { opacity: 0.6; cursor: not-allowed; }
  .btn-primary { background: #0d6efd; color: #fff; }
  .btn-success { background: #198754; color: #fff; }
  .btn-secondary { background: #6c757d; color: #fff; }
  .btn-warning { background: #ffc107; color: #212529; }
  .btn-danger { background: #dc3545; color: #fff; }
  .btn-link { background: transparent; color: #0d6efd; text-decoration: underline; padding: 0; }
  .prep-results { margin-top: 0.5rem; font-size: 0.85rem; padding: 0.5rem; border-radius: 4px; }
  .prep-results.success { background: #d4edda; }
  .prep-results.failure { background: #f8d7da; }
  .step-ok { color: #155724; }
  .step-fail { color: #721c24; font-weight: 600; }
  .confirm-dialog { border: 1px solid #ffc107; background: #fff3cd; padding: 0.75rem; border-radius: 4px; margin-top: 0.5rem; }
  .confirm-dialog p { margin: 0 0 0.5rem; }
  .confirm-dialog .btn { margin-right: 0.5rem; }
  .schedule-section { margin-top: 1rem; overflow-x: auto; }
  .schedule-section h4 { margin-bottom: 0.5rem; }
  .schedule-grid { border-collapse: collapse; width: 100%; font-size: 0.85rem; }
  .schedule-grid th, .schedule-grid td { border: 1px solid #dee2e6; padding: 0.4rem 0.6rem; text-align: left; }
  .schedule-grid th { background: #f8f9fa; font-weight: 600; }
  .lap-cell { background: #f0f4ff; font-weight: 600; vertical-align: top; }
  .error { color: #721c24; }
  .tournament-completed { text-align: center; padding: 2rem; background: #d4edda; border-radius: 8px; margin-bottom: 1rem; }
  .tournament-completed h3 { color: #155724; margin: 0 0 0.5rem; }
</style>
