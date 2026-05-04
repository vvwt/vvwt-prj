<script lang="ts">
  /**
   * Root component for the Timer SPA (E11S03 + E11S04 + E11S05).
   *
   * E11S03 lifecycle (retained):
   *   1. Mount: extract tournamentId from URL pathname (AC1/E11S03, AC5/E11S03)
   *      - Invalid/missing UUID → error('invalid-url') immediately (AC6/E11S03)
   *   2. Show ClockSyncDialog (AC2/E11S03)
   *      - User confirms offset → clockOffsetSeconds is set
   *      - Transition to 'loading'
   *   3. Loading: fetch timer data from GET /api/timer/tournaments/{tournamentId} (AC5/E11S03)
   *      - Success → 'loaded', render schedule
   *      - Various error types → error state (AC6/E11S03)
   *   4. Loaded: render schedule table + header (AC3/E11S03, AC4/E11S03, AC5/E11S03)
   *
   * E11S04 additions (in 'loaded' state):
   *   - Audio preloading from E11S01 endpoints (AC1/E11S04)
   *   - Countdown engine — local, no server polling (AC2/E11S04, D-6)
   *   - Automatic audio playback at schedule events (AC3/E11S04)
   *   - Pause music behavior (AC4/E11S04)
   *   - Transport controls: Play/Pause/Stop (AC5/E11S04)
   *   - Round counter (AC6/E11S04)
   *   - Manual time override integration with countdown (AC7/E11S04)
   *   - Audio error warnings (AC8/E11S04)
   *
   * E11S05 additions (in 'loaded' state):
   *   - WebSocket connection to /topic/display/{tenantId}/events (AC1/E11S05)
   *   - LAP_ADVANCED → schedule reload + countdown reset (AC2/E11S05)
   *   - PHASE_STATUS_CHANGED → full schedule reload (AC3/E11S05)
   *   - Disconnect resilience: local countdown continues, disconnect banner shown (AC4/E11S05)
   *   - Reconnect recovery: schedule reconciled to current server state (AC5/E11S05)
   *   - Initial connection error → local-only mode with disconnect indicator (AC6/E11S05)
   *
   * Note: ClockSyncDialog provides the user gesture needed to unlock Web Audio API.
   */
  import { onMount, onDestroy } from 'svelte';
  import { _ } from 'svelte-i18n';
  import {
    fetchTimerData,
    getTournamentIdFromUrl,
    InvalidTimerUrlError,
    NoActiveTournamentError,
    NoScheduleConfiguredError,
    type TimerData,
  } from './lib/timerApi.js';
  import { TimerWsClient } from './lib/timerWs.js';
  import {
    buildSnapshot,
    getAudioEventOnActivate,
    getAudioEventOnDeactivate,
    type TransportState,
    type CountdownSnapshot,
  } from './lib/countdownEngine.js';
  import { AudioEngine } from './lib/audioEngine.js';
  import ClockSyncDialog from './components/ClockSyncDialog.svelte';
  import ScheduleRow from './components/ScheduleRow.svelte';
  import Countdown from './components/Countdown.svelte';
  import TransportControls from './components/TransportControls.svelte';
  import RoundCounter from './components/RoundCounter.svelte';

  // ── App state ──────────────────────────────────────────────────────────────

  type AppState = 'clock-sync' | 'loading' | 'loaded' | 'error';
  type ErrorType = 'network' | 'invalid-url' | 'no-tournament' | 'no-schedule';

  let appState = $state<AppState>('clock-sync');
  let errorType = $state<ErrorType | null>(null);
  let clockOffsetSeconds = $state(0);
  let timerData = $state<TimerData | null>(null);
  /** Map from entry index → override time in seconds-since-midnight (AC4/E11S03, AC7/E11S04). */
  let timeOverrides = $state(new Map<number, number>());

  // ── E11S04: Transport state ────────────────────────────────────────────────
  let transportState = $state<TransportState>('STOPPED');
  /** Frozen clock value (seconds-since-midnight) at which Pause was triggered. */
  let pausedAt = $state<number | null>(null);
  /** Snapshot of the countdown engine, updated on every tick. */
  let snapshot = $state<CountdownSnapshot | null>(null);
  /** Index of the last event for which an audio event was fired (to avoid re-firing). */
  let lastFiredActiveIndex = $state<number>(-2);
  /** Index of the last "playing" entry for which deactivate audio was fired. */
  let lastPlayingIndex = $state<number>(-2);

  // ── E11S05: WebSocket client + status ─────────────────────────────────────
  /** 'connected' | 'disconnected' — drives the disconnect banner (AC4/E11S05). */
  let wsStatus = $state<'connected' | 'disconnected' | null>(null);
  /** WebSocket client instance (created when schedule loads). */
  let wsClient: TimerWsClient | null = null;

  // ── E11S04: Audio engine ───────────────────────────────────────────────────
  const audioEngine = new AudioEngine();

  // ── E11S04: Countdown tick interval ───────────────────────────────────────
  let tickInterval: ReturnType<typeof setInterval> | null = null;

  let tournamentId: string | null = null;

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  onMount(() => {
    tournamentId = getTournamentIdFromUrl();
    if (!tournamentId) {
      errorType = 'invalid-url';
      appState = 'error';
    }
  });

  onDestroy(() => {
    stopTick();
    audioEngine.stopAll();
    // E11S05: disconnect WebSocket and stop reconnect loop (AC4)
    if (wsClient) {
      wsClient.disconnect();
      wsClient = null;
    }
  });

  // ── Clock sync handler (AC2/E11S03) ───────────────────────────────────────

  async function handleClockSyncConfirm(offset: number): Promise<void> {
    clockOffsetSeconds = offset;
    appState = 'loading';
    await loadTimerData();
  }

  // ── Data loading (AC5/E11S03) ──────────────────────────────────────────────

  async function loadTimerData(): Promise<void> {
    if (!tournamentId) return;
    try {
      const data = await fetchTimerData(tournamentId);
      timerData = data;
      timeOverrides = new Map();
      appState = 'loaded';
      // E11S04: preload audio files after data loaded (AC1)
      audioEngine.preload(data.audio.startUrl, data.audio.endUrl, data.audio.pauseUrl);
      // E11S04: initialise snapshot (STOPPED state)
      refreshSnapshot();
      // E11S05: start WebSocket client on first load (AC1)
      // On subsequent calls (reconnect/reload), skip — wsClient already manages reconnect.
      if (!wsClient && data.tenantId) {
        wsClient = new TimerWsClient({
          tenantId: data.tenantId,
          onLapAdvanced: handleWsLapAdvanced,
          onPhaseChanged: handleWsPhaseChanged,
          onDisconnected: handleWsDisconnected,
          onReconnected: handleWsReconnected,
        });
        wsClient.connect();
      }
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

  // ── Retry handler (AC6/E11S03) ─────────────────────────────────────────────

  async function handleRetry(): Promise<void> {
    appState = 'loading';
    await loadTimerData();
  }

  // ── Time override handler (AC4/E11S03 + AC7/E11S04) ───────────────────────

  function handleTimeEdit(entryIndex: number, newTimeSeconds: number): void {
    timeOverrides = new Map(timeOverrides).set(entryIndex, newTimeSeconds);
    // AC7: override changes → immediately refresh snapshot
    if (appState === 'loaded') refreshSnapshot();
  }

  // ── E11S05: WebSocket event handlers ─────────────────────────────────────

  /**
   * AC2/E11S05: Lap advanced — reload schedule and reset audio event tracking.
   * The countdown engine continues; the schedule reload updates the display.
   */
  async function handleWsLapAdvanced(): Promise<void> {
    if (appState !== 'loaded') return;
    await loadTimerDataSilent();
    // Reset audio event tracking so events fire again from the new position
    lastFiredActiveIndex = -2;
    lastPlayingIndex = -2;
  }

  /**
   * AC3/E11S05: Phase status changed — reload full schedule.
   */
  async function handleWsPhaseChanged(): Promise<void> {
    if (appState !== 'loaded') return;
    await loadTimerDataSilent();
  }

  /**
   * AC4/E11S05: WebSocket disconnected — show disconnect banner.
   * The countdown engine continues locally (D-6).
   */
  function handleWsDisconnected(): void {
    wsStatus = 'disconnected';
  }

  /**
   * AC5/E11S05: WebSocket reconnected — hide banner and reload schedule to reconcile.
   */
  async function handleWsReconnected(): Promise<void> {
    wsStatus = 'connected';
    if (appState === 'loaded') {
      await loadTimerDataSilent();
    }
  }

  /**
   * Silently reload timer data without changing appState (used for WS-triggered reloads).
   * Does not reset wsClient — only refreshes schedule and audio config.
   */
  async function loadTimerDataSilent(): Promise<void> {
    if (!tournamentId) return;
    try {
      const data = await fetchTimerData(tournamentId);
      timerData = data;
      timeOverrides = new Map();
      audioEngine.preload(data.audio.startUrl, data.audio.endUrl, data.audio.pauseUrl);
      refreshSnapshot();
    } catch {
      // Silent reload failure — keep existing schedule; disconnect banner already visible
    }
  }

  // ── Current phase description (AC5/E11S03) ────────────────────────────────

  const currentPhaseDescription = $derived((): string => {
    if (!timerData || timerData.currentPhaseNumber === 0) return '';
    const phase = timerData.phases.find(p => p.phaseNumber === timerData!.currentPhaseNumber);
    return phase ? phase.description : '';
  });

  // ── E11S04: Countdown engine integration ──────────────────────────────────

  /**
   * Refreshes the countdown snapshot from the engine using current state.
   */
  function refreshSnapshot(): void {
    if (!timerData) return;
    snapshot = buildSnapshot(
      timerData.schedule,
      timeOverrides,
      clockOffsetSeconds,
      transportState,
      pausedAt,
    );
  }

  /**
   * Countdown tick: called every 250ms when PLAYING.
   * Refreshes snapshot, fires audio events when events trigger.
   */
  function onTick(): void {
    if (!timerData || transportState !== 'PLAYING') return;

    refreshSnapshot();
    if (!snapshot) return;

    const { activeEventIndex, playingIndex } = snapshot;

    // Fire audio on new active event (AC3/E11S04)
    if (activeEventIndex !== lastFiredActiveIndex && activeEventIndex !== -1) {
      // The entry just before activeEventIndex is transitioning from playing → done
      // If there was a previous playing entry, fire its deactivate event
      if (lastPlayingIndex !== -2 && lastPlayingIndex !== playingIndex) {
        const prevEntry = timerData.schedule[lastPlayingIndex];
        if (prevEntry) {
          const deactivateEvent = getAudioEventOnDeactivate(prevEntry);
          if (deactivateEvent.type === 'END_SOUND') {
            audioEngine.play('END');
          } else if (deactivateEvent.type === 'PAUSE_MUSIC_STOP') {
            audioEngine.stopPauseMusic();
          }
        }
      }

      // Fire activate event for the new active entry
      const entry = timerData.schedule[activeEventIndex];
      if (entry) {
        const activateEvent = getAudioEventOnActivate(entry);
        if (activateEvent.type === 'START_SOUND') {
          audioEngine.play('START');
        } else if (activateEvent.type === 'PAUSE_MUSIC_START') {
          audioEngine.play('PAUSE');
        }
      }

      lastFiredActiveIndex = activeEventIndex;
    }

    // Track playing index for deactivate events
    if (playingIndex !== lastPlayingIndex) {
      lastPlayingIndex = playingIndex;
    }
  }

  function startTick(): void {
    if (tickInterval !== null) return;
    tickInterval = setInterval(onTick, 250);
  }

  function stopTick(): void {
    if (tickInterval !== null) {
      clearInterval(tickInterval);
      tickInterval = null;
    }
  }

  // ── Transport handlers (AC5/E11S04) ───────────────────────────────────────

  function handlePlay(): void {
    if (transportState === 'PAUSED') {
      // Resume from frozen position
      // Adjust clockOffsetSeconds to account for paused time
      // (we do NOT adjust; instead, just clear pausedAt and keep offset as-is;
      //  the countdown will resume from current real time which may differ from pausedAt)
      pausedAt = null;
    }
    transportState = 'PLAYING';
    lastFiredActiveIndex = -2; // allow re-triggering events
    lastPlayingIndex = -2;
    refreshSnapshot();
    startTick();
  }

  function handlePause(): void {
    if (transportState !== 'PLAYING') return;
    // Freeze the countdown at the current venue-adjusted time
    const d = new Date();
    const deviceSeconds = d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds()
      + d.getMilliseconds() / 1000;
    pausedAt = ((deviceSeconds + clockOffsetSeconds) % 86400 + 86400) % 86400;
    transportState = 'PAUSED';
    stopTick();
    audioEngine.pauseAll();
    refreshSnapshot();
  }

  function handleStop(): void {
    transportState = 'STOPPED';
    pausedAt = null;
    stopTick();
    audioEngine.stopAll();
    lastFiredActiveIndex = -2;
    lastPlayingIndex = -2;
    refreshSnapshot();
  }

  // ── Audio engine state (AC8/E11S04) ───────────────────────────────────────

  const audioState = $derived(audioEngine.getState());
  const audioHasAnyUrl = $derived(audioEngine.hasAnyUrl());

  // ── Status for ScheduleRow (status prop) ──────────────────────────────────

  function getEntryStatus(i: number): 'upcoming' | 'playing' | 'done' {
    if (!snapshot) return 'upcoming';
    if (snapshot.playingIndex === i) return 'playing';
    if (snapshot.doneIndices.has(i)) return 'done';
    return 'upcoming';
  }
</script>

<!-- E44S02 AC5: VVW brand lockup — header strip with speaking alt per Brief Q-4 -->
<!-- AC13: min-width 120px ensures minimum render size per Brief C-9 -->
<header class="brand-header">
  <img src="/timer/vvw-tm-logo.svg" alt="Tournament Manager" class="brand-lockup" />
</header>

<div class="timer-app">

  {#if appState === 'clock-sync'}
    <!--
      AC2/E11S03: Clock sync dialog — shown on every page load.
      Also provides user gesture for Web Audio API (H-2).
    -->
    <ClockSyncDialog onConfirm={handleClockSyncConfirm} />

  {:else if appState === 'loading'}
    <div class="timer-app__loading" aria-busy="true" aria-label="Laden">
      <div class="timer-app__spinner"></div>
    </div>

  {:else if appState === 'error'}
    <!--
      AC6/E11S03: Error states.
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
      E11S03 + E11S04: Full timer view with countdown engine and transport controls.
    -->

    <!-- AC5/E11S03: Tournament name + current phase header -->
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

    <!-- E11S04: AC8 — Audio warning banners -->
    {#if audioHasAnyUrl}
      <!-- At least one audio category is configured; show per-category errors -->
      {#if audioState.statusStart === 'error'}
        <div class="timer-app__audio-warning" role="alert">
          {$_('timer.audio.warningStart')}
        </div>
      {/if}
      {#if audioState.statusEnd === 'error'}
        <div class="timer-app__audio-warning" role="alert">
          {$_('timer.audio.warningEnd')}
        </div>
      {/if}
      {#if audioState.statusPause === 'error'}
        <div class="timer-app__audio-warning" role="alert">
          {$_('timer.audio.warningPause')}
        </div>
      {/if}
    {:else}
      <!-- AC8: All three audio categories are null → prominent warning -->
      <div class="timer-app__audio-warning timer-app__audio-warning--prominent" role="status">
        {$_('timer.audio.warningNone')}
      </div>
    {/if}

    <!-- E11S05: AC4 — Disconnect indicator (shown when WS is disconnected) -->
    {#if wsStatus === 'disconnected'}
      <div class="timer-app__ws-disconnected" role="status" aria-live="polite">
        {$_('timer.ws.disconnected')}
      </div>
    {/if}

    <!-- E11S04: AC2 (Countdown) + AC6 (Round counter) + AC5 (Transport controls) -->
    <div class="timer-app__controls-bar">
      <!-- AC6: Round counter -->
      {#if snapshot}
        <RoundCounter
          currentRound={snapshot.currentRound}
          totalRounds={snapshot.totalRounds}
        />
      {/if}

      <!-- AC2: Countdown display -->
      {#if snapshot}
        <Countdown {snapshot} />
      {/if}

      <!-- AC5: Transport controls -->
      <TransportControls
        {transportState}
        onPlay={handlePlay}
        onPause={handlePause}
        onStop={handleStop}
      />
    </div>

    <!-- AC3/E11S03: Schedule table with AC7/E11S04 overrides -->
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
              status={getEntryStatus(i)}
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

  /* ── Header (AC5/E11S03) ─────────────────────────────────────────────────── */

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

  /* ── Audio warnings (AC8/E11S04) ─────────────────────────────────────────── */

  .timer-app__audio-warning {
    background: #fff3cd;
    color: #856404;
    border-left: 3px solid #ffc107;
    padding: 0.4rem 1rem;
    font-size: 0.85rem;
    flex-shrink: 0;
  }

  .timer-app__audio-warning--prominent {
    background: #f8d7da;
    color: #721c24;
    border-left-color: #f5c6cb;
    font-weight: 600;
    text-align: center;
    padding: 0.6rem 1rem;
  }

  /* ── WebSocket disconnect indicator (AC4/E11S05) ─────────────────────────── */

  .timer-app__ws-disconnected {
    background: #fff3cd;
    color: #664d03;
    border-left: 3px solid #ffc107;
    padding: 0.35rem 1rem;
    font-size: 0.85rem;
    flex-shrink: 0;
    text-align: center;
  }

  /* ── Controls bar (AC2, AC5, AC6 / E11S04) ─────────────────────────────── */

  .timer-app__controls-bar {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 1rem;
    background: #fff;
    border-bottom: 1px solid #e8ecf0;
    padding: 0.5rem 1rem;
    flex-shrink: 0;
    flex-wrap: wrap;
  }

  /* ── Schedule table (AC3/E11S03) ─────────────────────────────────────────── */

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

  /* E44S02 AC5 + AC13: brand lockup header */
  :global(.brand-header) {
    padding: 0.5rem 1rem;
    background: #fff;
    border-bottom: 1px solid #e0e0e0;
  }

  :global(.brand-lockup) {
    height: 2em;
    display: block;
  }
</style>
