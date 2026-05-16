<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Slot Optimization admin view (E27S02, AC-SVELTE-CANCEL-UI-AUTHORED).
   *
   * Polls GET /api/slotopt/tournaments/:tournamentId/status every 2 seconds.
   * Shows current state badge (running / idle / cancelled) and a Cancel button
   * that appears only while state is "running".
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

  interface StatusResponse {
    state: OptState;
    startedAt?: string;
    bestSoFarVarietyScore?: number;
    lastJobState?: string | null;  // E51S07: phase.last_job_state from DB (DEC-55 D-8)
  }

  let state: OptState = 'unknown';
  let bestScore: number | null = null;
  let lastJobState: string | null = null;  // E51S07
  let errorMsg: string | null = null;
  let cancelMsg: string | null = null;
  let polling = true;

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
</section>

<style>
  .slot-opt-panel { padding: 1rem; max-width: 480px; }
  .status-row, .score-row { display: flex; gap: 0.5rem; align-items: center; margin: 0.5rem 0; }
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
</style>
