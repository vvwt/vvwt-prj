<script lang="ts">
  /**
   * Device Management view for the Tournament Manager Admin SPA.
   *
   * Story E06S05 — AC1 (device list), AC2 (PIN lookup + assign), AC3 (unassign),
   * AC4 (field conflict dialog), AC5 (QR code), AC6 (real-time WebSocket updates),
   * AC7 (clear all), AC8 (navigation — wired via App.svelte), AC9 (error handling),
   * AC10 (auth — enforced by SecurityConfig), AC11 (i18n).
   *
   * QR code (AC5): rendered client-side using the `qrcode` npm package (browser build).
   * No native dependencies — pure JS QR generation.
   *
   * Real-time updates (AC6): subscribes to /topic/events via STOMP/SockJS on mount.
   * On DEVICE_REGISTERED event, refreshes the device list.
   */
  import { onMount, onDestroy } from 'svelte';
  import { _ } from 'svelte-i18n';
  import { Client as StompClient } from '@stomp/stompjs';
  import SockJS from 'sockjs-client';
  import * as QRCodeLib from 'qrcode';
  import {
    listDevices,
    findDeviceByPin,
    assignDevice,
    unassignDevice,
    clearAllDevices,
    type Device,
  } from '../stores/deviceStore.js';

  // ──────────────────────────────────────────────────────────────────────
  // State
  // ──────────────────────────────────────────────────────────────────────

  /** All registered devices for the current tenant (AC1). */
  let devices: Device[] = $state([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  /** PIN lookup state (AC2). */
  let pinInput = $state('');
  let lookupError = $state<string | null>(null);
  let lookedUpDevice = $state<Device | null>(null);
  let fieldInput = $state('');
  let assignError = $state<string | null>(null);

  /** Field conflict confirmation (AC4). */
  let conflictField = $state<number | null>(null);
  let showConflictDialog = $state(false);
  let pendingAssignDeviceId = $state<string | null>(null);

  /** Per-row unassign errors (AC3, AC9). */
  let unassignErrors = $state<Record<string, string>>({});

  /** QR code display (AC5). */
  let showQr = $state(false);
  let qrSvg = $state('');

  /** Clear all error (AC7, AC9). */
  let clearError = $state<string | null>(null);

  /** STOMP WebSocket client — disconnected on component destroy (AC6). */
  let stompClient: StompClient | null = null;

  // ──────────────────────────────────────────────────────────────────────
  // Lifecycle
  // ──────────────────────────────────────────────────────────────────────

  onMount(async () => {
    await loadDevices();
    connectWebSocket();
  });

  onDestroy(() => {
    if (stompClient) {
      stompClient.deactivate();
      stompClient = null;
    }
  });

  // ──────────────────────────────────────────────────────────────────────
  // Device list (AC1)
  // ──────────────────────────────────────────────────────────────────────

  async function loadDevices(): Promise<void> {
    loading = true;
    loadError = null;
    try {
      devices = await listDevices();
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // PIN lookup + assign (AC2, AC4)
  // ──────────────────────────────────────────────────────────────────────

  async function handlePinLookup(): Promise<void> {
    if (!pinInput.trim()) return;
    lookupError = null;
    lookedUpDevice = null;
    assignError = null;
    fieldInput = '';
    try {
      lookedUpDevice = await findDeviceByPin(pinInput.trim());
    } catch (e: unknown) {
      const status = (e as { status?: number }).status;
      if (status === 404) {
        lookupError = $_('devices.lookupError');
      } else {
        lookupError = $_('devices.lookupNetworkError');
      }
    }
  }

  async function handleAssign(): Promise<void> {
    if (!lookedUpDevice) return;
    const fieldNumber = parseInt(fieldInput, 10);
    if (isNaN(fieldNumber) || fieldNumber < 1) {
      assignError = $_('devices.assignError');
      return;
    }
    assignError = null;

    // Check whether this field is already occupied in the local device list (AC4)
    const occupant = devices.find(
      (d) => d.assignedField === fieldNumber && d.id !== lookedUpDevice!.id
    );
    if (occupant) {
      // Show confirmation dialog (AC4)
      conflictField = fieldNumber;
      pendingAssignDeviceId = lookedUpDevice.id;
      showConflictDialog = true;
      return;
    }

    await doAssign(lookedUpDevice.id, fieldNumber);
  }

  /** Confirms field conflict replacement — unassigns existing then assigns new (AC4). */
  async function handleConflictConfirm(): Promise<void> {
    showConflictDialog = false;
    if (pendingAssignDeviceId == null || conflictField == null) return;

    // Unassign the device currently occupying the field, then assign the new one
    const occupant = devices.find(
      (d) => d.assignedField === conflictField && d.id !== pendingAssignDeviceId
    );
    if (occupant) {
      try {
        await unassignDevice(occupant.id);
      } catch {
        // If unassign fails, proceed anyway — the server will enforce 409 on assign.
        // The assign call below will surface the error to the user.
      }
    }

    await doAssign(pendingAssignDeviceId, conflictField);
    conflictField = null;
    pendingAssignDeviceId = null;
  }

  function handleConflictCancel(): void {
    showConflictDialog = false;
    conflictField = null;
    pendingAssignDeviceId = null;
  }

  async function doAssign(deviceId: string, fieldNumber: number): Promise<void> {
    assignError = null;
    try {
      await assignDevice(deviceId, fieldNumber);
      // Reset PIN lookup form
      pinInput = '';
      lookedUpDevice = null;
      fieldInput = '';
      await loadDevices();
    } catch (e: unknown) {
      assignError = e instanceof Error ? e.message : $_('devices.assignError');
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // Unassign (AC3)
  // ──────────────────────────────────────────────────────────────────────

  async function handleUnassign(deviceId: string): Promise<void> {
    unassignErrors = { ...unassignErrors, [deviceId]: '' };
    try {
      await unassignDevice(deviceId);
      await loadDevices();
    } catch (e: unknown) {
      unassignErrors = {
        ...unassignErrors,
        [deviceId]: e instanceof Error ? e.message : $_('devices.unassignError'),
      };
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // QR code (AC5)
  // ──────────────────────────────────────────────────────────────────────

  /**
   * Generates and displays the QR code for the tablet registration URL.
   *
   * Uses QRCodeLib.create() (browser build of the `qrcode` package) to build the
   * QR matrix, then renders it as an SVG for display in the modal.
   * The registration URL is /score/register on the current origin.
   * Falls back to a text display if QR generation fails (AC5: QR is convenience only).
   */
  async function handleShowQr(): Promise<void> {
    const registrationUrl = window.location.origin + '/score/register';
    qrSvg = buildQrSvg(registrationUrl);
    showQr = true;
  }

  function handleHideQr(): void {
    showQr = false;
  }

  /**
   * Builds a QR code as an inline SVG string.
   *
   * Uses QRCodeLib.create() which returns a QR object with a `modules` BitMatrix.
   * Each dark module is rendered as an SVG `<rect>`.
   *
   * @param text the URL to encode
   * @returns SVG markup string
   */
  function buildQrSvg(text: string): string {
    try {
      // QRCodeLib.create is synchronous — no async needed
      const qr = (QRCodeLib as unknown as {
        create: (text: string, opts: { errorCorrectionLevel: string }) => {
          modules: { data: Uint8ClampedArray | boolean[]; size: number }
        }
      }).create(text, { errorCorrectionLevel: 'M' });

      const size = qr.modules.size;
      const data = qr.modules.data;
      const cellSize = 4;
      const padding = 8;
      const totalSize = size * cellSize + padding * 2;

      let rects = '';
      for (let row = 0; row < size; row++) {
        for (let col = 0; col < size; col++) {
          if (data[row * size + col]) {
            const x = padding + col * cellSize;
            const y = padding + row * cellSize;
            rects += `<rect x="${x}" y="${y}" width="${cellSize}" height="${cellSize}" fill="black"/>`;
          }
        }
      }

      return (
        `<svg xmlns="http://www.w3.org/2000/svg" width="${totalSize}" height="${totalSize}" ` +
        `viewBox="0 0 ${totalSize} ${totalSize}">` +
        `<rect width="${totalSize}" height="${totalSize}" fill="white"/>` +
        rects +
        `</svg>`
      );
    } catch {
      // Fallback: show the URL as text (AC5: QR is for convenience only)
      const safe = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      return (
        `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="60">` +
        `<rect width="300" height="60" fill="white" stroke="#ccc"/>` +
        `<text x="10" y="35" font-size="11" fill="#333">${safe}</text>` +
        `</svg>`
      );
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // Clear all (AC7)
  // ──────────────────────────────────────────────────────────────────────

  async function handleClearAll(): Promise<void> {
    if (!confirm($_('devices.clearAllConfirm'))) return;
    clearError = null;
    try {
      await clearAllDevices();
      await loadDevices();
    } catch (e: unknown) {
      clearError = e instanceof Error ? e.message : $_('devices.clearAllError');
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // Real-time updates via WebSocket (AC6)
  // ──────────────────────────────────────────────────────────────────────

  /**
   * Connects to the STOMP/SockJS WebSocket at /ws and subscribes to /topic/events.
   * On receiving a DEVICE_REGISTERED event, refreshes the device list (AC6).
   *
   * Uses @stomp/stompjs with SockJS transport. The STOMP client is activated on
   * mount and deactivated on component destroy to avoid memory leaks.
   */
  function connectWebSocket(): void {
    const client = new StompClient({
      // AC6: SockJS transport for connection resilience (SockJS fallback: long-polling)
      webSocketFactory: () => new SockJS('/ws') as unknown as WebSocket,
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe('/topic/events', (frame) => {
          try {
            const msg = JSON.parse(frame.body) as { eventType?: string };
            if (msg.eventType === 'DEVICE_REGISTERED') {
              // AC6: refresh the device list when the server signals a new registration
              loadDevices();
            }
          } catch {
            // Malformed message — ignore silently
          }
        });
      },
      onStompError: (frame) => {
        // AC9: STOMP protocol errors logged; list remains functional without real-time
        console.warn('[devices-ws] STOMP error:', frame.headers?.message);
      },
    });
    client.activate();
    stompClient = client;
  }

  // ──────────────────────────────────────────────────────────────────────
  // Formatting helpers
  // ──────────────────────────────────────────────────────────────────────

  function formatLastSeen(ts: string | null): string {
    if (!ts) return '—';
    try {
      return new Date(ts).toLocaleString();
    } catch {
      return ts;
    }
  }
</script>

<!-- AC8: navigation link is provided via App.svelte and Home.svelte -->
<main class="devices">
  <div class="devices__header">
    <h1>{$_('devices.title')}</h1>
    <div class="devices__header-actions">
      <!-- AC5: QR code display -->
      <button class="btn btn--secondary" onclick={handleShowQr}>
        {$_('devices.showQrButton')}
      </button>
      <!-- AC7: clear all devices -->
      <button class="btn btn--danger" onclick={handleClearAll}>
        {$_('devices.clearAllButton')}
      </button>
    </div>
  </div>

  <!-- QR code modal (AC5) -->
  {#if showQr}
    <div class="devices__overlay" role="dialog" aria-modal="true"
         aria-label={$_('devices.qrTitle')}>
      <div class="devices__modal">
        <h2>{$_('devices.qrTitle')}</h2>
        <p class="devices__qr-url">{window.location.origin + '/score/register'}</p>
        <!-- SVG generated by buildQrSvg — no user input involved -->
        <!-- eslint-disable-next-line svelte/no-at-html-tags -->
        {@html qrSvg}
        <button class="btn btn--secondary" onclick={handleHideQr}>
          {$_('devices.hideQrButton')}
        </button>
      </div>
    </div>
  {/if}

  <!-- Field conflict confirmation dialog (AC4) -->
  {#if showConflictDialog}
    <div class="devices__overlay" role="dialog" aria-modal="true">
      <div class="devices__modal">
        <p class="devices__conflict-msg">
          {$_('devices.fieldConflictConfirm', { values: { field: conflictField } })}
        </p>
        <div class="devices__modal-actions">
          <button class="btn btn--danger" onclick={handleConflictConfirm}>
            {$_('teams.saveButton')}
          </button>
          <button class="btn btn--secondary" onclick={handleConflictCancel}>
            {$_('teams.cancelButton')}
          </button>
        </div>
      </div>
    </div>
  {/if}

  <!-- Clear all error (AC7, AC9) -->
  {#if clearError}
    <p class="devices__error">{clearError}</p>
  {/if}

  <!-- PIN lookup + assign form (AC2) -->
  <section class="devices__lookup">
    <h2 class="devices__lookup-title">{$_('devices.pinLookupTitle')}</h2>
    <div class="devices__lookup-row">
      <label for="pin-input">{$_('devices.pinInputLabel')}</label>
      <input
        id="pin-input"
        type="text"
        class="devices__text-input"
        placeholder={$_('devices.pinInputPlaceholder')}
        bind:value={pinInput}
        onkeydown={(e) => { if (e.key === 'Enter') handlePinLookup(); }}
      />
      <button class="btn btn--primary" onclick={handlePinLookup}>
        {$_('devices.lookupButton')}
      </button>
    </div>

    {#if lookupError}
      <p class="devices__error">{lookupError}</p>
    {/if}

    {#if lookedUpDevice}
      <div class="devices__assign-row">
        <span class="devices__lookup-result">
          PIN: <strong>{lookedUpDevice.pin}</strong>
          &nbsp;&mdash;&nbsp;
          {$_(`devices.status.${lookedUpDevice.status}`, { default: lookedUpDevice.status })}
        </span>
        <label for="field-input">{$_('devices.assignFieldLabel')}</label>
        <input
          id="field-input"
          type="number"
          class="devices__text-input devices__text-input--short"
          min="1"
          placeholder={$_('devices.assignFieldPlaceholder')}
          bind:value={fieldInput}
          onkeydown={(e) => { if (e.key === 'Enter') handleAssign(); }}
        />
        <button class="btn btn--primary" onclick={handleAssign}>
          {$_('devices.assignButton')}
        </button>
      </div>
      {#if assignError}
        <p class="devices__error">{assignError}</p>
      {/if}
    {/if}
  </section>

  <!-- Device list (AC1) -->
  {#if loading}
    <p class="devices__loading">…</p>
  {:else if loadError}
    <p class="devices__error">{loadError}</p>
  {:else if devices.length === 0}
    <p class="devices__empty">{$_('devices.empty')}</p>
  {:else}
    <table class="devices__table">
      <thead>
        <tr>
          <th>{$_('devices.columns.pin')}</th>
          <th>{$_('devices.columns.deviceType')}</th>
          <th>{$_('devices.columns.assignedField')}</th>
          <th>{$_('devices.columns.status')}</th>
          <th>{$_('devices.columns.lastSeen')}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {#each devices as device (device.id)}
          <tr>
            <td><strong>{device.pin}</strong></td>
            <td>{$_(`devices.deviceType.${device.deviceType}`, { default: device.deviceType })}</td>
            <td>
              {device.assignedField != null
                ? device.assignedField
                : $_('devices.unassigned')}
            </td>
            <td>{$_(`devices.status.${device.status}`, { default: device.status })}</td>
            <td>{formatLastSeen(device.lastSeenAt)}</td>
            <td class="devices__row-actions">
              {#if device.status === 'ASSIGNED'}
                <!-- AC3: unassign button for assigned devices -->
                <button
                  class="btn btn--secondary btn--sm"
                  onclick={() => handleUnassign(device.id)}
                >
                  {$_('devices.unassignButton')}
                </button>
              {/if}
              {#if unassignErrors[device.id]}
                <span class="devices__row-error">{unassignErrors[device.id]}</span>
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</main>

<style>
  .devices {
    padding: 2rem;
    font-family: sans-serif;
  }

  .devices__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 1.5rem;
  }

  .devices__header h1 {
    margin: 0;
  }

  .devices__header-actions {
    display: flex;
    gap: 0.5rem;
  }

  /* PIN lookup section */
  .devices__lookup {
    background: #f9f9f9;
    border: 1px solid #e0e0e0;
    border-radius: 6px;
    padding: 1rem 1.5rem;
    margin-bottom: 1.5rem;
  }

  .devices__lookup-title {
    margin-top: 0;
    font-size: 1.1rem;
  }

  .devices__lookup-row,
  .devices__assign-row {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    flex-wrap: wrap;
    margin-top: 0.5rem;
  }

  .devices__text-input {
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    padding: 0.4rem 0.6rem;
    font-size: 0.95rem;
    width: 10rem;
  }

  .devices__text-input--short {
    width: 6rem;
  }

  .devices__lookup-result {
    font-size: 0.95rem;
  }

  /* Device table */
  .devices__table {
    width: 100%;
    border-collapse: collapse;
  }

  .devices__table th,
  .devices__table td {
    text-align: left;
    padding: 0.5rem 0.75rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .devices__table th {
    font-weight: 600;
    background: #f5f5f5;
  }

  .devices__row-actions {
    white-space: nowrap;
    display: flex;
    gap: 0.4rem;
    align-items: center;
  }

  .devices__row-error {
    color: #c0392b;
    font-size: 0.8rem;
  }

  .devices__error {
    color: #c0392b;
    margin-bottom: 1rem;
  }

  .devices__loading,
  .devices__empty {
    color: #666;
    margin-top: 1rem;
  }

  /* Overlay + modal (AC4 conflict dialog, AC5 QR code) */
  .devices__overlay {
    position: fixed;
    top: 0;
    left: 0;
    width: 100vw;
    height: 100vh;
    background: rgba(0, 0, 0, 0.5);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
  }

  .devices__modal {
    background: white;
    border-radius: 8px;
    padding: 2rem;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 1rem;
    max-width: 420px;
    width: 100%;
  }

  .devices__modal h2 {
    margin: 0;
  }

  .devices__qr-url {
    font-size: 0.85rem;
    color: #555;
    word-break: break-all;
    margin: 0;
  }

  .devices__conflict-msg {
    text-align: center;
    margin: 0;
  }

  .devices__modal-actions {
    display: flex;
    gap: 0.75rem;
  }

  /* Button styles (mirrors other views in this SPA) */
  .btn {
    cursor: pointer;
    border: none;
    border-radius: 4px;
    padding: 0.5rem 1rem;
    font-size: 0.9rem;
  }

  .btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }

  .btn--danger {
    background: #e74c3c;
    color: #fff;
  }

  .btn--sm {
    padding: 0.25rem 0.6rem;
    font-size: 0.8rem;
  }
</style>
