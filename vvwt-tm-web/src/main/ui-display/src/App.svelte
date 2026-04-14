<script lang="ts">
  /**
   * Root component for the display SPA (E07S05, E07S06, E07S07).
   *
   * Client-side routing (E07S07):
   *   The display SPA uses a single Vite entry point and one index.html for both
   *   /display/overview and /display/register. This component reads
   *   window.location.pathname on mount and dispatches to the correct page:
   *   - pathname starts with '/display/register' → render DisplayRegisterPage (E07S07)
   *   - all other paths (including /display/overview) → render overview flow (E07S05)
   *
   * Lifecycle — overview flow (E07S05):
   *   1. On mount: read device token from localStorage (AC5, AC11)
   *   2. If no token: redirect to /display/register (AC11, E07S07)
   *   3. If token present: fetch all three display endpoints concurrently (AC5)
   *   4. Render:
   *      - Loading state while fetching
   *      - Error state (noPhase / unauthorized / generic) on failure (AC6, AC9)
   *      - OverviewLayout on success (AC1–AC4)
   *
   * E07S06 additions:
   *   - After initial load: connect to WebSocket using device token (AC1)
   *   - Handle MATCH_RESULT_CHANGED → refresh matches + standings (AC2, AC3)
   *   - Handle LAP_ADVANCED → refresh matches for new lap (AC4)
   *   - Handle PHASE_STATUS_CHANGED → full reload (AC5)
   *   - Reconnect with exponential backoff; fallback to polling after 5 failures (AC7, AC9)
   *   - Show ConnectionStatus indicator (AC7)
   *   - 401 on REST poll → redirect to /display/register (AC9)
   *
   * Error classification (AC9):
   *   - NoActivePhaseError → errorType 'noPhase' (AC6)
   *   - UnauthorizedError  → errorType 'unauthorized' (AC9 re-register message)
   *   - ApiError / other   → errorType 'generic' (AC9 retry button)
   *
   * No hardcoded strings: all user-visible text uses svelte-i18n $_() (AC10).
   */
  import { onMount, onDestroy } from 'svelte';
  import {
    readDeviceToken,
    fetchPhaseOverview,
    fetchMatches,
    fetchGroupStandings,
    UnauthorizedError,
    NoActivePhaseError,
    type DisplayPhaseOverview,
    type DisplayMatchesData,
    type DisplayGroupStandings,
  } from './lib/displayApi.js';
  import {
    connect as wsConnect,
    disconnect as wsDisconnect,
    EVENT_TYPE_MATCH_RESULT_CHANGED,
    EVENT_TYPE_LAP_ADVANCED,
    EVENT_TYPE_PHASE_STATUS_CHANGED,
    type WsEventMessage,
    type ConnectionStatus as WsConnectionStatus,
  } from './lib/websocket.js';
  import OverviewLayout from './components/OverviewLayout.svelte';
  import ErrorPanel from './components/ErrorPanel.svelte';
  import ConnectionStatusIndicator from './components/ConnectionStatus.svelte';
  import DisplayRegisterPage from './components/DisplayRegisterPage.svelte';

  // ---------------------------------------------------------------------------
  // Client-side routing (E07S07)
  // ---------------------------------------------------------------------------

  /**
   * True when the current URL is /display/register (E07S07).
   * Determined once at component creation time — routing is page-load-based,
   * not reactive (navigation triggers a full page load via window.location.href).
   */
  const isRegisterPage = window.location.pathname.startsWith('/display/register');

  /** Polling interval in ms when WebSocket fallback is active (AC9). */
  const POLLING_INTERVAL_MS = 5_000;

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------

  let loading = $state(true);
  let errorType = $state<'noPhase' | 'unauthorized' | 'generic' | null>(null);
  let phaseData = $state<DisplayPhaseOverview | null>(null);
  let matchesData = $state<DisplayMatchesData | null>(null);
  let standingsData = $state<DisplayGroupStandings | null>(null);
  let connectionStatus = $state<WsConnectionStatus>('disconnected');

  let activeToken: string = '';
  let pollingIntervalId: ReturnType<typeof setInterval> | null = null;

  // ---------------------------------------------------------------------------
  // Data loading
  // ---------------------------------------------------------------------------

  /**
   * Load all display data concurrently (AC5).
   * Classifies errors for specific UI messages (AC6, AC9).
   *
   * @param token valid device token from localStorage
   */
  async function loadData(token: string): Promise<void> {
    loading = true;
    errorType = null;
    phaseData = null;
    matchesData = null;
    standingsData = null;

    try {
      // AC5: fetch all three endpoints concurrently — render after all have resolved
      const [overview, matches, groups] = await Promise.all([
        fetchPhaseOverview(token),
        fetchMatches(token),
        fetchGroupStandings(token),
      ]);

      phaseData = overview;
      matchesData = matches;
      standingsData = groups;
    } catch (err: unknown) {
      if (err instanceof UnauthorizedError) {
        // AC9: 401 — device token invalid; show re-register instruction
        errorType = 'unauthorized';
      } else if (err instanceof NoActivePhaseError) {
        // AC6: 404 — no active tournament phase
        errorType = 'noPhase';
      } else {
        // AC9: network/server error — show generic error + retry button
        errorType = 'generic';
      }
    } finally {
      loading = false;
    }
  }

  /**
   * Refresh matches + standings after a MATCH_RESULT_CHANGED event (AC2, AC3).
   * Re-uses the active token from the current session.
   */
  async function refreshMatchesAndStandings(): Promise<void> {
    if (!activeToken) return;
    try {
      const [matches, groups] = await Promise.all([
        fetchMatches(activeToken),
        fetchGroupStandings(activeToken),
      ]);
      matchesData = matches;
      standingsData = groups;
    } catch (err) {
      if (err instanceof UnauthorizedError) {
        window.location.href = '/display/register';
      }
      // Other errors: silently ignore — data stays at last known state
    }
  }

  /**
   * Refresh matches for the new lap after a LAP_ADVANCED event (AC4).
   */
  async function refreshMatches(): Promise<void> {
    if (!activeToken) return;
    try {
      matchesData = await fetchMatches(activeToken);
    } catch (err) {
      if (err instanceof UnauthorizedError) {
        window.location.href = '/display/register';
      }
    }
  }

  /**
   * Full reload: re-fetch all data after a PHASE_STATUS_CHANGED event (AC5).
   */
  async function fullReload(): Promise<void> {
    if (!activeToken) return;
    await loadData(activeToken);
  }

  // ---------------------------------------------------------------------------
  // WebSocket event handler (E07S06 AC2–AC5)
  // ---------------------------------------------------------------------------

  /**
   * Dispatch incoming WebSocket events to the appropriate refresh function.
   * Called by the WebSocket module after each received STOMP message.
   */
  function handleWsEvent(msg: WsEventMessage): void {
    switch (msg.eventType) {
      case EVENT_TYPE_MATCH_RESULT_CHANGED:
        // AC2 + AC3: update match row + standings
        void refreshMatchesAndStandings();
        break;
      case EVENT_TYPE_LAP_ADVANCED:
        // AC4: refresh match grid for the new lap
        void refreshMatches();
        break;
      case EVENT_TYPE_PHASE_STATUS_CHANGED:
        // AC5: phase transition → full reload
        void fullReload();
        break;
      default:
        // Unknown event type — ignore silently
        break;
    }
  }

  // ---------------------------------------------------------------------------
  // Polling fallback (AC9)
  // ---------------------------------------------------------------------------

  /**
   * Activates the polling fallback when WebSocket reconnects are exhausted (AC9).
   * Polls all three REST endpoints at {@link POLLING_INTERVAL_MS} interval.
   * If a poll returns 401, redirects to /display/register.
   */
  function activatePollingFallback(): void {
    if (pollingIntervalId !== null) return; // already polling

    pollingIntervalId = setInterval(async () => {
      if (!activeToken) return;
      try {
        const [overview, matches, groups] = await Promise.all([
          fetchPhaseOverview(activeToken),
          fetchMatches(activeToken),
          fetchGroupStandings(activeToken),
        ]);
        phaseData = overview;
        matchesData = matches;
        standingsData = groups;
        errorType = null;
      } catch (err) {
        if (err instanceof UnauthorizedError) {
          stopPolling();
          window.location.href = '/display/register';
        }
        // NoActivePhaseError or network errors: show noPhase, keep polling
        else if (err instanceof NoActivePhaseError) {
          errorType = 'noPhase';
        }
      }
    }, POLLING_INTERVAL_MS);
  }

  function stopPolling(): void {
    if (pollingIntervalId !== null) {
      clearInterval(pollingIntervalId);
      pollingIntervalId = null;
    }
  }

  // ---------------------------------------------------------------------------
  // Retry handler (AC9)
  // ---------------------------------------------------------------------------

  /**
   * Retry handler for the ErrorPanel retry button (AC9).
   * Re-reads the token (in case it changed) and reloads data.
   */
  function handleRetry(): void {
    const token = readDeviceToken();
    if (token === null) {
      // Token was cleared while retry was pending — redirect to register
      window.location.href = '/display/register';
      return;
    }
    void loadData(token);
  }

  // ---------------------------------------------------------------------------
  // Mount: token check + initial data load + WebSocket connect (AC1, AC5, AC11)
  // ---------------------------------------------------------------------------

  onMount(() => {
    // E07S07: if this is the register page, the DisplayRegisterPage component
    // handles its own lifecycle. The overview flow (below) must not run.
    if (isRegisterPage) return;

    const token = readDeviceToken();

    if (token === null) {
      // AC11: no device token in localStorage → redirect to display registration page (E07S07)
      window.location.href = '/display/register';
      return;
    }

    activeToken = token;

    void loadData(token).then(() => {
      // E07S06 AC1: connect WebSocket after initial data load succeeds
      // (phaseData.phaseId holds the tenantId via the API, but tenantId is
      //  embedded in the device token auth — the server resolves it; the
      //  frontend needs tenantId from the phase overview response to subscribe
      //  to the correct topic)
      if (phaseData !== null && errorType === null) {
        // E07S06 AC1: phaseData.tenantId is returned by the server (E07S06 addition to
        // DisplayPhaseOverviewResponse). Use it to subscribe to the tenant-scoped topic.
        wsConnect(
          token,
          phaseData.tenantId,
          handleWsEvent,
          (status) => { connectionStatus = status; },
          activatePollingFallback
        );
      }
    });
  });

  // ---------------------------------------------------------------------------
  // Cleanup on destroy
  // ---------------------------------------------------------------------------

  onDestroy(() => {
    // Only the overview flow uses WebSocket and polling
    if (!isRegisterPage) {
      wsDisconnect();
      stopPolling();
    }
  });
