// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { Client as StompClient } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** STOMP CONNECT header that signals a timer client (server-side: D-7). */
const TIMER_CONNECT_HEADER = 'X-Timer-Connect';

/** STOMP CONNECT header carrying the tenant ID for topic subscription. */
const TIMER_TENANT_ID_HEADER = 'X-Timer-Tenant-Id';

/** Topic pattern — mirrors DomainEventBridge.DISPLAY_EVENTS_TOPIC_PATTERN */
const DISPLAY_TOPIC_PATTERN = '/topic/display/{tenantId}/events';

/** Initial reconnect delay in milliseconds. */
const RECONNECT_INITIAL_MS = 1_000;

/** Maximum reconnect delay in milliseconds (AC4: max 30s interval). */
const RECONNECT_MAX_MS = 30_000;

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Event types emitted by the server. Timer reacts to these two only. */
type ServerEventType = 'LAP_ADVANCED' | 'PHASE_STATUS_CHANGED' | string;

/** Minimal shape of the EventMessage.java JSON payload from the server. */
interface ServerEventMessage {
  eventType: ServerEventType;
  entityId: string;
  timestamp: string;
}

export interface TimerWsOptions {
  /** UUID of the tenant — used to build the subscription topic. */
  tenantId: string;
  /** Called when a LAP_ADVANCED event is received (AC2). */
  onLapAdvanced: () => void;
  /** Called when a PHASE_STATUS_CHANGED event is received (AC3). */
  onPhaseChanged: () => void;
  /** Called when the WebSocket disconnects or initial connect fails (AC4). */
  onDisconnected: () => void;
  /** Called when the WebSocket successfully reconnects (AC5). */
  onReconnected: () => void;
}

// ---------------------------------------------------------------------------
// TimerWsClient
// ---------------------------------------------------------------------------

/**
 * Manages the STOMP/SockJS WebSocket lifecycle for the timer SPA (E11S05).
 *
 * Usage:
 * ```ts
 * const ws = new TimerWsClient({ tenantId, onLapAdvanced, onPhaseChanged, onDisconnected, onReconnected });
 * ws.connect();
 * // ...
 * ws.disconnect(); // call in onDestroy
 * ```
 */
export class TimerWsClient {

  private readonly options: TimerWsOptions;
  private client: StompClient | null = null;
  private destroyed = false;
  private reconnectDelay = RECONNECT_INITIAL_MS;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private connected = false;

  constructor(options: TimerWsOptions) {
    this.options = options;
  }

  /**
   * Establish the WebSocket connection. Idempotent — safe to call if already connected.
   */
  connect(): void {
    if (this.destroyed || this.client !== null) return;
    this.doConnect();
  }

  /**
   * Permanently disconnect and stop all reconnect attempts.
   * Call this in the Svelte component's {@code onDestroy} lifecycle.
   */
  disconnect(): void {
    this.destroyed = true;
    this.clearReconnectTimer();
    if (this.client) {
      this.client.deactivate();
      this.client = null;
    }
  }

  // ── Private helpers ──────────────────────────────────────────────────────

  private doConnect(): void {
    if (this.destroyed) return;

    const { tenantId } = this.options;
    const topic = DISPLAY_TOPIC_PATTERN.replace('{tenantId}', tenantId);

    const client = new StompClient({
      webSocketFactory: () => new SockJS('/ws') as unknown as WebSocket,
      // Disable built-in STOMP reconnect — we manage reconnect ourselves
      // for exponential backoff control.
      reconnectDelay: 0,
      connectHeaders: {
        [TIMER_CONNECT_HEADER]: 'true',
        [TIMER_TENANT_ID_HEADER]: tenantId,
      },
      onConnect: () => {
        if (this.destroyed) {
          client.deactivate();
          return;
        }
        this.connected = true;
        this.reconnectDelay = RECONNECT_INITIAL_MS; // reset backoff on success

        // AC5: signal reconnect (first connect is treated as a special case below)
        // The App re-fetches the schedule on both first connect (already done) and reconnect.
        // We fire onReconnected here regardless — the App component handles the first-connect
        // case by not reacting (it already loaded the schedule during the loading phase).
        this.options.onReconnected();

        client.subscribe(topic, (frame) => {
          if (this.destroyed) return;
          this.handleMessage(frame.body);
        });
      },
      onStompError: (_frame) => {
        // STOMP-level error — treat as disconnect
        this.handleDisconnect();
      },
      onWebSocketClose: () => {
        this.handleDisconnect();
      },
      onWebSocketError: (_event) => {
        this.handleDisconnect();
      },
    });

    client.activate();
    this.client = client;
  }

  private handleMessage(body: string): void {
    let msg: ServerEventMessage;
    try {
      msg = JSON.parse(body) as ServerEventMessage;
    } catch {
      return; // Malformed message — ignore
    }

    if (msg.eventType === 'LAP_ADVANCED') {
      this.options.onLapAdvanced();
    } else if (msg.eventType === 'PHASE_STATUS_CHANGED') {
      this.options.onPhaseChanged();
    }
    // Other event types (MATCH_RESULT_CHANGED, DEVICE_REGISTERED) are silently ignored.
  }

  private handleDisconnect(): void {
    if (this.destroyed) return;

    const wasConnected = this.connected;
    this.connected = false;

    if (this.client) {
      // Deactivate without triggering another onWebSocketClose (guard: destroyed)
      const c = this.client;
      this.client = null;
      c.deactivate().catch(() => { /* ignore */ });
    }

    // AC4: notify app of disconnect (only once per disconnect event)
    if (wasConnected || !this.client) {
      this.options.onDisconnected();
    }

    // Schedule reconnect with exponential backoff (AC4)
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.destroyed) return;
    this.clearReconnectTimer();

    const delay = this.reconnectDelay;
    // Exponential backoff: double each attempt, cap at RECONNECT_MAX_MS
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, RECONNECT_MAX_MS);

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.destroyed && this.client === null) {
        this.doConnect();
      }
    }, delay);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}
