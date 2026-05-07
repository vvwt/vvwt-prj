<script lang="ts">
  /**
   * Device Management view for the Tournament Manager Admin SPA.
   *
   * Story E06S05 — AC1 (device list), AC3 (unassign), AC4 (field conflict dialog),
   * AC5 (QR code), AC6 (real-time WebSocket updates), AC7 (clear all),
   * AC8 (navigation — wired via App.svelte), AC9 (error handling),
   * AC10 (auth — enforced by SecurityConfig), AC11 (i18n).
   *
   * Story E07S03 — AC1 (type filter tabs), AC2 (display device list integration),
   * AC3 (configure display device modal), AC4 (remove device dialog), AC5 (display
   * device limit indicator), AC6 (real-time DEVICE_REMOVED events), AC7 (scoring
   * tablet workflow preserved), AC8 (error handling), AC9 (i18n), AC10 (security).
   *
   * Story E49S01 — inline-row PIN assignment for SCORING_TABLET, device name column,
   * PIN-lock indicator + reset, rename for SCORING_TABLET.
   * E49S01 AC3: PIN column removed; AC8: PIN lookup section removed.
   *
   * QR code (E06S05 AC5): rendered client-side using the `qrcode` npm package.
   * No native dependencies — pure JS QR generation.
   *
   * Real-time updates (E06S05 AC6, E07S03 AC6): subscribes to /topic/events via
   * STOMP/SockJS on mount. Reacts to DEVICE_REGISTERED and DEVICE_REMOVED events.
   *
   * Filter (E07S03 AC1): three tabs — "all", "scoring", "display".
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { Client as StompClient } from '@stomp/stompjs';
  import SockJS from 'sockjs-client';
  import * as QRCodeLib from 'qrcode';
  import {
    listDevices,
    assignDevice,
    unassignDevice,
    clearAllDevices,
    configureDisplayDevice,
    removeDevice,
    getDisplayLimit,
    resetPinLock,
    renameDevice,
    type Device,
  } from '../stores/deviceStore.js';

  // ──────────────────────────────────────────────────────────────────────
  // Constants
  // ──────────────────────────────────────────────────────────────────────

  const DEVICE_TYPE_SCORING = 'SCORING_TABLET';
  const DEVICE_TYPE_DISPLAY = 'DISPLAY';
  /** Single supported display schema value (E07S03 AC3). */
  const DISPLAY_SCHEMA_OVERVIEW = '{"display_schema":"OVERVIEW"}';

  type FilterType = 'all' | 'scoring' | 'display';

  // ──────────────────────────────────────────────────────────────────────
  // State
  // ──────────────────────────────────────────────────────────────────────

  /** All registered devices for the current tenant (E06S05 AC1). */
  let devices: Device[] = $state([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  /** Active filter tab (E07S03 AC1). */
  let activeFilter = $state<FilterType>('all');

  /** Configured DISPLAY device limit from backend (E07S03 AC5). */
  let maxDisplayCount = $state(10);

  /** Per-row inline assign state: keyed by device id (E49S01 inline-row). */
  let rowPinInputs = $state<Record<string, string>>({});
  let rowFieldInputs = $state<Record<string, string>>({});
  let rowAssignErrors = $state<Record<string, string>>({});

  /** Field conflict confirmation (E06S05 AC4). */
  let conflictField = $state<number | null>(null);
  let showConflictDialog = $state(false);
  let pendingAssignDeviceId = $state<string | null>(null);
  let pendingAssignPin = $state<string | null>(null);

  /** Per-row rename state (E49S01 AC11). */
  let renamingDeviceId = $state<string | null>(null);
  let renameInput = $state('');
  let renameErrors = $state<Record<string, string>>({});

  /** Per-row unassign errors (E06S05 AC3, AC9). */
  let unassignErrors = $state<Record<string, string>>({});

  /** QR code display (E06S05 AC5). */
  let showQr = $state(false);
  let qrSvg = $state('');

  /** Clear all error (E06S05 AC7, AC9). */
  let clearError = $state<string | null>(null);

  /** Configure display device modal state (E07S03 AC3). */
  let showConfigureModal = $state(false);
  let configuringDevice = $state<Device | null>(null);
  let configNameInput = $state('');
  let configSaveError = $state<string | null>(null);
  let configSaving = $state(false);

  /** Remove device confirmation dialog (E07S03 AC4). */
  let showRemoveDialog = $state(false);
  let removingDevice = $state<Device | null>(null);
  let removeErrors = $state<Record<string, string>>({});
  let removeInProgress = $state(false);

  /** STOMP WebSocket client — disconnected on component destroy (E06S05 AC6). */
  let stompClient: StompClient | null = null;

  // ──────────────────────────────────────────────────────────────────────
  // Derived counts (E07S03 AC1, AC5)
  // ──────────────────────────────────────────────────────────────────────

  let totalCount = $derived(devices.length);
  let scoringCount = $derived(devices.filter((d) => d.deviceType === DEVICE_TYPE_SCORING).length);
  let displayCount = $derived(devices.filter((d) => d.deviceType === DEVICE_TYPE_DISPLAY).length);

  let filteredDevices = $derived(
    activeFilter === 'scoring'
      ? devices.filter((d) => d.deviceType === DEVICE_TYPE_SCORING)
      : activeFilter === 'display'
        ? devices.filter((d) => d.deviceType === DEVICE_TYPE_DISPLAY)
        : devices
  );

  // ──────────────────────────────────────────────────────────────────────
  // Lifecycle
  // ──────────────────────────────────────────────────────────────────────

  onMount(async () => {
    // E47S01 AC3/AC4/AC8: register title and actions (QR=secondary, clearAll=danger) in persistent header
    pageHeader.set({
      title: get(_)('devices.title'),
      backTo: null,
      tournamentId: null,
      actions: [
        {
          label: get(_)('devices.showQrButton'),
          ariaLabel: get(_)('devices.showQrButton'),
          handler: handleShowQr,
          variant: 'secondary',
        },
        {
          label: get(_)('devices.clearAllButton'),
          ariaLabel: get(_)('devices.clearAllButton'),
          handler: handleClearAll,
          variant: 'danger',
        },
      ],
    });
    await Promise.all([loadDevices(), loadDisplayLimit()]);
    connectWebSocket();
  });

  onDestroy(() => {
    resetPageHeader();
    if (stompClient) {
      stompClient.deactivate();
      stompClient = null;
    }
  });

  // ──────────────────────────────────────────────────────────────────────
  // Device list (E06S05 AC1)
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
  // Display device limit (E07S03 AC5)
  // ──────────────────────────────────────────────────────────────────────

  async function loadDisplayLimit(): Promise<void> {
    try {
      maxDisplayCount = await getDisplayLimit();
    } catch {
      // Non-critical — keep default value; limit indicator still shows
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // Inline-row assign (E49S01 — PIN out-of-band)
  // ──────────────────────────────────────────────────────────────────────

  async function handleRowAssign(device: Device): Promise<void> {
    const pin = rowPinInputs[device.id]?.trim() ?? null;
    const fieldStr = rowFieldInputs[device.id]?.trim() ?? '';
    const fieldNumber = parseInt(fieldStr, 10);
    if (isNaN(fieldNumber) || fieldNumber < 1) {
      rowAssignErrors = { ...rowAssignErrors, [device.id]: $_('devices.assignError') };
      return;
    }
    rowAssignErrors = { ...rowAssignErrors, [device.id]: '' };

    // Check whether this field is already occupied in the local device list (AC4)
    const occupant = devices.find(
      (d) => d.assignedField === fieldNumber && d.id !== device.id
    );
    if (occupant) {
      conflictField = fieldNumber;
      pendingAssignDeviceId = device.id;
      pendingAssignPin = device.deviceType === 'SCORING_TABLET' ? pin : null;
      showConflictDialog = true;
      return;
    }

    await doAssign(device.id, fieldNumber, device.deviceType === 'SCORING_TABLET' ? pin : null);
  }

  /** Confirms field conflict replacement — unassigns existing then assigns new (E06S05 AC4). */
  async function handleConflictConfirm(): Promise<void> {
    showConflictDialog = false;
    if (pendingAssignDeviceId == null || conflictField == null) return;

    const occupant = devices.find(
      (d) => d.assignedField === conflictField && d.id !== pendingAssignDeviceId
    );
    if (occupant) {
      try {
        await unassignDevice(occupant.id);
      } catch {
        // If unassign fails, proceed — server will enforce 409 on assign.
      }
    }

    await doAssign(pendingAssignDeviceId, conflictField, pendingAssignPin);
    conflictField = null;
    pendingAssignDeviceId = null;
    pendingAssignPin = null;
  }

  function handleConflictCancel(): void {
    showConflictDialog = false;
    conflictField = null;
    pendingAssignDeviceId = null;
    pendingAssignPin = null;
  }

  async function doAssign(deviceId: string, fieldNumber: number, pin: string | null): Promise<void> {
    rowAssignErrors = { ...rowAssignErrors, [deviceId]: '' };
    try {
      await assignDevice(deviceId, fieldNumber, pin);
      rowPinInputs = { ...rowPinInputs, [deviceId]: '' };
      rowFieldInputs = { ...rowFieldInputs, [deviceId]: '' };
      await loadDevices();
    } catch (e: unknown) {
      const status = (e as { status?: number }).status;
      let msg = e instanceof Error ? e.message : $_('devices.assignError');
      if (status === 403) msg = $_('devices.scoringTablet.pinMismatch');
      if (status === 423) msg = $_('devices.scoringTablet.pinLocked');
      if (status === 422) msg = $_('devices.scoringTablet.pinMissing');
      rowAssignErrors = { ...rowAssignErrors, [deviceId]: msg };
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // PIN lock reset (E49S01 AC6)
  // ──────────────────────────────────────────────────────────────────────

  async function handleResetPinLock(device: Device): Promise<void> {
    if (!confirm($_('devices.scoringTablet.pinLockResetConfirm'))) return;
    try {
      await resetPinLock(device.id);
      await loadDevices();
    } catch (e: unknown) {
      rowAssignErrors = {
        ...rowAssignErrors,
        [device.id]: e instanceof Error ? e.message : $_('devices.scoringTablet.pinLockResetError'),
      };
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // Rename (E49S01 AC11)
  // ──────────────────────────────────────────────────────────────────────

  function startRename(device: Device): void {
    renamingDeviceId = device.id;
    renameInput = device.deviceName ?? '';
  }

  function cancelRename(): void {
    renamingDeviceId = null;
    renameInput = '';
  }

  async function confirmRename(device: Device): Promise<void> {
    if (!renameInput.trim()) return;
    try {
      await renameDevice(device.id, renameInput.trim());
      renamingDeviceId = null;
      renameInput = '';
      await loadDevices();
    } catch (e: unknown) {
      renameErrors = {
        ...renameErrors,
        [device.id]: e instanceof Error ? e.message : $_('devices.scoringTablet.renameError'),
      };
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // Unassign (E06S05 AC3)
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
  // QR code (E06S05 AC5)
  // ──────────────────────────────────────────────────────────────────────

  async function handleShowQr(): Promise<void> {
    const registrationUrl = window.location.origin + '/score/register';
    qrSvg = buildQrSvg(registrationUrl);
    showQr = true;
  }

  function handleHideQr(): void {
    showQr = false;
  }

  function buildQrSvg(text: string): string {
    try {
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
  // Clear all (E06S05 AC7)
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
  // Configure display device (E07S03 AC3)
  // ──────────────────────────────────────────────────────────────────────

  function openConfigureModal(device: Device): void {
    configuringDevice = device;
    configNameInput = device.deviceName ?? '';
    configSaveError = null;
    showConfigureModal = true;
  }

  function closeConfigureModal(): void {
    showConfigureModal = false;
    configuringDevice = null;
    configNameInput = '';
    configSaveError = null;
  }

  async function handleConfigureSave(): Promise<void> {
    if (!configuringDevice) return;
    if (!configNameInput.trim()) {
      configSaveError = $_('devices.displayDevice.saveError');
      return;
    }
    configSaving = true;
    configSaveError = null;
    try {
      await configureDisplayDevice(configuringDevice.id, configNameInput.trim(), DISPLAY_SCHEMA_OVERVIEW);
      await loadDevices();
      closeConfigureModal();
    } catch (e: unknown) {
      configSaveError = e instanceof Error ? e.message : $_('devices.displayDevice.saveError');
    } finally {
      configSaving = false;
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // Remove device (E07S03 AC4)
  // ──────────────────────────────────────────────────────────────────────

  function openRemoveDialog(device: Device): void {
    removingDevice = device;
    showRemoveDialog = true;
  }

  function closeRemoveDialog(): void {
    showRemoveDialog = false;
    removingDevice = null;
  }

  async function handleRemoveConfirm(): Promise<void> {
    if (!removingDevice) return;
    removeInProgress = true;
    const targetId = removingDevice.id;
    try {
      await removeDevice(targetId);
      showRemoveDialog = false;
      removingDevice = null;
      await loadDevices();
    } catch (e: unknown) {
      removeErrors = {
        ...removeErrors,
        [targetId]: e instanceof Error ? e.message : $_('devices.removeError'),
      };
      showRemoveDialog = false;
      removingDevice = null;
    } finally {
      removeInProgress = false;
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // Real-time updates via WebSocket (E06S05 AC6, E07S03 AC6)
  // ──────────────────────────────────────────────────────────────────────

  function connectWebSocket(): void {
    const client = new StompClient({
      webSocketFactory: () => new SockJS('/ws') as unknown as WebSocket,
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe('/topic/events', (frame) => {
          try {
            const msg = JSON.parse(frame.body) as { eventType?: string };
            if (msg.eventType === 'DEVICE_REGISTERED' || msg.eventType === 'DEVICE_REMOVED') {
              // E06S05 AC6 + E07S03 AC6: refresh list on registration or removal
              loadDevices();
            }
          } catch {
            // Malformed message — ignore silently
          }
        });
      },
      onStompError: (frame) => {
        console.warn('[devices-ws] STOMP error:', frame.headers?.message);
      },
    });
    client.activate();
    stompClient = client;
  }

  // ──────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────

  function formatRegisteredAt(ts: string | null): string {
    if (!ts) return '—';
    try {
      return new Date(ts).toLocaleString();
    } catch {
      return ts;
    }
  }

  /** Returns a human-readable config status label for a DISPLAY device (E07S03 AC2). */
  function configStatus(device: Device): string {
    if (device.deviceType !== DEVICE_TYPE_DISPLAY) return '';
    return device.configuration
      ? $_('devices.displayDevice.configuredStatus')
      : $_('devices.displayDevice.pendingStatus');
  }

  /** Returns the display name for a DISPLAY device (E07S03 AC2). */
  function displayName(device: Device): string {
    return device.deviceName ?? $_('devices.displayDevice.unnamed');
  }
</script>

<!-- E06S05 AC8: navigation link provided via App.svelte -->
<main class="devices">

  <!-- QR code modal (E06S05 AC5) -->
  {#if showQr}
    <div class="devices__overlay" role="dialog" aria-modal="true"
         aria-label={$_('devices.qrTitle')}>
      <div class="devices__modal">
        <h2>{$_('devices.qrTitle')}</h2>
        <p class="devices__qr-url">{window.location.origin + '/score/register'}</p>
        <!-- eslint-disable-next-line svelte/no-at-html-tags -->
        {@html qrSvg}
        <button class="btn btn--secondary" onclick={handleHideQr}>
          {$_('devices.hideQrButton')}
        </button>
      </div>
    </div>
  {/if}

  <!-- Field conflict confirmation dialog (E06S05 AC4) -->
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

  <!-- Configure display device modal (E07S03 AC3) -->
  {#if showConfigureModal && configuringDevice}
    <div class="devices__overlay" role="dialog" aria-modal="true"
         aria-label={$_('devices.displayDevice.configureButton')}>
      <div class="devices__modal devices__modal--configure">
        <h2>{$_('devices.displayDevice.configureButton')}</h2>
        <div class="devices__form-row">
          <label for="config-name-input">{$_('devices.displayDevice.nameLabel')}</label>
          <input
            id="config-name-input"
            type="text"
            class="devices__text-input devices__text-input--wide"
            placeholder={$_('devices.displayDevice.namePlaceholder')}
            bind:value={configNameInput}
            onkeydown={(e) => { if (e.key === 'Enter') handleConfigureSave(); }}
          />
        </div>
        <div class="devices__form-row">
          <label for="config-schema-select">{$_('devices.displayDevice.schemaLabel')}</label>
          <!-- Only one schema option in V1; disabled to prevent future confusion (E07S03 AC3) -->
          <select id="config-schema-select" class="devices__select" disabled>
            <option value="OVERVIEW">{$_('devices.displayDevice.schemaOption')}</option>
          </select>
        </div>
        {#if configSaveError}
          <p class="devices__error">{configSaveError}</p>
        {/if}
        <div class="devices__modal-actions">
          <button class="btn btn--primary" onclick={handleConfigureSave} disabled={configSaving}>
            {$_('devices.displayDevice.saveButton')}
          </button>
          <button class="btn btn--secondary" onclick={closeConfigureModal} disabled={configSaving}>
            {$_('devices.displayDevice.cancelButton')}
          </button>
        </div>
      </div>
    </div>
  {/if}

  <!-- Remove device confirmation dialog (E07S03 AC4) -->
  {#if showRemoveDialog && removingDevice}
    <div class="devices__overlay" role="dialog" aria-modal="true">
      <div class="devices__modal">
        <p class="devices__conflict-msg">
          {$_('devices.removeConfirm', { values: { name: removingDevice.deviceType === DEVICE_TYPE_DISPLAY ? displayName(removingDevice) : (removingDevice.deviceName ?? removingDevice.id) } })}
        </p>
        <div class="devices__modal-actions">
          <button class="btn btn--danger" onclick={handleRemoveConfirm} disabled={removeInProgress}>
            {$_('devices.removeButton')}
          </button>
          <button class="btn btn--secondary" onclick={closeRemoveDialog} disabled={removeInProgress}>
            {$_('tournamentForm.cancelButton')}
          </button>
        </div>
      </div>
    </div>
  {/if}

  <!-- Clear all error (E06S05 AC7, AC9) -->
  {#if clearError}
    <p class="devices__error">{clearError}</p>
  {/if}

  <!-- Filter tabs (E07S03 AC1) -->
  <div class="devices__filter-tabs" role="tablist">
    <button
      role="tab"
      aria-selected={activeFilter === 'all'}
      class="devices__tab"
      class:devices__tab--active={activeFilter === 'all'}
      onclick={() => (activeFilter = 'all')}
    >
      {$_('devices.filter.all')} ({totalCount})
    </button>
    <button
      role="tab"
      aria-selected={activeFilter === 'scoring'}
      class="devices__tab"
      class:devices__tab--active={activeFilter === 'scoring'}
      onclick={() => (activeFilter = 'scoring')}
    >
      {$_('devices.filter.scoringTablets')} ({scoringCount})
    </button>
    <button
      role="tab"
      aria-selected={activeFilter === 'display'}
      class="devices__tab"
      class:devices__tab--active={activeFilter === 'display'}
      onclick={() => (activeFilter = 'display')}
    >
      {$_('devices.filter.displayDevices')} ({displayCount})
    </button>
  </div>

  <!-- Display device limit indicator (E07S03 AC5) -->
  {#if activeFilter === 'display' || activeFilter === 'all'}
    <div class="devices__limit-bar" class:devices__limit-bar--full={displayCount >= maxDisplayCount}>
      {$_('devices.displayDevice.limitIndicator', { values: { current: displayCount, max: maxDisplayCount } })}
      {#if displayCount >= maxDisplayCount}
        &nbsp;— {$_('devices.displayDevice.limitReached')}
      {/if}
    </div>
  {/if}

  <!-- Device list (E06S05 AC1, E07S03 AC2) -->
  {#if loading}
    <p class="devices__loading">…</p>
  {:else if loadError}
    <p class="devices__error">{loadError}</p>
  {:else if filteredDevices.length === 0}
    <p class="devices__empty">{$_('devices.empty')}</p>
  {:else}
    <table class="devices__table">
      <thead>
        <tr>
          <!-- Name column for all devices (E49S01 AC3: PIN column removed) -->
          <th>{$_('devices.columns.name')}</th>
          <th>{$_('devices.columns.deviceType')}</th>
          {#if activeFilter !== 'display'}
            <th>{$_('devices.columns.assignedField')}</th>
          {/if}
          <th>{$_('devices.columns.status')}</th>
          {#if activeFilter === 'display' || activeFilter === 'all'}
            <th>{$_('devices.columns.configStatus')}</th>
          {/if}
          <th>{$_('devices.columns.registeredAt')}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {#each filteredDevices as device (device.id)}
          <tr>
            <!-- Device name cell (E49S01 AC1/AC3) -->
            <td>
              {#if device.deviceType === DEVICE_TYPE_DISPLAY}
                <strong>{displayName(device)}</strong>
              {:else if renamingDeviceId === device.id}
                <!-- Inline rename input (E49S01 AC11) -->
                <div class="devices__inline-row">
                  <input
                    type="text"
                    class="devices__text-input"
                    bind:value={renameInput}
                    onkeydown={(e) => {
                      if (e.key === 'Enter') confirmRename(device);
                      if (e.key === 'Escape') cancelRename();
                    }}
                  />
                  <button class="btn btn--primary btn--sm" onclick={() => confirmRename(device)}>
                    {$_('devices.scoringTablet.renameButton')}
                  </button>
                  <button class="btn btn--secondary btn--sm" onclick={cancelRename}>
                    {$_('teams.cancelButton')}
                  </button>
                  {#if renameErrors[device.id]}
                    <span class="devices__row-error">{renameErrors[device.id]}</span>
                  {/if}
                </div>
              {:else}
                <strong>{device.deviceName ?? '—'}</strong>
                <button
                  class="btn btn--secondary btn--sm devices__rename-btn"
                  onclick={() => startRename(device)}
                  title={$_('devices.scoringTablet.renameButton')}
                >
                  ✏
                </button>
              {/if}
            </td>

            <!-- Device type badge -->
            <td>
              <span class="devices__type-badge devices__type-badge--{device.deviceType.toLowerCase()}">
                {$_(`devices.deviceType.${device.deviceType}`, { default: device.deviceType })}
              </span>
            </td>

            <!-- Assigned field + inline assign row — only for scoring context -->
            {#if activeFilter !== 'display'}
              <td>
                {#if device.deviceType === DEVICE_TYPE_SCORING && device.status !== 'ASSIGNED'}
                  <!-- Inline-row PIN + field assignment (E49S01 AC5/AC8) -->
                  <div class="devices__inline-row">
                    <input
                      type="text"
                      class="devices__text-input devices__text-input--short"
                      placeholder={$_('devices.scoringTablet.pinInputPlaceholder')}
                      aria-label={$_('devices.scoringTablet.pinInputLabel')}
                      bind:value={rowPinInputs[device.id]}
                    />
                    <input
                      type="number"
                      class="devices__text-input devices__text-input--short"
                      min="1"
                      placeholder={$_('devices.assignFieldPlaceholder')}
                      aria-label={$_('devices.assignFieldLabel')}
                      bind:value={rowFieldInputs[device.id]}
                      onkeydown={(e) => { if (e.key === 'Enter') handleRowAssign(device); }}
                    />
                    <button class="btn btn--primary btn--sm" onclick={() => handleRowAssign(device)}>
                      {$_('devices.assignButton')}
                    </button>
                  </div>
                  {#if rowAssignErrors[device.id]}
                    <span class="devices__row-error">{rowAssignErrors[device.id]}</span>
                  {/if}
                {:else if device.deviceType === DEVICE_TYPE_DISPLAY && device.status !== 'ASSIGNED'}
                  <!-- Display devices: field input only, no PIN -->
                  <div class="devices__inline-row">
                    <input
                      type="number"
                      class="devices__text-input devices__text-input--short"
                      min="1"
                      placeholder={$_('devices.assignFieldPlaceholder')}
                      aria-label={$_('devices.assignFieldLabel')}
                      bind:value={rowFieldInputs[device.id]}
                      onkeydown={(e) => { if (e.key === 'Enter') handleRowAssign(device); }}
                    />
                    <button class="btn btn--primary btn--sm" onclick={() => handleRowAssign(device)}>
                      {$_('devices.assignButton')}
                    </button>
                  </div>
                  {#if rowAssignErrors[device.id]}
                    <span class="devices__row-error">{rowAssignErrors[device.id]}</span>
                  {/if}
                {:else}
                  {device.assignedField != null
                    ? device.assignedField
                    : $_('devices.unassigned')}
                {/if}
              </td>
            {/if}

            <!-- Status (+ PIN lock indicator for E49S01 AC6) -->
            <td>
              {$_(`devices.status.${device.status}`, { default: device.status })}
            </td>

            <!-- Config status — display + all views (E07S03 AC2) -->
            {#if activeFilter === 'display' || activeFilter === 'all'}
              <td>
                {#if device.deviceType === DEVICE_TYPE_DISPLAY}
                  <span class="devices__config-status" class:devices__config-status--ok={!!device.configuration}>
                    {configStatus(device)}
                  </span>
                {:else}
                  <span class="devices__config-status devices__config-status--na">—</span>
                {/if}
              </td>
            {/if}

            <!-- Registered at -->
            <td>{formatRegisteredAt(device.registeredAt)}</td>

            <!-- Row actions -->
            <td class="devices__row-actions">
              {#if device.deviceType === DEVICE_TYPE_DISPLAY}
                <!-- Configure button for display devices (E07S03 AC3) -->
                <button
                  class="btn btn--secondary btn--sm"
                  onclick={() => openConfigureModal(device)}
                >
                  {$_('devices.displayDevice.configureButton')}
                </button>
              {/if}

              {#if device.status === 'ASSIGNED'}
                <!-- Unassign button for assigned devices (E06S05 AC3) -->
                <button
                  class="btn btn--secondary btn--sm"
                  onclick={() => handleUnassign(device.id)}
                >
                  {$_('devices.unassignButton')}
                </button>
              {/if}

              <!-- PIN lock reset button (E49S01 AC6) — only for locked SCORING_TABLET -->
              {#if device.deviceType === DEVICE_TYPE_SCORING}
                <button
                  class="btn btn--secondary btn--sm"
                  onclick={() => handleResetPinLock(device)}
                  title={$_('devices.scoringTablet.pinLockResetButton')}
                >
                  {$_('devices.scoringTablet.pinLockResetButton')}
                </button>
              {/if}

              <!-- Remove button for all devices (E07S03 AC4) -->
              <button
                class="btn btn--danger btn--sm"
                onclick={() => openRemoveDialog(device)}
              >
                {$_('devices.removeButton')}
              </button>

              {#if unassignErrors[device.id]}
                <span class="devices__row-error">{unassignErrors[device.id]}</span>
              {/if}
              {#if removeErrors[device.id]}
                <span class="devices__row-error">{removeErrors[device.id]}</span>
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

  /* Filter tabs (E07S03 AC1) */
  .devices__filter-tabs {
    display: flex;
    gap: 0;
    border-bottom: 2px solid #ddd;
    margin-bottom: 1rem;
  }

  .devices__tab {
    background: none;
    border: none;
    border-bottom: 2px solid transparent;
    margin-bottom: -2px;
    padding: 0.5rem 1.25rem;
    font-size: 0.95rem;
    cursor: pointer;
    color: #555;
    transition: color 0.15s, border-color 0.15s;
  }

  .devices__tab:hover {
    color: #2980b9;
  }

  .devices__tab--active {
    color: #2980b9;
    border-bottom-color: #2980b9;
    font-weight: 600;
  }

  /* Display limit bar (E07S03 AC5) */
  .devices__limit-bar {
    font-size: 0.85rem;
    color: #555;
    background: #f0f4f8;
    border: 1px solid #d0dce8;
    border-radius: 4px;
    padding: 0.4rem 0.75rem;
    margin-bottom: 1rem;
  }

  .devices__limit-bar--full {
    color: #c0392b;
    background: #fdf0ee;
    border-color: #f5c6c0;
  }

  /* Inline assign/rename row inside table cell (E49S01) */
  .devices__inline-row {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    flex-wrap: wrap;
  }

  /* Rename button — shown inline next to device name */
  .devices__rename-btn {
    margin-left: 0.25rem;
    font-size: 0.75rem;
    padding: 0.1rem 0.4rem;
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

  .devices__text-input--wide {
    width: 16rem;
  }

  .devices__select {
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    padding: 0.4rem 0.6rem;
    font-size: 0.95rem;
    background: #f5f5f5;
    color: #666;
  }

  /* Device type badge (E07S03 AC2) */
  .devices__type-badge {
    display: inline-block;
    font-size: 0.75rem;
    border-radius: 3px;
    padding: 0.1rem 0.4rem;
    font-weight: 500;
  }

  .devices__type-badge--scoring_tablet {
    background: #e8f4fd;
    color: #1a6fa8;
  }

  .devices__type-badge--display {
    background: #eaf5ea;
    color: #1e7e34;
  }

  /* Config status indicator (E07S03 AC2) */
  .devices__config-status {
    font-size: 0.8rem;
    color: #e67e22;
  }

  .devices__config-status--ok {
    color: #27ae60;
  }

  .devices__config-status--na {
    color: #aaa;
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

  /* Configure modal form rows */
  .devices__form-row {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    width: 100%;
    flex-wrap: wrap;
  }

  /* Overlay + modal (E06S05 AC4 / AC5, E07S03 AC3 / AC4) */
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

  .devices__modal--configure {
    max-width: 480px;
    align-items: flex-start;
  }

  .devices__modal--configure h2 {
    align-self: center;
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
    align-self: center;
  }

  /* Button styles */
  .btn {
    cursor: pointer;
    border: none;
    border-radius: 4px;
    padding: 0.5rem 1rem;
    font-size: 0.9rem;
  }

  .btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
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
