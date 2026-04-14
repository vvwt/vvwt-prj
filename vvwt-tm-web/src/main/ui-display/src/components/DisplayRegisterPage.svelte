<script lang="ts">
  /**
   * Display device registration page (E07S07).
   *
   * Lifecycle (AC1–AC11):
   *
   *   1. On mount: check localStorage for an existing device token (AC5, AC11).
   *   2. Token found → validate via GET /api/devices/status (AC5):
   *        - configured (configuration non-null):
   *            display_schema === 'OVERVIEW' → redirect to /display/overview (AC4)
   *        - registered (no config) → show waiting screen + start polling (AC3)
   *        - 404 → clear token → re-register (AC7)
   *        - network error → show error state with retry (AC9)
   *   3. No token → register: POST /api/devices/register { deviceType: 'DISPLAY' } (AC1):
   *        - 201 → store token in localStorage (AC11) → show waiting screen + start polling (AC2, AC3)
   *        - 429 → show limit-reached error, no retry loop (AC6)
   *        - network/server error → show error state with retry button (AC9)
   *   4. Poll loop: GET /api/devices/status every 3 s (AC3):
   *        - configuration non-null + display_schema === 'OVERVIEW' → redirect (AC4)
   *        - 404 → clear token → restart from step 1 (AC7)
   *        - continuous failure > 30 s → show "connection lost" message (AC9)
   *        - transient error → keep polling, update message (AC9)
   *
   * All user-visible strings use $_() from svelte-i18n (AC10).
   * No admin credentials or admin functionality exposed (AC11).
   * All assets are bundled locally — no CDN references (AC8, DEC-16).
   */

  import { onMount, onDestroy } from 'svelte';
  import { _ } from 'svelte-i18n';
  import {
    readDeviceToken,
    writeDeviceToken,
    clearDeviceToken,
    registerDisplayDevice,
    pollDeviceStatus,
    DeviceLimitError,
    DeviceRemovedError,
    DISPLAY_SCHEMA_OVERVIEW,
  } from '../lib/displayApi.js';

  // ---------------------------------------------------------------------------
  // Page state machine
  // ---------------------------------------------------------------------------

  /**
   * State machine states:
   *   CHECKING       — initial; checking localStorage for existing token
   *   REGISTERING    — calling POST /api/devices/register
   *   WAITING        — registered; polling for configuration assignment (AC3)
   *   LIMIT_REACHED  — 429 from register endpoint (AC6)
   *   REGISTER_ERROR — non-429 error from register endpoint (AC9)
   *   CONNECTION_LOST — polling failed continuously for > 30 s (AC9)
   */
  type PageState =
    | 'CHECKING'
    | 'REGISTERING'
    | 'WAITING'
    | 'LIMIT_REACHED'
    | 'REGISTER_ERROR'
    | 'CONNECTION_LOST';

  let pageState = $state<PageState>('CHECKING');

  /**
   * Truncated device token for display on the waiting screen (AC2).
   * Shows the last 8 characters so the organizer can match the device.
   * Full token is never shown to avoid confusion with PIN displays.
   */
  let displayToken = $state<string>('');

  // ---------------------------------------------------------------------------
  // Polling internals (AC3)
  // ---------------------------------------------------------------------------

  /** Active polling interval handle — cleared on redirect, device removal, or component destroy. */
  let pollIntervalId: ReturnType<typeof setInterval> | null = null;

  /** Timestamp (ms) when the first consecutive poll failure was detected (AC9). */
  let firstFailureAt: number | null = null;

  /** Number of milliseconds of continuous failure before showing "connection lost" (AC9). */
  const CONNECTION_LOST_THRESHOLD_MS = 30_000;

  /** Poll interval in milliseconds (AC3 — default 3 seconds). */
  const POLL_INTERVAL_MS = 3_000;

  // ---------------------------------------------------------------------------
  // Poll lifecycle helpers
  // ---------------------------------------------------------------------------

  function stopPolling(): void {
    if (pollIntervalId !== null) {
      clearInterval(pollIntervalId);
      pollIntervalId = null;
    }
    firstFailureAt = null;
  }

  function redirectToOverview(): void {
    stopPolling();
    window.location.href = '/display/overview';
  }

  /**
   * Execute one polling tick: GET /api/devices/status (AC3).
   *
   * On configured OVERVIEW schema → redirect (AC4).
   * On DeviceRemovedError (404) → clear token + restart (AC7).
   * On continuous failure > 30 s → CONNECTION_LOST (AC9).
   *
   * @param token current device token
   */
  async function pollOnce(token: string): Promise<void> {
    try {
      const result = await pollDeviceStatus(token);

      // Successful response — reset failure timer (AC9)
      firstFailureAt = null;

      if (result.configuration !== null) {
        // AC3: non-null configuration → parse display_schema
        try {
          const config = JSON.parse(result.configuration) as { display_schema?: string };
          if (config.display_schema === DISPLAY_SCHEMA_OVERVIEW) {
            // AC4: redirect to /display/overview
            redirectToOverview();
          }
          // Unknown schema — keep polling (future schemas handled here in V2)
        } catch {
          // Malformed configuration JSON — keep polling; admin will need to reconfigure
        }
      }
      // REGISTERED with no configuration → keep polling (AC3)
    } catch (err: unknown) {
      if (err instanceof DeviceRemovedError) {
        // AC7: device deleted by admin — clear token and restart registration
        stopPolling();
        clearDeviceToken();
        await startRegistrationFlow();
        return;
      }

      // Transient network or server error — track failure duration (AC9)
      const now = Date.now();
      if (firstFailureAt === null) {
        firstFailureAt = now;
      } else if (now - firstFailureAt >= CONNECTION_LOST_THRESHOLD_MS) {
        // AC9: 30+ seconds of continuous failure → show connection lost message
        pageState = 'CONNECTION_LOST';
        // Keep polling — it may recover
      }
      // Before the threshold: continue polling silently (normal transient behaviour)
    }
  }

  /**
   * Start polling GET /api/devices/status every POLL_INTERVAL_MS (AC3).
   *
   * @param token the device token to poll with
   */
  function startPolling(token: string): void {
    stopPolling();
    firstFailureAt = null;
    pollIntervalId = setInterval(() => {
      void pollOnce(token);
    }, POLL_INTERVAL_MS);
  }

  // ---------------------------------------------------------------------------
  // Registration and initial validation flow
  // ---------------------------------------------------------------------------

  /**
   * Validate an existing token: GET /api/devices/status (AC5).
   *
   * - Configured OVERVIEW → redirect immediately (AC4)
   * - Registered (no config) → show waiting screen + start polling (AC3)
   * - DeviceRemovedError (404) → clear token + re-register (AC7)
   * - Other error → fall through to re-register (AC9 resilience)
   *
   * @param token token from localStorage
   */
  async function validateExistingToken(token: string): Promise<void> {
    try {
      const result = await pollDeviceStatus(token);

      if (result.configuration !== null) {
        // AC5: already configured — redirect immediately
        try {
          const config = JSON.parse(result.configuration) as { display_schema?: string };
          if (config.display_schema === DISPLAY_SCHEMA_OVERVIEW) {
            redirectToOverview();
            return;
          }
        } catch {
          // Malformed config — fall through to waiting screen
        }
      }

      // Registered but not yet configured — show waiting screen + start polling (AC3)
      displayToken = truncateToken(token);
      pageState = 'WAITING';
      startPolling(token);
    } catch (err: unknown) {
      if (err instanceof DeviceRemovedError) {
        // AC7: device removed — clear and re-register
        clearDeviceToken();
        await registerFresh();
      } else {
        // Network error during validation — re-register (safe: server deduplicates)
        clearDeviceToken();
        await registerFresh();
      }
    }
  }

  /**
   * Register a new DISPLAY device: POST /api/devices/register (AC1).
   *
   * On success: store token, show waiting screen, start polling (AC2, AC3).
   * On 429: show limit-reached error (AC6).
   * On other error: show register-error state with retry button (AC9).
   */
  async function registerFresh(): Promise<void> {
    pageState = 'REGISTERING';

    try {
      const result = await registerDisplayDevice();
      // AC11: store token in localStorage
      writeDeviceToken(result.deviceToken);
      // AC2: show waiting screen with truncated token
      displayToken = truncateToken(result.deviceToken);
      pageState = 'WAITING';
      // AC3: start polling for configuration
      startPolling(result.deviceToken);
    } catch (err: unknown) {
      if (err instanceof DeviceLimitError) {
        // AC6: limit reached — show clear error, no retry loop
        pageState = 'LIMIT_REACHED';
      } else {
        // AC9: generic error — show retry button
        pageState = 'REGISTER_ERROR';
      }
    }
  }

  /**
   * Entry point for the registration flow (AC1, AC5).
   *
   * Check localStorage first; if token exists, validate it.
   * If no token, register fresh.
   */
  async function startRegistrationFlow(): Promise<void> {
    const existingToken = readDeviceToken();
    if (existingToken !== null) {
      // AC5: token found — validate before re-registering
      await validateExistingToken(existingToken);
    } else {
      // No token — fresh registration (AC1)
      await registerFresh();
    }
  }

  // ---------------------------------------------------------------------------
  // Retry handler (AC9)
  // ---------------------------------------------------------------------------

  /**
   * Retry handler for the retry button in REGISTER_ERROR and CONNECTION_LOST states.
   * Clears any stale token and restarts the registration flow.
   */
  async function handleRetry(): Promise<void> {
    stopPolling();
    clearDeviceToken();
    await startRegistrationFlow();
  }

  // ---------------------------------------------------------------------------
  // Utility: truncate token for display (AC2)
  // ---------------------------------------------------------------------------

  /**
   * Return the last 8 characters of the token for display on the waiting screen (AC2).
   * Provides enough entropy for the organizer to match the device without showing
   * the full credential.
   *
   * @param token full device token
   */
  function truncateToken(token: string): string {
    return token.length > 8 ? '…' + token.slice(-8) : token;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle hooks
  // ---------------------------------------------------------------------------

  onMount(() => {
    void startRegistrationFlow();
  });

  onDestroy(() => {
    stopPolling();
  });
</script>

<div class="register-page" role="main">
  {#if pageState === 'CHECKING' || pageState === 'REGISTERING'}
    <!-- Registering / checking existing token — show spinner with status message -->
    <div class="register-page__status" aria-busy="true" aria-live="polite">
      <div class="register-page__spinner" aria-hidden="true"></div>
      <p class="register-page__message">{$_('display.register.registering')}</p>
    </div>

  {:else if pageState === 'WAITING' || pageState === 'CONNECTION_LOST'}
    <!-- AC2: waiting for assignment — show truncated token + waiting message -->
    <div class="register-page__waiting" aria-live="polite">
      <p class="register-page__heading">{$_('display.register.waitingTitle')}</p>
      <div class="register-page__token-block">
        <span class="register-page__token-label">{$_('display.register.tokenLabel')}:</span>
        <span class="register-page__token-value">{displayToken}</span>
      </div>
      {#if pageState === 'CONNECTION_LOST'}
        <!-- AC9: 30+ s failure — show connection-lost message -->
        <p class="register-page__connection-lost" role="alert">
          {$_('display.register.connectionLost')}
        </p>
      {/if}
    </div>

  {:else if pageState === 'LIMIT_REACHED'}
    <!-- AC6: registration limit — clear error, no retry button -->
    <div class="register-page__error" role="alert">
      <p class="register-page__message register-page__message--limit">
        {$_('display.register.limitReached')}
      </p>
    </div>

  {:else if pageState === 'REGISTER_ERROR'}
    <!-- AC9: registration error — show message + retry button -->
    <div class="register-page__error" role="alert">
      <p class="register-page__message register-page__message--error">
        {$_('display.register.registerError')}
      </p>
      <button
        class="register-page__retry-btn"
        type="button"
        onclick={() => { void handleRetry(); }}
      >
        {$_('display.register.retryButton')}
      </button>
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
    background: #1a1a2e;
    color: #eee;
    height: 100vh;
    overflow: hidden;
  }

  :global(#app) {
    height: 100vh;
    overflow: hidden;
  }

  .register-page {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100vh;
    padding: 2rem;
  }

  /* ---------- Registering / Checking state ---------- */

  .register-page__status {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 1.5rem;
  }

  .register-page__spinner {
    width: 48px;
    height: 48px;
    border: 5px solid #444;
    border-top-color: #4a90e2;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  /* ---------- Waiting state ---------- */

  .register-page__waiting {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 1.5rem;
    text-align: center;
  }

  .register-page__heading {
    font-size: 2rem;
    font-weight: bold;
    color: #f0f0f0;
    margin: 0;
  }

  .register-page__token-block {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    background: #2d2d54;
    padding: 0.75rem 1.5rem;
    border-radius: 8px;
  }

  .register-page__token-label {
    font-size: 1rem;
    color: #aaa;
  }

  /* AC2: token value displayed prominently for organizer identification */
  .register-page__token-value {
    font-family: monospace;
    font-size: 1.4rem;
    font-weight: bold;
    color: #f0f0f0;
    letter-spacing: 0.05em;
  }

  /* AC9: connection-lost message — subtle warning, does not block the UI */
  .register-page__connection-lost {
    font-size: 0.95rem;
    color: #e67e22;
    margin: 0;
  }

  /* ---------- Error states (AC6, AC9) ---------- */

  .register-page__error {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 1.5rem;
    text-align: center;
    max-width: 480px;
  }

  .register-page__message {
    font-size: 1.2rem;
    color: #aaa;
    margin: 0;
  }

  .register-page__message--limit {
    color: #e74c3c;
    font-size: 1.3rem;
  }

  .register-page__message--error {
    color: #e74c3c;
  }

  .register-page__retry-btn {
    padding: 0.7rem 1.8rem;
    font-size: 1rem;
    background: #4a90e2;
    color: #fff;
    border: none;
    border-radius: 4px;
    cursor: pointer;
  }

  .register-page__retry-btn:hover {
    background: #2471a3;
  }
</style>
