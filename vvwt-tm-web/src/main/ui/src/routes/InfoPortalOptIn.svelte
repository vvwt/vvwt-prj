<script lang="ts">
  /**
   * Info-Portal opt-in control for a tournament — E62S02 AC3.
   *
   * Fetches the current opt-in status from the backend and allows
   * the TM-admin to opt the tournament into Info-Portal publication.
   *
   * Status values:
   *   DISABLED       — Info-Portal not configured on this server
   *   NOT_REGISTERED — Tournament not yet published to Info-Portal
   *   REGISTERED     — Tournament is published and registered
   *   ERROR          — Registration attempt failed
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { apiFetch } from '../lib/api.js';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';

  // ── Props (Svelte 5 runes) ────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── State ─────────────────────────────────────────────────────────────────
  type OptInStatus = 'DISABLED' | 'NOT_REGISTERED' | 'REGISTERED' | 'ERROR' | null;

  let status = $state<OptInStatus>(null);
  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let actionError = $state<string | null>(null);
  let submitting = $state(false);

  onMount(async () => {
    pageHeader.set({
      title: get(_)('infoPortal.title') ?? 'Info-Portal',
      backTo: '/tournaments',
      tournamentId: tournamentId || null,
      actions: [],
    });
    await fetchStatus();
  });

  onDestroy(() => {
    resetPageHeader();
  });

  async function fetchStatus(): Promise<void> {
    if (!tournamentId) return;
    loading = true;
    loadError = null;
    try {
      const response = await apiFetch(`/api/tournaments/${tournamentId}/info-portal`);
      if (!response.ok) {
        loadError = `HTTP ${response.status}`;
        return;
      }
      const json = await response.json();
      status = json.status as OptInStatus;
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  async function handleOptIn(): Promise<void> {
    actionError = null;
    submitting = true;
    try {
      const response = await apiFetch(`/api/tournaments/${tournamentId}/info-portal`, {
        method: 'POST',
      });
      if (!response.ok) {
        const json = await response.json().catch(() => ({}));
        actionError = (json as { error?: string }).error ?? `HTTP ${response.status}`;
        return;
      }
      // Refresh status after opt-in
      await fetchStatus();
    } catch (e: unknown) {
      actionError = e instanceof Error ? e.message : String(e);
    } finally {
      submitting = false;
    }
  }
</script>

<div class="info-portal-opt-in">
  {#if loading}
    <p class="loading">Laden…</p>
  {:else if loadError}
    <p class="error">Fehler: {loadError}</p>
  {:else if status === 'DISABLED'}
    <div class="status-disabled">
      <span class="status-icon">⊘</span>
      <p>Info-Portal nicht konfiguriert</p>
    </div>
  {:else if status === 'REGISTERED'}
    <div class="status-registered">
      <span class="status-icon">✓</span>
      <p>Bereits veröffentlicht</p>
    </div>
  {:else if status === 'NOT_REGISTERED'}
    <div class="status-not-registered">
      <p>Dieses Turnier ist noch nicht im Info-Portal veröffentlicht.</p>
      {#if actionError}
        <p class="error">{actionError}</p>
      {/if}
      <button
        class="btn-primary"
        disabled={submitting}
        onclick={handleOptIn}
      >
        {submitting ? 'Wird veröffentlicht…' : 'Opt-in: Im Info-Portal veröffentlichen'}
      </button>
    </div>
  {:else if status === 'ERROR'}
    <div class="status-error">
      <span class="status-icon">✗</span>
      <p>Veröffentlichung fehlgeschlagen.</p>
      {#if actionError}
        <p class="error">{actionError}</p>
      {/if}
      <button
        class="btn-secondary"
        disabled={submitting}
        onclick={handleOptIn}
      >
        {submitting ? 'Wird erneut versucht…' : 'Erneut versuchen'}
      </button>
    </div>
  {/if}
</div>

<style>
  .info-portal-opt-in {
    padding: 1rem;
  }
  .status-disabled {
    color: var(--color-text-muted, #888);
  }
  .status-registered {
    color: var(--color-success, #2a7a2a);
  }
  .status-error {
    color: var(--color-error, #c00);
  }
  .error {
    color: var(--color-error, #c00);
  }
  .status-icon {
    font-size: 1.5rem;
    margin-right: 0.5rem;
  }
  .btn-primary,
  .btn-secondary {
    padding: 0.5rem 1.25rem;
    border: none;
    border-radius: 4px;
    cursor: pointer;
  }
  .btn-primary {
    background: var(--color-primary, #005fa3);
    color: #fff;
  }
  .btn-secondary {
    background: var(--color-secondary, #666);
    color: #fff;
  }
  .btn-primary:disabled,
  .btn-secondary:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
</style>
