<script lang="ts">
  /**
   * Slot Optimization admin view (E27S02, AC-SVELTE-CANCEL-UI-AUTHORED;
   * E63S07 AC-TEST-STATUS-DISPLAYED, AC-TEST-MANUAL-RECHECK).
   *
   * Polls GET /api/slotopt/tournaments/:tournamentId/status every 2 seconds.
   * Shows current state badge (running / idle / cancelled) and a Cancel button
   * that appears only while state is "running".
   *
   * E63S07 extension: also shows the dispatcher reachability status with a
   * manual re-check button (GET /api/slotopt/dispatcher/status + POST /api/slotopt/dispatcher/recheck).
   *
   * Hash route: /tournaments/:tournamentId/slot-optimization
   */
  import { onDestroy, onMount } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';

  /** Route params injected by svelte-spa-router. */
  export let params: { tournamentId?: string } = {};

  type OptState = 'running' | 'idle' | 'cancelled' | 'unknown';

  /** E63S07: dispatcher reachability tri-state. */
  type DispatcherStatusValue = 'REACHABLE' | 'UNREACHABLE' | 'NOT_CONFIGURED';

  interface StatusResponse {
    state: OptState;
    startedAt?: string;
    bestSoFarVarietyScore?: number;
    lastJobState?: string | null;  // E51S07: phase.last_job_state from DB (DEC-55 D-8)
  }

  /** E63S07: response from GET /api/slotopt/dispatcher/status and POST /api/slotopt/dispatcher/recheck */
  interface DispatcherStatusResponse {
    status: DispatcherStatusValue;
    dispatcherUrl?: string | null;
  }

  let state: OptState = 'unknown';
  let bestScore: number | null = null;
  let lastJobState: string | null = null;  // E51S07
  let errorMsg: string | null = null;
  let cancelMsg: string | null = null;
  let polling = true;

  // E63S07: dispatcher status state
  let dispatcherStatus: DispatcherStatusValue | null = null;
  let dispatcherUrl: string | null = null;
  let dispatcherErrorMsg: string | null = null;
  let recheckInFlight = false;  // AC-ERR-RECHECK-CONCURRENT-CLICKS: debounce re-check button

  async function fetchStatus(): Promise<void> {
    if (!params.tournamentId) return;
    try {
      const res = await fetch(
        `/api/slotopt/tournaments/${params.tournamentId}/status`,
        { credentials: 'include' }
      );
      if (!res.ok) { errorMsg = `Status fetch failed: ${res.status}`; return; }
      const body: StatusResponse = await res.json();
      state = body.state ?? 'unknown';
      bestScore = body.bestSoFarVarietyScore ?? null;
      lastJobState = body.lastJobState ?? null;  // E51S07
      errorMsg = null;
    } catch (e) {
      errorMsg = String(e);
    }
  }

  /** E63S07 AC-TEST-STATUS-DISPLAYED: fetch dispatcher reachability status on mount. */
  async function fetchDispatcherStatus(): Promise<void> {
    try {
      const res = await fetch(
        '/api/slotopt/dispatcher/status',
        { credentials: 'include' }
      );
      if (!res.ok) {
        dispatcherErrorMsg = `Dispatcher status fetch failed: ${res.status}`;
        return;
      }
      const body: DispatcherStatusResponse = await res.json();
      dispatcherStatus = body.status;
      dispatcherUrl = body.dispatcherUrl ?? null;
      dispatcherErrorMsg = null;
    } catch (e) {
      dispatcherErrorMsg = String(e);
    }
  }

  /**
   * E63S07 AC-TEST-MANUAL-RECHECK: operator-initiated dispatcher re-probe.
   * AC-ERR-RECHECK-CONCURRENT-CLICKS: button disabled while in-flight (recheckInFlight flag).
   */
  async function recheckDispatcher(): Promise<void> {
    if (recheckInFlight) return;
    recheckInFlight = true;
    try {
      const res = await fetch(
        '/api/slotopt/dispatcher/recheck',
        { method: 'POST', credentials: 'include' }
      );
      if (!res.ok) {
        dispatcherErrorMsg = `Re-check failed: ${res.status}`;
        return;
      }
      const body: DispatcherStatusResponse = await res.json();
      dispatcherStatus = body.status;
      dispatcherUrl = body.dispatcherUrl ?? null;
      dispatcherErrorMsg = null;
    } catch (e) {
      dispatcherErrorMsg = String(e);
    } finally {
      recheckInFlight = false;
    }
  }

  async function cancelOptimization(): Promise<void> {
    if (!params.tournamentId) return;
    try {
      const res = await fetch(
        `/api/slotopt/tournaments/${params.tournamentId}/cancel`,
        { method: 'POST', credentials: 'include' }
      );
      if (res.status === 409) {
        cancelMsg = 'No active optimization to cancel.';
      } else if (!res.ok) {
        cancelMsg = `Cancel failed: ${res.status}`;
      } else {
        cancelMsg = 'Cancellation signal sent.';
        await fetchStatus();
      }
    } catch (e) {
      cancelMsg = String(e);
    }
  }

  // E47S02 AC2/AC3/AC5/AC6/AC7/AC9: register page title via pageHeader store.
  // SlotOptimization is a sub-route with tournament context (Brief D-5/D-6):
  //   - backTo: resolveParent resolves /tournaments/:tournamentId/slot-optimization → /tournaments/:tournamentId/edit
  //   - tournamentId: params.tournamentId — tournament name shown in header per D-6
  //   - actions: [] — the inline cancel button is job-state-bound (stays in-page per D-4 + story notes)
  //   - title: slotopt.title i18n key per Brief D-14 (AC1)
  onMount(() => {
    const tid = params.tournamentId ?? '';
    pageHeader.set({
      title: get(_)('slotopt.title'),
      backTo: resolveParent('/tournaments/:tournamentId/slot-optimization', tid),
      tournamentId: tid || null,
      actions: [],
    });
    // E63S07: fetch dispatcher status on mount
    fetchDispatcherStatus();
  });

  // Start polling
  fetchStatus();
  const interval = setInterval(() => { if (polling) fetchStatus(); }, 2000);
  onDestroy(() => {
    polling = false;
    clearInterval(interval);
    resetPageHeader();
  });
