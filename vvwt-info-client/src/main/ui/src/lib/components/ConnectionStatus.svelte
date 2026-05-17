<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * ConnectionStatus component — displays network-error (AC14) and stale-data (AC15) indicators.
   * AC14: "Verbindung verloren — Wiederverbindung…" with data-testid="connection-lost-indicator".
   * AC15: "Daten möglicherweise veraltet" with data-testid="stale-data-indicator" (distinct from AC14).
   * AC16: all strings from de.ts; no inline literals in template.
   * Story: E38S08.
   */
  import de from '../i18n/de.js';

  interface Props {
    connectionLost: boolean;
    dataStale: boolean;
  }

  const { connectionLost, dataStale }: Props = $props();
</script>

{#if connectionLost}
  <div class="indicator connection-lost" data-testid="connection-lost-indicator">
    {de['connection.lost']}
  </div>
{/if}

{#if dataStale}
  <div class="indicator stale-data" data-testid="stale-data-indicator">
    {de['data.stale']}
  </div>
{/if}

<style>
  .indicator {
    padding: 0.5rem 1rem;
    border-radius: 4px;
    margin-bottom: 0.5rem;
    font-size: 0.9rem;
  }

  .connection-lost {
    background-color: #fef3c7;
    color: #92400e;
    border: 1px solid #fcd34d;
  }

  .stale-data {
    background-color: #eff6ff;
    color: #1e40af;
    border: 1px solid #bfdbfe;
  }
</style>
