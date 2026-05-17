// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** WebSocket endpoint — relative URL, resolved at runtime against the serving host (DEC-16). */
const WS_ENDPOINT = '/ws';

/** STOMP CONNECT header name for the display device token (AC1). */
const DEVICE_TOKEN_HEADER = 'X-Device-Token';

/** Tenant-scoped display topic pattern. */
const DISPLAY_TOPIC_PATTERN = '/topic/display/%s/events';

/** Initial reconnect delay in milliseconds (AC7). */
const RECONNECT_DELAY_INITIAL_MS = 1_000;

/** Maximum reconnect delay in milliseconds — caps the exponential backoff (AC7). */
const RECONNECT_DELAY_MAX_MS = 30_000;

/** Number of consecutive reconnect failures before switching to polling fallback (AC9). */
export const MAX_RECONNECT_ATTEMPTS = 5;

// ---------------------------------------------------------------------------
// Event message type (mirrors EventMessage Java DTO)
// ---------------------------------------------------------------------------

/** Payload of a single WebSocket event message from the server. */
export interface WsEventMessage {
  eventType: string;
  entityId: string;
  timestamp: string;
}

/** Known event types broadcast by DomainEventBridge. */
export const EVENT_TYPE_MATCH_RESULT_CHANGED = 'MATCH_RESULT_CHANGED';
export const EVENT_TYPE_LAP_ADVANCED         = 'LAP_ADVANCED';
export const EVENT_TYPE_PHASE_STATUS_CHANGED = 'PHASE_STATUS_CHANGED';

// ---------------------------------------------------------------------------
// Connection status
// ---------------------------------------------------------------------------

/** WebSocket connection state, used for the status indicator (AC7, AC9). */
export type ConnectionStatus =
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'polling'   // fallback: WebSocket unavailable, polling REST instead
  | 'disconnected';

// ---------------------------------------------------------------------------
// WebSocket client state
// ---------------------------------------------------------------------------

let stompClient: Client | null = null;
let reconnectAttempts = 0;
let reconnectDelayMs = RECONNECT_DELAY_INITIAL_MS;
let reconnectTimerId: ReturnType<typeof setTimeout> | null = null;
let isFallbackPolling = false;
let pollingTimerId: ReturnType<typeof setInterval> | null = null;

// Callbacks set during connect()
let activeDeviceToken: string = '';
let activeTenantId: string = '';
let activeOnEvent: ((msg: WsEventMessage) => void) | null = null;
let activeOnStatusChange: ((status: ConnectionStatus) => void) | null = null;
let activeOnFallback: (() => void) | null = null;

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Connect to the display WebSocket and subscribe to the tenant-scoped topic.
 *
 * @param deviceToken   display device token for STOMP CONNECT auth (AC1)
 * @param tenantId      tenant UUID — used to build the subscription topic (AC11)
 * @param onEvent       callback invoked for each received event message
 * @param onStatusChange callback invoked on connection state changes
 * @param onFallback    callback invoked when polling fallback activates (AC9)
 */
export function connect(
  deviceToken: string,
  tenantId: string,
  onEvent: (msg: WsEventMessage) => void,
  onStatusChange: (status: ConnectionStatus) => void,
  onFallback: () => void
): void {
  // Store callbacks for use during reconnect attempts
  activeDeviceToken = deviceToken;
  activeTenantId = tenantId;
  activeOnEvent = onEvent;
  activeOnStatusChange = onStatusChange;
  activeOnFallback = onFallback;

  reconnectAttempts = 0;
  reconnectDelayMs = RECONNECT_DELAY_INITIAL_MS;
  isFallbackPolling = false;

  onStatusChange('connecting');
  doConnect();
}

/**
 * Disconnect the active STOMP session and cancel any pending reconnect timers.
 * Stops polling fallback if active.
 */
export function disconnect(): void {
  cancelReconnectTimer();
  stopPolling();

  if (stompClient !== null) {
    try {
      stompClient.deactivate();
    } catch {
      // best-effort: ignore errors on cleanup
    }
    stompClient = null;
  }

  if (activeOnStatusChange !== null) {
    activeOnStatusChange('disconnected');
  }
}

/**
 * Returns true if the client is currently in polling-fallback mode (AC9).
 */
export function isPolling(): boolean {
  return isFallbackPolling;
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

function doConnect(): void {
  const topic = buildDisplayTopic(activeTenantId);

  const client = new Client({
    // Use SockJS transport (DEC-16: served from same origin as REST API)
    webSocketFactory: () => new SockJS(WS_ENDPOINT) as WebSocket,

    connectHeaders: {
      [DEVICE_TOKEN_HEADER]: activeDeviceToken,
    },

    // Suppress internal STOMP library reconnect — we manage it manually for accurate backoff
    reconnectDelay: 0,

    onConnect: () => {
      reconnectAttempts = 0;
      reconnectDelayMs = RECONNECT_DELAY_INITIAL_MS;
      activeOnStatusChange?.('connected');

      client.subscribe(topic, (stompMessage) => {
        try {
          const payload = JSON.parse(stompMessage.body) as WsEventMessage;
          activeOnEvent?.(payload);
        } catch {
          // Malformed message — log and ignore; do not crash the client
          console.warn('[ws] Received malformed event message:', stompMessage.body);
        }
      });
    },

    onDisconnect: () => {
      activeOnStatusChange?.('disconnected');
    },

    onStompError: (frame) => {
      console.warn('[ws] STOMP error:', frame.headers['message']);
      scheduleReconnect();
    },

    onWebSocketError: (_event) => {
      scheduleReconnect();
    },

    onWebSocketClose: (_event) => {
      if (!isFallbackPolling) {
        scheduleReconnect();
      }
    },
  });

  stompClient = client;
  client.activate();
}

function scheduleReconnect(): void {
  if (isFallbackPolling) {
    // Already in fallback mode — do not attempt further reconnects
    return;
  }

  reconnectAttempts += 1;

  if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
    // Exhausted retries — activate polling fallback (AC9)
    activateFallbackPolling();
    return;
  }

  activeOnStatusChange?.('reconnecting');

  const delay = reconnectDelayMs;
  reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_DELAY_MAX_MS);

  reconnectTimerId = setTimeout(() => {
    reconnectTimerId = null;
    doConnect();
  }, delay);
}

function activateFallbackPolling(): void {
  isFallbackPolling = true;
  cancelReconnectTimer();

  activeOnStatusChange?.('polling');
  activeOnFallback?.();
}

function stopPolling(): void {
  if (pollingTimerId !== null) {
    clearInterval(pollingTimerId);
    pollingTimerId = null;
  }
  isFallbackPolling = false;
}

function cancelReconnectTimer(): void {
  if (reconnectTimerId !== null) {
    clearTimeout(reconnectTimerId);
    reconnectTimerId = null;
  }
}

/** Builds the tenant-scoped display topic path (AC11). */
function buildDisplayTopic(tenantId: string): string {
  return DISPLAY_TOPIC_PATTERN.replace('%s', tenantId);
}