</script>

{#if isRegisterPage}
  <!--
    E07S07: /display/register path — render the device registration lifecycle.
    DisplayRegisterPage manages its own state machine (CHECKING → REGISTERING →
    WAITING → redirect to /display/overview). It does not use any overview state.
  -->
  <DisplayRegisterPage />
{:else}
  <!--
    E07S05 + E07S06: /display/overview (and all other paths) — render the
    tournament overview flow with WebSocket real-time updates.
  -->
  <div class="display-app">
    <!-- E07S06 AC7: subtle connection status indicator (always rendered once data loads) -->
    {#if !loading && errorType === null && phaseData !== null}
      <ConnectionStatusIndicator status={connectionStatus} />
    {/if}

    {#if loading}
      <!-- Loading state — no user-visible text needed; spinner communicates progress -->
      <div class="display-app__loading" aria-busy="true" aria-label="Loading">
        <div class="display-app__spinner"></div>
      </div>
    {:else if errorType !== null}
      <!-- AC6, AC9: error state -->
      <ErrorPanel {errorType} onRetry={handleRetry} />
    {:else if phaseData !== null && matchesData !== null && standingsData !== null}
      <!-- AC1–AC4: main two-column layout -->
      <OverviewLayout
        {phaseData}
        {matchesData}
        {standingsData}
      />
    {/if}
  </div>
{/if}

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
    height: 100vh;
    overflow: hidden;
  }

  :global(#app) {
    height: 100vh;
    overflow: hidden;
  }

  .display-app {
    height: 100vh;
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }

  .display-app__loading {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
  }

  .display-app__spinner {
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
</style>
