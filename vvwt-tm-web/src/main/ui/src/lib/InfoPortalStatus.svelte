<script lang="ts">
  /**
   * Info Portal publisher status banner (AC4, AC10, DEC-43 D3).
   *
   * Story E38S09 — displays algorithm-deprecation warnings and publisher error
   * states in the TM admin UI. Fetches from GET /api/info-portal/status on mount
   * and renders an appropriate banner.
   *
   * Banner severity:
   *   - HIGH (deprecationWarning.severity === 'HIGH') → red/critical banner
   *   - LOW  (deprecationWarning.severity === 'LOW')  → yellow/info banner
   *   - algorithmDeprecatedHardStop                   → red hard-stop banner
   *   - registrationError / signatureMismatch         → orange warning banner
   *   - publisherUnhealthy                            → orange warning banner
   *   - No active status                              → nothing rendered
   *
   * Per DEC-43 D3: deprecation warnings MUST be shown regardless of severity threshold.
   * When /api/info-portal/status returns 404 (feature disabled) the component
   * silently renders nothing — feature is optional per AC6.
   */
  import { onMount } from 'svelte';
  import { _ } from 'svelte-i18n';
  import { apiFetch } from './api.js';

  interface DeprecationWarning {
    algorithmId: string;
    deprecationDate: string; // ISO date string
    severity: 'HIGH' | 'LOW';
  }

  interface InfoPortalStatusResponse {
    registered: boolean;
    registrationError: boolean;
    signatureMismatch: boolean;
    algorithmDeprecatedHardStop: boolean;
    publisherUnhealthy: boolean;
    deprecationWarning?: DeprecationWarning;
  }

  let status: InfoPortalStatusResponse | null = null;
  let fetchError = false;

  onMount(async () => {
    try {
      const resp = await apiFetch('/api/info-portal/status');
      if (resp.status === 404 || resp.status === 503) {
        // Feature disabled or server unavailable — silently suppress (AC6)
        return;
      }
      if (!resp.ok) {
        fetchError = true;
        return;
      }
      status = await resp.json() as InfoPortalStatusResponse;
    } catch {
      // Network error — silently suppress; admin can check manually
    }
  });

  function daysUntil(dateStr: string): number {
    const target = new Date(dateStr);
    const now = new Date();
    return Math.ceil((target.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
  }
</script>

{#if status}
  {#if status.algorithmDeprecatedHardStop}
    <div class="info-portal-banner info-portal-banner--error" role="alert">
      <strong>{$_('infoPortal.hardStop.title')}</strong>
      <p>{$_('infoPortal.hardStop.body')}</p>
    </div>
  {:else if status.deprecationWarning}
    {@const w = status.deprecationWarning}
    {@const days = daysUntil(w.deprecationDate)}
    <div
      class="info-portal-banner {w.severity === 'HIGH'
        ? 'info-portal-banner--error'
        : 'info-portal-banner--warning'}"
      role="alert"
    >
      <strong>
        {w.severity === 'HIGH'
          ? $_('infoPortal.deprecationWarning.titleHigh', { values: { algorithm: w.algorithmId } })
          : $_('infoPortal.deprecationWarning.titleLow', { values: { algorithm: w.algorithmId } })}
      </strong>
      <p>
        {$_('infoPortal.deprecationWarning.body', {
          values: { algorithm: w.algorithmId, date: w.deprecationDate, days: days.toString() },
        })}
      </p>
    </div>
  {:else if status.registrationError}
    <div class="info-portal-banner info-portal-banner--warning" role="alert">
      <strong>{$_('infoPortal.registrationError.title')}</strong>
      <p>{$_('infoPortal.registrationError.body')}</p>
    </div>
  {:else if status.signatureMismatch}
    <div class="info-portal-banner info-portal-banner--warning" role="alert">
      <strong>{$_('infoPortal.signatureMismatch.title')}</strong>
      <p>{$_('infoPortal.signatureMismatch.body')}</p>
    </div>
  {:else if status.publisherUnhealthy}
    <div class="info-portal-banner info-portal-banner--warning" role="alert">
      <strong>{$_('infoPortal.publisherUnhealthy.title')}</strong>
      <p>{$_('infoPortal.publisherUnhealthy.body')}</p>
    </div>
  {/if}
{/if}

<style>
  .info-portal-banner {
    padding: 0.75rem 1rem;
    border-radius: 4px;
    margin-bottom: 1rem;
    font-family: sans-serif;
    font-size: 0.9rem;
    border: 1px solid transparent;
  }

  .info-portal-banner p {
    margin: 0.25rem 0 0;
  }

  .info-portal-banner--error {
    background-color: #fdecea;
    border-color: #e74c3c;
    color: #922b21;
  }

  .info-portal-banner--warning {
    background-color: #fef9e7;
    border-color: #f39c12;
    color: #7d6608;
  }
</style>
