<script lang="ts">
  /**
   * EmbeddedWorkerControl route — operator controls + observability for the embedded worker.
   *
   * Story E63S05 — AC-GOV-CONTROLLER-PLACEMENT, AC-TEST-WEB-IT.
   *
   * Route: /embedded-worker (hash-based, top-level admin page)
   *
   * Features:
   * - Displays current worker state (name + numeric code)
   * - Displays packet counters (completed, failed)
   * - Pause / Resume / Disable action buttons
   * - Polls status every 5 s while mounted
   * - Registers page title via pageHeader store (E47S01 shell)
   */
  import { onMount, onDestroy } from 'svelte';
  import { _ } from 'svelte-i18n';
  import { pageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';

  interface StatusResponse {
    state: string;
    stateCode: number;
    packetsCompleted: number;
    packetsFailed: number;
  }

  interface ControlResponse {
    success: boolean;
    message: string;
    state: string;
    stateCode: number;
  }

  let status: StatusResponse | null = $state(null);
  let errorMessage: string | null = $state(null);
  let actionMessage: string | null = $state(null);
  let loading: boolean = $state(true);

  let pollTimer: ReturnType<typeof setInterval> | null = null;

  onMount(() => {
    pageHeader.set({
      title: $_('embeddedWorker.title'),
      backTo: resolveParent('/embedded-worker', ''),
      tournamentId: null,
      actions: [],
    });

    void fetchStatus();
    pollTimer = setInterval(() => { void fetchStatus(); }, 5000);
  });

  onDestroy(() => {
    if (pollTimer !== null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  });

  async function fetchStatus(): Promise<void> {
    try {
      const response = await fetch('/api/slotopt/embedded-worker/status', {
        credentials: 'same-origin',
      });
      if (!response.ok) {
        errorMessage = `Status-Abruf fehlgeschlagen: HTTP ${response.status}`;
        return;
      }
      const body: StatusResponse = await response.json();
      status = body;
      errorMessage = null;
    } catch (e) {
      errorMessage = 'Fehler beim Abrufen des Worker-Status.';
    } finally {
      loading = false;
    }
  }

  async function sendControl(action: 'pause' | 'resume' | 'disable'): Promise<void> {
    actionMessage = null;
    try {
      const response = await fetch(`/api/slotopt/embedded-worker/${action}`, {
        method: 'POST',
        credentials: 'same-origin',
      });
      const body: ControlResponse = await response.json();
      actionMessage = body.message;
      // Refresh status immediately after control
      await fetchStatus();
    } catch (e) {
      actionMessage = `Fehler beim Ausführen der Aktion "${action}".`;
    }
  }
</script>

<main class="embedded-worker-control">
  {#if loading}
    <p>Wird geladen…</p>
  {:else if errorMessage}
    <p class="error">{errorMessage}</p>
  {:else if status !== null}
    <section class="worker-status">
      <dl>
        <dt>Status</dt>
        <dd>{status.state} ({status.stateCode})</dd>
        <dt>Pakete verarbeitet</dt>
        <dd>{status.packetsCompleted}</dd>
        <dt>Pakete fehlgeschlagen</dt>
        <dd>{status.packetsFailed}</dd>
      </dl>
    </section>

    <section class="worker-actions">
      <button class="btn btn--pause" type="button" onclick={() => { void sendControl('pause'); }}>
        Pausieren
      </button>
      <button class="btn btn--resume" type="button" onclick={() => { void sendControl('resume'); }}>
        Fortsetzen
      </button>
      <button class="btn btn--disable" type="button" onclick={() => { void sendControl('disable'); }}>
        Deaktivieren
      </button>
    </section>

    {#if actionMessage !== null}
      <p class="action-result">{actionMessage}</p>
    {/if}
  {/if}
</main>

<style>
  .embedded-worker-control {
    padding: 1rem;
  }

  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 0.25rem 1rem;
    margin-bottom: 1rem;
  }

  dt {
    font-weight: 600;
    color: #2c3e50;
  }

  dd {
    margin: 0;
    color: #555;
  }

  .worker-actions {
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
    margin-bottom: 1rem;
  }

  .btn {
    padding: 0.4rem 0.8rem;
    border: none;
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.9rem;
  }

  .btn--pause {
    background: #e67e22;
    color: #fff;
  }

  .btn--resume {
    background: #27ae60;
    color: #fff;
  }

  .btn--disable {
    background: #e74c3c;
    color: #fff;
  }

  .error {
    color: #e74c3c;
  }

  .action-result {
    color: #2c3e50;
    font-style: italic;
  }
</style>
