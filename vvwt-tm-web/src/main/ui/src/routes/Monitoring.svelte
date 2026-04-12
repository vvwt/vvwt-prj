<script lang="ts">
  /**
   * Live monitoring view — Story E05S10 AC6–AC12.
   *
   * Receives the phase ID via route parameter `params.phaseId`.
   *
   * Features:
   *   - Group standings table(s) with D-33 ranking (AC6, AC7)
   *   - Lap navigation with match detail (AC8, AC9)
   *   - Real-time updates via WebSocket (AC10)
   *   - Phase/tournament completion banner (AC11)
   *   - WebSocket connection status indicator (AC12)
   *   - Highlight animation on update (AC10)
   *
   * All visible strings use the svelte-i18n `$_()` function (AC12/i18n).
   */
  import { onMount, onDestroy } from 'svelte';
  import { _ } from 'svelte-i18n';
  import { apiFetch } from '../lib/api.js';
  import { Client } from '@stomp/stompjs';

  // ── Types ─────────────────────────────────────────────────────────────────

  interface GroupTableEntry {
    rank: number;
    avatarId: string;
    teamDescription: string;
    groupNumber: number;
    groupPosition: number;
    matchCount: number;
    points: number;
    setsWon: number;
    setsLost: number;
    setQuotient: number | null;
    ballsWon: number;
    ballsLost: number;
    ballQuotient: number | null;
    withoutAssessment: boolean;
  }

  interface LapSetResult {
    setIndex: number;
    team1Points: number;
    team2Points: number;
    setState: string;
  }

  interface LapMatch {
    matchId: string;
    fieldNumber: number | null;
    avatar1Id: string;
    avatar2Id: string;
    team1Description: string;
    team2Description: string;
    refereeDescription: string;
    matchState: string;
    setResults: LapSetResult[];
  }

  interface LapMatchesResponse {
    phaseId: string;
    lapNumber: number;
    matches: LapMatch[];
  }

  interface CurrentLapSummary {
    currentLapNumber: number;
    totalLapCount: number;
    matchCountByState: Record<string, number>;
    phaseStatus: string;
  }

  // ── Props ─────────────────────────────────────────────────────────────────

  interface Props {
    params?: { phaseId?: string };
  }
  let { params = {} }: Props = $props();
  const phaseId = $derived(params.phaseId ?? '');

  // ── State ─────────────────────────────────────────────────────────────────

  /** Map from groupNumber to sorted entries. */
  let groupTables = $state<Record<number, GroupTableEntry[]>>({});
  let lapData = $state<LapMatchesResponse | null>(null);
  let lapSummary = $state<CurrentLapSummary | null>(null);

  let loading = $state(true);
  let loadError = $state<string | null>(null);

  /** Currently viewed lap (for lap navigation). */
  let viewedLap = $state(1);

  /** Highlight animation flag — true briefly after a WS update. */
  let updated = $state(false);

  // ── WebSocket ─────────────────────────────────────────────────────────────

  let stompClient: Client | null = null;
  /** AC12: WebSocket connection status. */
  let wsConnected = $state(false);

  function connectWebSocket(): void {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${wsProtocol}//${window.location.host}/ws`;

    stompClient = new Client({
      brokerURL: wsUrl,
      onConnect: () => {
        wsConnected = true;
        stompClient?.subscribe('/topic/events', (msg) => {
          // AC10: re-fetch on any MATCH_RESULT_CHANGED, LAP_ADVANCED, or PHASE_COMPLETED event
          const event = JSON.parse(msg.body);
          if (event.eventType === 'LAP_ADVANCED') {
            // Refresh lap summary — new lap may have started
            loadLapSummary().then(() => {
              if (lapSummary) {
                viewedLap = lapSummary.currentLapNumber;
                loadLapMatches(viewedLap);
              }
            });
          } else {
            loadLapMatches(viewedLap);
          }
          loadGroupTables();
          triggerHighlight();
        });
      },
      onDisconnect: () => {
        wsConnected = false;
      },
    });
    stompClient.activate();
  }

  function triggerHighlight(): void {
    updated = true;
    setTimeout(() => { updated = false; }, 1500);
  }

  // ── Data loading ──────────────────────────────────────────────────────────

  async function loadGroupTables(): Promise<void> {
    if (!phaseId) return;
    try {
      const resp = await apiFetch(`/api/phases/${phaseId}/groups`);
      if (resp.ok) {
        const data: Record<string, GroupTableEntry[]> = await resp.json();
        // Convert string keys to number keys
        const tables: Record<number, GroupTableEntry[]> = {};
        for (const [k, v] of Object.entries(data)) {
          tables[Number(k)] = v;
        }
        groupTables = tables;
      }
    } catch {
      // Non-fatal: group tables remain stale until next successful load
    }
  }

  async function loadLapSummary(): Promise<void> {
    if (!phaseId) return;
    try {
      const resp = await apiFetch(`/api/phases/${phaseId}/current-lap`);
      if (resp.ok) {
        lapSummary = await resp.json();
      }
    } catch {
      // Non-fatal
    }
  }

  async function loadLapMatches(lap: number): Promise<void> {
    if (!phaseId || lap < 1) return;
    try {
      const resp = await apiFetch(`/api/phases/${phaseId}/laps/${lap}/matches`);
      if (resp.ok) {
        lapData = await resp.json();
      }
    } catch {
      // Non-fatal
    }
  }

  async function loadAll(): Promise<void> {
    if (!phaseId) return;
    loading = true;
    loadError = null;
    try {
      await loadLapSummary();
      if (lapSummary) {
        viewedLap = lapSummary.currentLapNumber > 0 ? lapSummary.currentLapNumber : 1;
      }
      await Promise.all([
        loadGroupTables(),
        loadLapMatches(viewedLap),
      ]);
    } catch {
      loadError = $_('monitoring.loadError');
    } finally {
      loading = false;
    }
  }

  // ── Lap navigation ────────────────────────────────────────────────────────

  function goToLap(lap: number): void {
    const total = lapSummary?.totalLapCount ?? 1;
    if (lap < 1 || lap > total) return;
    viewedLap = lap;
    loadLapMatches(lap);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  function formatQuotient(q: number | null): string {
    if (q === null) return '∞';
    return q.toFixed(2);
  }

  function isTerminal(state: string): boolean {
    return state.startsWith('FINISHED_') || state === 'CANCELED';
  }

  function matchStateClass(state: string): string {
    if (state === 'INPROGRESS') return 'state-inprogress';
    if (state.startsWith('FINISHED_')) return 'state-finished';
    if (state === 'CANCELED') return 'state-canceled';
    if (state === 'ENABLED') return 'state-enabled';
    return 'state-other';
  }

  function lapProgressPercent(summary: CurrentLapSummary): number {
    const total = Object.values(summary.matchCountByState)
        .reduce((s, c) => s + c, 0);
    if (total === 0) return 0;
    const done = (summary.matchCountByState['FINISHED_STANDOFF'] ?? 0)
        + (summary.matchCountByState['FINISHED_WINNER1'] ?? 0)
        + (summary.matchCountByState['FINISHED_WINNER2'] ?? 0)
        + (summary.matchCountByState['CANCELED'] ?? 0);
    return Math.round((done / total) * 100);
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  onMount(() => {
    loadAll();
    connectWebSocket();
  });

  onDestroy(() => {
    stompClient?.deactivate();
  });

  // ── Derived ───────────────────────────────────────────────────────────────

  const groupNumbers = $derived(Object.keys(groupTables).map(Number).sort((a, b) => a - b));
  const phaseCompleted = $derived(lapSummary?.phaseStatus === 'COMPLETED');
</script>

<div class="monitoring" class:updated={updated}>
  <!-- Header: phase status + WS indicator (AC12) -->
  <div class="monitoring-header">
    <h2>{$_('monitoring.title')}</h2>
    <div class="ws-indicator" class:connected={wsConnected} title={wsConnected ? $_('monitoring.wsConnected') : $_('monitoring.wsDisconnected')}>
      {wsConnected ? '●' : '○'} {wsConnected ? $_('monitoring.wsConnected') : $_('monitoring.wsDisconnected')}
    </div>
  </div>

  {#if loading}
    <p>{$_('monitoring.loading')}</p>
  {:else if loadError}
    <p class="error">{loadError}</p>
  {:else}

    <!-- AC11: Phase completion banner -->
    {#if phaseCompleted}
      <div class="phase-completed-banner">
        <strong>{$_('monitoring.phaseCompleted')}</strong>
        {$_('monitoring.phaseCompletedMessage')}
      </div>
    {/if}

    <!-- AC4: Current lap summary + progress -->
    {#if lapSummary}
      <div class="lap-summary">
        <span class="lap-label">
          {$_('monitoring.lapLabel', { values: { current: lapSummary.currentLapNumber, total: lapSummary.totalLapCount } })}
        </span>
        <div class="progress-bar">
          <div class="progress-fill" style="width: {lapProgressPercent(lapSummary)}%"></div>
        </div>
        <span class="progress-pct">{lapProgressPercent(lapSummary)}%</span>
      </div>
    {/if}

    <!-- AC6/AC7: Group standings tables -->
    <section class="group-tables-section">
      <h3>{$_('monitoring.groupTables.title')}</h3>
      {#if groupNumbers.length === 0}
        <p>{$_('monitoring.groupTables.empty')}</p>
      {:else}
        {#each groupNumbers as gn (gn)}
          <div class="group-table-block" class:updated={updated}>
            <h4>{$_('monitoring.groupTables.groupLabel', { values: { n: gn } })}</h4>
            <table class="standings-table">
              <thead>
                <tr>
                  <th>{$_('monitoring.groupTables.rankColumn')}</th>
                  <th>{$_('monitoring.groupTables.teamColumn')}</th>
                  <th class="num">{$_('monitoring.groupTables.matchesColumn')}</th>
                  <th class="num">{$_('monitoring.groupTables.pointsColumn')}</th>
                  <th class="num">{$_('monitoring.groupTables.setsColumn')}</th>
                  <th class="num">{$_('monitoring.groupTables.setQuotientColumn')}</th>
                  <th class="num">{$_('monitoring.groupTables.ballsColumn')}</th>
                  <th class="num">{$_('monitoring.groupTables.ballQuotientColumn')}</th>
                </tr>
              </thead>
              <tbody>
                {#each groupTables[gn] as entry (entry.avatarId)}
                  <tr class:without-assessment={entry.withoutAssessment}>
                    <td>{entry.rank}</td>
                    <td>{entry.teamDescription}</td>
                    <td class="num">{entry.matchCount}</td>
                    <td class="num points">{entry.points}</td>
                    <td class="num">{entry.setsWon}:{entry.setsLost}</td>
                    <td class="num">{formatQuotient(entry.setQuotient)}</td>
                    <td class="num">{entry.ballsWon}:{entry.ballsLost}</td>
                    <td class="num">{formatQuotient(entry.ballQuotient)}</td>
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
        {/each}
      {/if}
    </section>

    <!-- AC8/AC9: Lap matches with set scores + navigation -->
    <section class="lap-section">
      <div class="lap-nav">
        <h3>{$_('monitoring.lapMatches.title', { values: { n: viewedLap } })}</h3>
        <div class="lap-nav-buttons">
          <button
            class="btn btn-secondary"
            disabled={viewedLap <= 1}
            onclick={() => goToLap(viewedLap - 1)}
          >
            ‹ {$_('monitoring.lapMatches.prevLap')}
          </button>
          <button
            class="btn btn-secondary"
            disabled={viewedLap >= (lapSummary?.totalLapCount ?? 1)}
            onclick={() => goToLap(viewedLap + 1)}
          >
            {$_('monitoring.lapMatches.nextLap')} ›
          </button>
        </div>
      </div>

      {#if lapData === null || lapData.matches.length === 0}
        <p>{$_('monitoring.lapMatches.empty')}</p>
      {:else}
        <div class="lap-matches" class:updated={updated}>
          {#each lapData.matches as match (match.matchId)}
            <div class="match-card {matchStateClass(match.matchState)}">
              <div class="match-header">
                <span class="field-label">
                  {$_('monitoring.lapMatches.fieldLabel', { values: { n: match.fieldNumber ?? '—' } })}
                </span>
                <span class="match-state-badge">{match.matchState}</span>
              </div>
              <div class="match-teams">
                <span class="team team1">{match.team1Description}</span>
                <span class="vs">vs</span>
                <span class="team team2">{match.team2Description}</span>
              </div>
              {#if match.refereeDescription && match.refereeDescription !== '—'}
                <div class="referee">
                  {$_('monitoring.lapMatches.refereeLabel')}: {match.refereeDescription}
                </div>
              {/if}
              <!-- AC9: Set scores -->
              {#if match.setResults.length > 0}
                <div class="set-scores">
                  {#each match.setResults as sr (sr.setIndex)}
                    <span class="set-score" class:set-finished={sr.setState !== 'OPEN'}>
                      {sr.team1Points}:{sr.team2Points}
                    </span>
                  {/each}
                </div>
              {/if}
              <!-- E05S11 AC7: link to result correction view -->
              <div class="correction-link">
                <a href="#/phases/{phaseId}/matches/{match.matchId}/correct" class="btn-correct">
                  {$_('monitoring.correctButton')}
                </a>
              </div>
            </div>
          {/each}
        </div>
      {/if}
    </section>

  {/if}
</div>

<style>
  .monitoring { max-width: 1100px; margin: 0 auto; padding: 1rem; }

  /* AC10: highlight pulse on WS update */
  @keyframes highlightPulse {
    0%   { background-color: transparent; }
    30%  { background-color: #fffde7; }
    100% { background-color: transparent; }
  }
  .monitoring.updated { animation: highlightPulse 1.5s ease; }
  .group-table-block.updated { animation: highlightPulse 1.5s ease; }
  .lap-matches.updated { animation: highlightPulse 1.5s ease; }

  /* Header */
  .monitoring-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1rem; }
  .monitoring-header h2 { margin: 0; }
  .ws-indicator { font-size: 0.85rem; padding: 0.25rem 0.7rem; border-radius: 12px; background: #f8d7da; color: #721c24; }
  .ws-indicator.connected { background: #d4edda; color: #155724; }

  /* Phase completed banner */
  .phase-completed-banner {
    background: #d1ecf1; color: #0c5460; border: 1px solid #bee5eb;
    padding: 0.75rem 1rem; border-radius: 6px; margin-bottom: 1rem;
  }

  /* Lap summary */
  .lap-summary { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1.5rem; }
  .lap-label { font-size: 0.95rem; font-weight: 600; white-space: nowrap; }
  .progress-bar { flex: 1; height: 10px; background: #e9ecef; border-radius: 5px; overflow: hidden; }
  .progress-fill { height: 100%; background: #4caf50; transition: width 0.4s; }
  .progress-pct { font-size: 0.85rem; color: #666; white-space: nowrap; }

  /* Group tables */
  .group-tables-section { margin-bottom: 2rem; }
  .group-tables-section h3 { margin-bottom: 0.75rem; }
  .group-table-block { margin-bottom: 1.5rem; overflow-x: auto; }
  .group-table-block h4 { margin: 0 0 0.4rem; color: #444; }
  .standings-table { border-collapse: collapse; width: 100%; font-size: 0.875rem; }
  .standings-table th, .standings-table td { border: 1px solid #dee2e6; padding: 0.35rem 0.6rem; text-align: left; }
  .standings-table th { background: #f8f9fa; font-weight: 600; }
  .standings-table th.num, .standings-table td.num { text-align: right; }
  .standings-table td.points { font-weight: 700; }
  .standings-table tr.without-assessment { color: #999; font-style: italic; }

  /* Lap section */
  .lap-section h3 { margin-bottom: 0.5rem; }
  .lap-nav { display: flex; align-items: center; justify-content: space-between; margin-bottom: 0.75rem; }
  .lap-nav h3 { margin: 0; }
  .lap-nav-buttons { display: flex; gap: 0.5rem; }

  /* Match cards */
  .lap-matches { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 0.75rem; }
  .match-card { border: 1px solid #dee2e6; border-radius: 6px; padding: 0.75rem; font-size: 0.875rem; }
  .match-card.state-inprogress { border-color: #ffc107; background: #fffdf0; }
  .match-card.state-finished { border-color: #4caf50; background: #f9fff9; }
  .match-card.state-enabled { border-color: #0d6efd; background: #f0f4ff; }
  .match-card.state-canceled { border-color: #adb5bd; background: #f8f9fa; opacity: 0.7; }
  .match-header { display: flex; justify-content: space-between; margin-bottom: 0.4rem; }
  .field-label { font-size: 0.8rem; color: #666; }
  .match-state-badge { font-size: 0.75rem; font-weight: 600; padding: 0.1rem 0.4rem; border-radius: 4px; background: #e9ecef; }
  .match-teams { display: flex; align-items: center; gap: 0.4rem; font-weight: 600; margin-bottom: 0.3rem; }
  .team { flex: 1; }
  .team1 { text-align: left; }
  .team2 { text-align: right; }
  .vs { color: #888; font-size: 0.8rem; flex-shrink: 0; }
  .referee { font-size: 0.8rem; color: #666; margin-bottom: 0.3rem; }
  .set-scores { display: flex; gap: 0.3rem; flex-wrap: wrap; margin-top: 0.4rem; }
  .set-score { font-size: 0.8rem; padding: 0.1rem 0.4rem; background: #e9ecef; border-radius: 3px; }
  .set-score.set-finished { background: #d4edda; }
  .correction-link { margin-top: 0.5rem; }
  .btn-correct {
    display: inline-block; font-size: 0.75rem; padding: 0.15rem 0.5rem;
    background: #f0f4ff; border: 1px solid #0d6efd; color: #0d6efd;
    border-radius: 4px; text-decoration: none;
  }
  .btn-correct:hover { background: #0d6efd; color: #fff; }

  /* Buttons */
  .btn { padding: 0.35rem 0.8rem; border: none; border-radius: 4px; cursor: pointer; font-size: 0.875rem; }
  .btn:disabled { opacity: 0.5; cursor: not-allowed; }
  .btn-secondary { background: #6c757d; color: #fff; }

  .error { color: #721c24; }
</style>
