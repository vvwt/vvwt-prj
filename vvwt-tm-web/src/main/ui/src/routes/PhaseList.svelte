<script lang="ts">
  /**
   * Phase overview list for a tournament — Story E48S05.
   *
   * Displays all phases of a tournament with their:
   *   - sequenceNumber, description, status badge, gameMode, currentLapNumber, match counts
   *
   * Navigation entry point: Tournaments.svelte "Phasen" button (AC-FRONTEND-NAV-FROM-TOURNAMENTS).
   * E47 shell mechanism (AC-FRONTEND-E47-HEADER-INTEGRATION): registers title + back-button via
   * pageHeader store; no per-page __header block.
   *
   * Read-only — no lifecycle buttons (those are E48S06 scope).
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { listPhases, type PhaseOverview } from '../stores/phaseStore.js';
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
</script>

<main class="phases">

  {#if loading}
    <p class="phases__loading">…</p>
  {:else if loadError}
    <p class="phases__error">{loadError}</p>
  {:else if phases.length === 0}
    <p class="phases__empty">{$_('phases.empty')}</p>
  {:else}
    <table class="phases__table">
      <thead>
        <tr>
          <th>{$_('phases.columns.sequenceNumber')}</th>
          <th>{$_('phases.columns.description')}</th>
          <th>{$_('phases.columns.status')}</th>
          <th>{$_('phases.columns.gameMode')}</th>
          <th>{$_('phases.columns.currentLap')}</th>
          <th>{$_('phases.columns.matches')}</th>
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

  .phases__loading,
  .phases__empty {
    color: #666;
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

  .badge--active {
    background: #d5f5e3;
    color: #1e8449;
  }

  .badge--completed {
    background: #d6eaf8;
    color: #1a5276;
  }
</style>
