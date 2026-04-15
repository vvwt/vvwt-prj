<script lang="ts">
  /**
   * Root component for the Timer SPA (E11S03).
   *
   * Lifecycle:
   *   1. Mount: extract tournamentId from URL pathname (AC1, AC5)
   *      - Invalid/missing UUID → error('invalid-url') immediately (AC6)
   *   2. Show ClockSyncDialog (AC2)
   *      - User confirms offset → clockOffsetSeconds is set
   *      - Transition to 'loading'
   *   3. Loading: fetch timer data from GET /api/timer/{tournamentId} (AC5)
   *      - Success → 'loaded', render schedule
   *      - InvalidTimerUrlError → error('invalid-url') (AC6)
   *      - NoActiveTournamentError → error('no-tournament') (AC6)
   *      - NoScheduleConfiguredError → error('no-schedule') (AC6)
   *      - NetworkError → error('network') with retry button (AC6)
   *   4. Loaded: render schedule table + header (AC3, AC4, AC5)
   *
   * Note (story note): The ClockSyncDialog also provides the user gesture needed
   * to unlock Web Audio API AudioContext in future stories (H-2).
   */
  import { onMount } from 'svelte';
  import { _ } from 'svelte-i18n';
  import {
    fetchTimerData,
    getTournamentIdFromUrl,
    InvalidTimerUrlError,
    NoActiveTournamentError,
    NoScheduleConfiguredError,
    type TimerData,
  } from './lib/timerApi.js';
  import ClockSyncDialog from './components/ClockSyncDialog.svelte';
  import ScheduleRow from './components/ScheduleRow.svelte';

  // ── App state ──────────────────────────────────────────────────────────────

  type AppState = 'clock-sync' | 'loading' | 'loaded' | 'error';
  type ErrorType = 'network' | 'invalid-url' | 'no-tournament' | 'no-schedule';

  let appState = $state<AppState>('clock-sync');
  let errorType = $state<ErrorType | null>(null);
  let clockOffsetSeconds = $state(0);
  let timerData = $state<TimerData | null>(null);
  /** Map from entry index → override time in seconds-since-midnight (AC4). */
  let timeOverrides = $state(new Map<number, number>());

  let tournamentId: string | null = null;

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  onMount(() => {
    tournamentId = getTournamentIdFromUrl();
    if (!tournamentId) {
      // AC6: invalid URL (no UUID in path)
      errorType = 'invalid-url';
      appState = 'error';
    }
    // else: stay in 'clock-sync' state — dialog will be shown
  });

  // ── Clock sync handler (AC2) ───────────────────────────────────────────────

  /**
   * Called by ClockSyncDialog when the user confirms the clock offset.
   * Stores the offset and begins data loading.
   */
  async function handleClockSyncConfirm(offset: number): Promise<void> {
    clockOffsetSeconds = offset;
    appState = 'loading';
    await loadTimerData();
  }

  // ── Data loading (AC5) ─────────────────────────────────────────────────────

  /**
   * Fetches timer data from the E11S02 endpoint and updates state.
   */
  async function loadTimerData(): Promise<void> {
    if (!tournamentId) return;
    try {
      const data = await fetchTimerData(tournamentId);
      timerData = data;
      timeOverrides = new Map();
      appState = 'loaded';
    } catch (e: unknown) {
      if (e instanceof InvalidTimerUrlError) {
        errorType = 'invalid-url';
      } else if (e instanceof NoActiveTournamentError) {
        errorType = 'no-tournament';
      } else if (e instanceof NoScheduleConfiguredError) {
        errorType = 'no-schedule';
      } else {
        errorType = 'network';
      }
      appState = 'error';
    }
  }

  // ── Retry handler (AC6) ────────────────────────────────────────────────────

  /**
   * Retry: only available for network errors (AC6).
   * Re-loads data without re-asking for clock sync.
   */
  async function handleRetry(): Promise<void> {
    appState = 'loading';
    await loadTimerData();
  }

  // ── Time override handler (AC4) ────────────────────────────────────────────

  /**
   * Called by ScheduleRow when the user edits a trigger time (AC4).
   */
  function handleTimeEdit(entryIndex: number, newTimeSeconds: number): void {
    timeOverrides = new Map(timeOverrides).set(entryIndex, newTimeSeconds);
  }

  // ── Current phase description (AC5) ───────────────────────────────────────

  const currentPhaseDescription = $derived((): string => {
    if (!timerData || timerData.currentPhaseNumber === 0) return '';
    const phase = timerData.phases.find(p => p.phaseNumber === timerData!.currentPhaseNumber);
    return phase ? phase.description : '';
  });
</script>

