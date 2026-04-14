<script lang="ts">
  /**
   * Root component for the Gesamtübersicht display SPA (E07S05).
   *
   * Lifecycle:
   *   1. On mount: read device token from localStorage (AC5, AC11)
   *   2. If no token: redirect to /display/register (AC11, E07S07)
   *   3. If token present: fetch all three display endpoints concurrently (AC5)
   *   4. Render:
   *      - Loading state while fetching
   *      - Error state (noPhase / unauthorized / generic) on failure (AC6, AC9)
   *      - OverviewLayout on success (AC1–AC4)
   *
   * Error classification (AC9):
   *   - NoActivePhaseError → errorType 'noPhase' (AC6)
   *   - UnauthorizedError  → errorType 'unauthorized' (AC9 re-register message)
   *   - ApiError / other   → errorType 'generic' (AC9 retry button)
   *
   * No hardcoded strings: all user-visible text uses svelte-i18n $_() (AC10).
   */
  import { onMount } from 'svelte';
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
  import OverviewLayout from './components/OverviewLayout.svelte';
  import ErrorPanel from './components/ErrorPanel.svelte';

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------

  let loading = $state(true);
  let errorType = $state<'noPhase' | 'unauthorized' | 'generic' | null>(null);
  let phaseData = $state<DisplayPhaseOverview | null>(null);
  let matchesData = $state<DisplayMatchesData | null>(null);
  let standingsData = $state<DisplayGroupStandings | null>(null);

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
  // Mount: token check + initial data load (AC5, AC11)
  // ---------------------------------------------------------------------------

  onMount(() => {
    const token = readDeviceToken();

    if (token === null) {
      // AC11: no device token in localStorage → redirect to display registration page (E07S07)
      window.location.href = '/display/register';
      return;
    }

    void loadData(token);
  });
</script>

<div class="display-app">
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
