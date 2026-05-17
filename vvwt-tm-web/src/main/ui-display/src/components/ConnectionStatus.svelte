<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Subtle connection status indicator for the Gesamtübersicht display SPA (E07S06 AC7).
   *
   * Renders a small icon and optional label in the top-right corner of the viewport.
   * The indicator is visually subtle — it must not distract from the main tournament data.
   *
   * States:
   *   - connecting   — spinner; transition in progress
   *   - connected    — green dot; WebSocket is live
   *   - reconnecting — spinner; reconnecting after disconnect
   *   - polling      — amber dot; WebSocket unavailable, polling fallback active (AC9)
   *   - disconnected — red dot; completely disconnected (edge case, e.g. on destroy)
   *
   * All user-visible text uses svelte-i18n (AC10).
   */
  import { _ } from 'svelte-i18n';
  import type { ConnectionStatus } from '../lib/websocket.js';

  interface Props {
    status: ConnectionStatus;
  }

  const { status }: Props = $props();

  const labelKey: Record<ConnectionStatus, string> = {
    connecting:   'display.connection.connecting',
    connected:    'display.connection.connected',
    reconnecting: 'display.connection.reconnecting',
    polling:      'display.connection.polling',
    disconnected: 'display.connection.disconnected',
  };

  const dotClass: Record<ConnectionStatus, string> = {
    connecting:   'cs-dot cs-dot--spinning',
    connected:    'cs-dot cs-dot--green',
    reconnecting: 'cs-dot cs-dot--spinning',
    polling:      'cs-dot cs-dot--amber',
    disconnected: 'cs-dot cs-dot--red',
  };
</script>

<div
  class="connection-status"
  role="status"
  aria-live="polite"
  aria-label={$_(labelKey[status])}
  title={$_(labelKey[status])}
>
  <span class={dotClass[status]}></span>
</div>

<style>
  /*
   * E50S02: connection indicator is now in-flow inside display-header (flex row).
   * margin-left: auto pushes the indicator to the right side of the header band.
   * Removed: position:fixed, top, right, z-index — no longer needed as overlay.
   * All 5 connection-state styles (dots, spinner, colors) are preserved below.
   */
  .connection-status {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 0.35rem;
    opacity: 0.7;
    pointer-events: none; /* indicator only — not interactive */
  }

  .cs-dot {
    display: inline-block;
    width: 10px;
    height: 10px;
    border-radius: 50%;
  }

  .cs-dot--green {
    background: #27ae60;
  }

  .cs-dot--amber {
    background: #e67e22;
  }

  .cs-dot--red {
    background: #c0392b;
  }

  .cs-dot--spinning {
    background: transparent;
    border: 2px solid #7f8c8d;
    border-top-color: #2980b9;
    animation: cs-spin 0.8s linear infinite;
  }

  @keyframes cs-spin {
    to { transform: rotate(360deg); }
  }
</style>