<div class="timer-app">

  {#if appState === 'clock-sync'}
    <!--
      AC2: Clock sync dialog — shown on every page load, before any data is fetched.
      Cannot be dismissed without confirming the time.
    -->
    <ClockSyncDialog onConfirm={handleClockSyncConfirm} />

  {:else if appState === 'loading'}
    <div class="timer-app__loading" aria-busy="true" aria-label="Laden">
      <div class="timer-app__spinner"></div>
    </div>

  {:else if appState === 'error'}
    <!--
      AC6: Error states — each has a specific message per errorType.
      Network errors show a retry button; others are terminal (no retry).
    -->
    <div class="timer-app__error" role="alert">
      <p class="timer-app__error-message">
        {#if errorType === 'invalid-url'}
          {$_('timer.error.invalidUrl')}
        {:else if errorType === 'no-tournament'}
          {$_('timer.error.noTournament')}
        {:else if errorType === 'no-schedule'}
          {$_('timer.error.noSchedule')}
        {:else}
          {$_('timer.error.network')}
        {/if}
      </p>
      {#if errorType === 'network'}
        <button class="timer-app__retry-btn" onclick={handleRetry}>
          {$_('timer.error.retry')}
        </button>
      {/if}
    </div>

  {:else if appState === 'loaded' && timerData !== null}
    <!--
      AC3, AC4, AC5: Schedule display with editable times and header.
    -->

    <!-- AC5: Tournament name + current phase as page header -->
    <header class="timer-app__header">
      <h1 class="timer-app__tournament-name">{timerData.tournamentName}</h1>
      {#if timerData.currentPhaseNumber > 0}
        <p class="timer-app__current-phase">
          {$_('timer.header.phase')} {timerData.currentPhaseNumber}
          {#if currentPhaseDescription()}
            — {currentPhaseDescription()}
          {/if}
          {#if timerData.currentLapNumber > 0}
            | {$_('timer.header.lap')} {timerData.currentLapNumber}
          {/if}
        </p>
      {/if}
    </header>

    <!-- AC3: Schedule table -->
    <div class="timer-app__schedule-container">
      <table class="timer-app__schedule">
        <thead>
          <tr>
            <th class="timer-app__col-label">{$_('timer.schedule.roundLabel')}</th>
            <th class="timer-app__col-time">{$_('timer.clockSync.prompt')}</th>
            <th class="timer-app__col-status">Status</th>
          </tr>
        </thead>
        <tbody>
          {#each timerData.schedule as entry, i (i)}
            <ScheduleRow
              {entry}
              entryIndex={i}
              {clockOffsetSeconds}
              hasStartTime={timerData.hasStartTime}
              overrideTimeSeconds={timeOverrides.get(i) ?? null}
              onTimeEdit={handleTimeEdit}
              status="upcoming"
            />
          {/each}
        </tbody>
      </table>
    </div>

  {/if}

</div>

<style>
  :global(*, *::before, *::after) {
    box-sizing: border-box;
  }

  :global(body) {
    margin: 0;
    padding: 0;
    font-family: Arial, Helvetica, sans-serif;
    background: #f4f6f9;
    color: #2c3e50;
    min-height: 100vh;
  }

  :global(#app) {
    min-height: 100vh;
  }

  .timer-app {
    min-height: 100vh;
    display: flex;
    flex-direction: column;
  }

  /* ── Loading ─────────────────────────────────────────────────────────────── */

  .timer-app__loading {
    display: flex;
    align-items: center;
    justify-content: center;
    flex: 1;
  }

  .timer-app__spinner {
    width: 48px;
    height: 48px;
    border: 5px solid #ddd;
    border-top-color: #2980b9;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  /* ── Error ───────────────────────────────────────────────────────────────── */

  .timer-app__error {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    flex: 1;
    padding: 2rem;
    text-align: center;
  }

  .timer-app__error-message {
    font-size: 1.1rem;
    color: #c0392b;
    margin-bottom: 1rem;
  }

  .timer-app__retry-btn {
    padding: 0.6rem 1.5rem;
    background: #2980b9;
    color: #fff;
    border: none;
    border-radius: 4px;
    font-size: 1rem;
    cursor: pointer;
  }

  .timer-app__retry-btn:hover {
    background: #1a6eae;
  }

  /* ── Header (AC5) ────────────────────────────────────────────────────────── */

  .timer-app__header {
    background: #2c3e50;
    color: #fff;
    padding: 1rem 1.5rem;
    flex-shrink: 0;
  }

  .timer-app__tournament-name {
    margin: 0;
    font-size: 1.3rem;
    font-weight: 700;
  }

  .timer-app__current-phase {
    margin: 0.25rem 0 0;
    font-size: 0.95rem;
    opacity: 0.8;
  }

  /* ── Schedule table (AC3) ────────────────────────────────────────────────── */

  .timer-app__schedule-container {
    flex: 1;
    overflow-y: auto;
    padding: 1rem;
  }

  .timer-app__schedule {
    width: 100%;
    border-collapse: collapse;
    background: #fff;
    border-radius: 6px;
    overflow: hidden;
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  }

  .timer-app__schedule thead tr {
    background: #34495e;
    color: #fff;
  }

  .timer-app__col-label,
  .timer-app__col-time,
  .timer-app__col-status {
    padding: 0.6rem 0.75rem;
    font-size: 0.85rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    text-align: left;
  }

  .timer-app__col-status {
    text-align: center;
    width: 8rem;
  }

  .timer-app__col-time {
    width: 9rem;
  }
</style>