</script>

<section class="slot-opt-panel">
  {#if errorMsg}
    <p class="error">{errorMsg}</p>
  {/if}

  <div class="status-row">
    <span class="label">Status:</span>
    <span class="badge badge--{state}">{state}</span>
  </div>

  {#if bestScore !== null}
    <div class="score-row">
      <span class="label">Best score so far:</span>
      <span>{bestScore.toFixed(4)}</span>
    </div>
  {/if}

  <!-- E51S07: display lastJobState from phase.last_job_state (DEC-55 D-8) -->
  {#if lastJobState !== null}
    <div class="job-state-row">
      <span class="label">Phase Job-Status:</span>
      <span class="badge badge--{lastJobState}">{lastJobState}</span>
    </div>
  {/if}

  {#if state === 'running'}
    <button class="btn btn--cancel" on:click={cancelOptimization}>
      Cancel optimization
    </button>
  {/if}

  {#if cancelMsg}
    <p class="cancel-msg">{cancelMsg}</p>
  {/if}

  <!-- E63S07 AC-TEST-STATUS-DISPLAYED: dispatcher reachability section -->
  <hr class="section-divider" />
  <div class="dispatcher-section">
    <div class="section-header">
      <span class="label">Dispatcher:</span>
      {#if dispatcherStatus !== null}
        <span class="badge badge--dispatcher-{dispatcherStatus.toLowerCase()}">{dispatcherStatus.replace('_', ' ')}</span>
      {:else}
        <span class="badge badge--unknown">checking…</span>
      {/if}
      <!-- E63S07 AC-TEST-MANUAL-RECHECK: re-check button
           AC-ERR-RECHECK-CONCURRENT-CLICKS: disabled while in-flight -->
      <button
        class="btn btn--recheck"
        on:click={recheckDispatcher}
        disabled={recheckInFlight}
        aria-busy={recheckInFlight}
      >
        {recheckInFlight ? 'Checking…' : 'Re-check'}
      </button>
    </div>

    {#if dispatcherUrl}
      <div class="dispatcher-url-row">
        <span class="label-sm">URL:</span>
        <span class="dispatcher-url">{dispatcherUrl}</span>
      </div>
    {/if}

    {#if dispatcherErrorMsg}
      <p class="error">{dispatcherErrorMsg}</p>
    {/if}
  </div>
</section>

<style>
  .slot-opt-panel { padding: 1rem; max-width: 520px; }
  .status-row, .score-row, .job-state-row { display: flex; gap: 0.5rem; align-items: center; margin: 0.5rem 0; }
  .label { font-weight: 600; }
  .badge { padding: 0.2rem 0.6rem; border-radius: 4px; font-size: 0.85rem; text-transform: capitalize; }
  .badge--running  { background: #d4edda; color: #155724; }
  .badge--idle     { background: #e2e3e5; color: #383d41; }
  .badge--cancelled { background: #fff3cd; color: #856404; }
  .badge--unknown  { background: #f8d7da; color: #721c24; }
  .btn--cancel { margin-top: 1rem; padding: 0.4rem 1rem; background: #dc3545; color: #fff; border: none; border-radius: 4px; cursor: pointer; }
  .btn--cancel:hover { background: #c82333; }
  .error { color: #721c24; }
  .cancel-msg { color: #155724; margin-top: 0.5rem; }

  /* E63S07: dispatcher section */
  .section-divider { margin: 1.25rem 0 1rem; border: none; border-top: 1px solid #dee2e6; }
  .dispatcher-section { }
  .section-header { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
  .badge--dispatcher-reachable    { background: #d4edda; color: #155724; }
  .badge--dispatcher-unreachable  { background: #f8d7da; color: #721c24; }
  .badge--dispatcher-not_configured { background: #e2e3e5; color: #383d41; }
  .btn--recheck { padding: 0.25rem 0.75rem; background: #6c757d; color: #fff; border: none; border-radius: 4px; cursor: pointer; font-size: 0.85rem; }
  .btn--recheck:hover:not(:disabled) { background: #5a6268; }
  .btn--recheck:disabled { opacity: 0.65; cursor: not-allowed; }
  .dispatcher-url-row { display: flex; gap: 0.5rem; align-items: flex-start; margin-top: 0.4rem; }
  .label-sm { font-weight: 600; font-size: 0.85rem; flex-shrink: 0; }
  .dispatcher-url { font-size: 0.85rem; word-break: break-all; color: #495057; }
</style>
