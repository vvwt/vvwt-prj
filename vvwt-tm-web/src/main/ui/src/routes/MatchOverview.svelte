<script lang="ts">
  /**
   * Match overview page for an ACTIVE phase — Story E48S26.
   *
   * Route: /tournaments/:tournamentId/phases/:phaseId/matches
   *
   * Displays all matches of the phase with:
   *   - Round (lap number), field number
   *   - Team 1 name, Team 2 name (resolved by BE via TeamAvatar → Team.description)
   *   - Per-set scores (ordered by setIndex); "–" when no sets recorded
   *   - Localized match state label
   *   - "Korrigieren" button for correction-eligible matches
   *     (eligible: not INPROGRESS, not ONCHECK — mirrors backend guard)
   *
   * Entry point: PhaseList.svelte "Spiele anzeigen" button on ACTIVE phase row.
   * Back-nav: → /tournaments/:tournamentId/phases (via parentRouteMap).
   *
   * E47 shell: registers title + back-button via pageHeader store.
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { push } from 'svelte-spa-router';
  import { listPhaseMatches, type MatchSummary } from '../stores/correctionStore.js';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';

  // ── Props ─────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string; phaseId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');
  const phaseId = $derived(params.phaseId ?? '');

  // ── State ─────────────────────────────────────────────────────
  let matches = $state<MatchSummary[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  // ── Lifecycle ─────────────────────────────────────────────────
  onMount(async () => {
    pageHeader.set({
      title: get(_)('matchOverview.pageTitle'),
      backTo: resolveParent('/tournaments/:tournamentId/phases/:phaseId/matches', tournamentId, phaseId),
      tournamentId: tournamentId || null,
      actions: [],
    });
    await loadMatches();
  });

  onDestroy(() => {
    resetPageHeader();
  });

  async function loadMatches(): Promise<void> {
    loading = true;
    loadError = null;
    try {
      matches = await listPhaseMatches(phaseId);
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : get(_)('matchOverview.loadError');
    } finally {
      loading = false;
    }
  }

  /**
   * Returns true when the match state is eligible for correction
   * (not INPROGRESS or ONCHECK — client mirror of backend guard DEC-65 §D-7).
   */
  function isCorrectionEligible(state: string): boolean {
    return state !== 'INPROGRESS' && state !== 'ONCHECK';
  }

  /** Navigate to the correction form for a specific match. */
  function navigateToCorrection(matchId: string): void {
    push(`/tournaments/${tournamentId}/phases/${phaseId}/matches/${matchId}/correction`);
  }

  /** Format set scores for display: "25:10 25:15" or "–" when empty. */
  function formatSetScores(match: MatchSummary): string {
    if (!match.setScores || match.setScores.length === 0) return '–';
    return match.setScores
      .map(s => `${s.team1Points}:${s.team2Points}`)
      .join(' ');
  }
</script>

<main class="match-overview">

  {#if loading}
    <p class="match-overview__loading" data-testid="match-overview-loading">{$_('matchOverview.loading')}</p>
  {:else if loadError}
    <p class="match-overview__error" data-testid="match-overview-error">{loadError}</p>
  {:else if matches.length === 0}
    <p class="match-overview__empty" data-testid="match-overview-empty">
      {$_('matchOverview.empty')}
    </p>
  {:else}
    <table class="match-overview__table">
      <thead>
        <tr>
          <th>{$_('matchOverview.columns.lap')}</th>
          <th>{$_('matchOverview.columns.field')}</th>
          <th>{$_('matchOverview.columns.team1')}</th>
          <th>{$_('matchOverview.columns.team2')}</th>
          <th>{$_('matchOverview.columns.setScores')}</th>
          <th>{$_('matchOverview.columns.state')}</th>
          <th>{$_('matchOverview.columns.actions')}</th>
        </tr>
      </thead>
      <tbody>
        {#each matches as match (match.matchId)}
          <tr>
            <td>{match.lapNumber ?? '–'}</td>
            <td>{match.fieldNumber ?? '–'}</td>
            <td>{match.team1Name}</td>
            <td>{match.team2Name}</td>
            <td class="match-overview__set-scores">{formatSetScores(match)}</td>
            <td>
              <span class="match-overview__state">
                {$_(`matchOverview.state.${match.state}`, { default: match.state })}
              </span>
            </td>
            <td class="match-overview__actions">
              {#if isCorrectionEligible(match.state)}
                <button
                  class="btn btn--primary"
                  onclick={() => navigateToCorrection(match.matchId)}
                  data-testid="correction-btn-{match.matchId}"
                >
                  {$_('correction.correctButton')}
                </button>
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}

</main>

<style>
  .match-overview {
    padding: 2rem;
    font-family: sans-serif;
  }

  .match-overview__table {
    width: 100%;
    border-collapse: collapse;
  }

  .match-overview__table th,
  .match-overview__table td {
    text-align: left;
    padding: 0.5rem 0.75rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .match-overview__table th {
    font-weight: 600;
    background: #f5f5f5;
  }

  .match-overview__error {
    color: #c0392b;
    margin-bottom: 1rem;
  }

  .match-overview__loading,
  .match-overview__empty {
    color: #666;
  }

  .match-overview__set-scores {
    font-variant-numeric: tabular-nums;
  }

  .match-overview__actions {
    white-space: nowrap;
  }

  .match-overview__state {
    font-size: 0.85rem;
  }

  .btn {
    display: inline-block;
    border: none;
    border-radius: 4px;
    padding: 0.3rem 0.75rem;
    font-size: 0.85rem;
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
</style>
