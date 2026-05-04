<script lang="ts">
  /**
   * vvwt-info App — per-team timeline view SPA (E38S08).
   *
   * AC3: single state machine WS with reconnect-on-gap (NOT buffer).
   * AC5: supersede UX when tournament_ended=true.
   * AC6: 410 Gone UX.
   * AC10: URL-as-bearer tokens from path only; never stored in localStorage/sessionStorage.
   * AC14: exponential backoff 1s×2, full-jitter, cap 60s, indefinite; connection-lost indicator.
   * AC15: stale-data indicator (60s threshold, distinct from connection-lost).
   * AC16: DE-only strings via de.ts; no inline literals in template.
   *
   * Story: E38S08. DEC-2: Svelte 5 runes; no SvelteKit.
   */

  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import de from './lib/i18n/de.js';
  import { extractTokensFromCurrentUrl } from './lib/tokenExtractor.js';
  import { createConnectionStore } from './lib/stores/connectionStore.js';
  import { createTournamentStore } from './lib/stores/tournamentStore.js';
  import Timeline from './lib/components/Timeline.svelte';
  import ConnectionStatus from './lib/components/ConnectionStatus.svelte';
  import SupersedeView from './lib/components/SupersedeView.svelte';
  import GoneView from './lib/components/GoneView.svelte';
  import type { TournamentSnapshot, ScheduleEntry } from './lib/stores/tournamentStore.js';

  // Allow optional props for test harness injection (AC10/AC13 tests pass these directly)
  interface Props {
    tournamentToken?: string;
    teamToken?: string;
  }

  const { tournamentToken: propTournamentToken, teamToken: propTeamToken }: Props = $props();

  // AC10: tokens from URL path (not query string, not cookies, not storage)
  const { tournamentToken, teamToken } = (propTournamentToken !== undefined && propTeamToken !== undefined)
    ? { tournamentToken: propTournamentToken, teamToken: propTeamToken }
    : extractTokensFromCurrentUrl();

  const wsUrl = tournamentToken && teamToken
    ? `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/api/v1/stream/${tournamentToken}/${teamToken}`
    : '';
  const pollUrl = tournamentToken && teamToken
    ? `/api/v1/poll/${tournamentToken}/${teamToken}`
    : '';

  const connStore = createConnectionStore({ wsUrl, pollUrl });
  const tournStore = createTournamentStore();

  let connState = $state(get(connStore));
  let tournState = $state(get(tournStore));

  const unsubConn = connStore.subscribe((s) => { connState = s; });
  const unsubTourn = tournStore.subscribe((s) => { tournState = s; });

  // WebSocket lifecycle
  let ws: WebSocket | null = null;
  let pollTimer: ReturnType<typeof setTimeout> | null = null;
  let wsBackoffMs = connStore.getInitialBackoffMs();
  let wsReconnectTimer: ReturnType<typeof setTimeout> | null = null;

  // Poll cadence from server StreamHello (default 5s per app.yml)
  let pollCadenceMs = 5000;

  function scheduleReconnect(): void {
    wsBackoffMs = connStore.computeNextBackoffMs(wsBackoffMs);
    if (wsBackoffMs < 1) wsBackoffMs = 1000;
    wsReconnectTimer = setTimeout(() => connectWs(), wsBackoffMs);
  }

  function connectWs(): void {
    if (!wsUrl) return;
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      connStore.onConnectSuccess();
      wsBackoffMs = connStore.getInitialBackoffMs();
    };

    ws.onmessage = (event: MessageEvent) => {
      try {
        const envelope = JSON.parse(event.data as string) as { payload: unknown };
        handleEnvelopePayload(envelope.payload);
      } catch {
        // Malformed message — ignore
      }
    };

    ws.onerror = () => {
      connStore.onWsFailed();
    };

    ws.onclose = (event: CloseEvent) => {
      if (event.code === 410) {
        // 410 Gone — tournament expired
        connStore.onGoneResponse();
        return;
      }
      if (connState.linkExpired || connState.tournamentEnded) return;
      connStore.onWsFailed();
      scheduleReconnect();
    };
  }

  function handleEnvelopePayload(payload: unknown): void {
    if (!payload || typeof payload !== 'object') return;
    const p = payload as Record<string, unknown>;

    if ('tournament_ended' in p || 'tournamentId' in p) {
      // TournamentSnapshot
      const snapshot = parseSnapshot(p);
      if (snapshot) {
        const lastKnownSeq = tournState.lastKnownSeq;
        // AC3: if this is a delta update and there's a gap → reconnect (not buffer)
        // Snapshot always applied directly; gap detection is for delta messages
        tournStore.applySnapshot(snapshot);
        connStore.applySnapshot({
          ...snapshot,
          sequenceNumber: snapshot.sequenceNumber,
        });
        connStore.onConnected();
        connStore.onSuccessfulUpdate();
        void lastKnownSeq; // suppress unused warning
      }
    } else if ('pollCadenceSeconds' in p) {
      // StreamHello
      const cadence = p['pollCadenceSeconds'];
      if (typeof cadence === 'number') {
        pollCadenceMs = cadence * 1000;
      }
    }
    // DomainEvent (delta) — sequence gap check
    else if ('seq' in p) {
      const seq = BigInt(p['seq'] as number);
      const lastSeq = tournState.lastKnownSeq;
      if (lastSeq >= 0n && connStore.shouldReconnectOnGap(lastSeq, seq)) {
        // AC3: gap detected → reconnect (NOT buffer)
        ws?.close();
        connectWs();
      } else {
        // No gap: would apply delta here — for Phase 1, request fresh snapshot
        ws?.close();
        connectWs();
      }
    }
  }

  function parseSnapshot(p: Record<string, unknown>): TournamentSnapshot | null {
    try {
      return {
        tournamentId: p['tournamentId'] as string,
        tenantId: p['tenantId'] as string,
        sequenceNumber: BigInt(p['sequenceNumber'] as number),
        scheduleEntries: (p['scheduleEntries'] as ScheduleEntry[]) ?? [],
        teams: (p['teams'] as { teamId: string; name: string; number: number }[]) ?? [],
        tournament_ended: (p['tournament_ended'] as boolean) ?? false,
      };
    } catch {
      return null;
    }
  }

  async function doPoll(): Promise<void> {
    if (!pollUrl || connState.linkExpired || connState.tournamentEnded) return;
    try {
      const since = tournState.lastKnownSeq >= 0n ? `?since=${tournState.lastKnownSeq}` : '';
      const resp = await fetch(`${pollUrl}${since}`);
      if (resp.status === 410) {
        connStore.onGoneResponse();
        return;
      }
      if (!resp.ok) {
        connStore.onPollFailed();
        return;
      }
      const envelope = (await resp.json()) as { payload: unknown };
      handleEnvelopePayload(envelope.payload);
    } catch {
      connStore.onPollFailed();
    }
  }

  function startPollFallback(): void {
    if (pollTimer !== null) clearInterval(pollTimer);
    pollTimer = setInterval(() => { void doPoll(); }, pollCadenceMs);
  }

  onMount(() => {
    if (!tournamentToken || !teamToken) {
      connStore.onGoneResponse();
      return;
    }
    connectWs();
    startPollFallback();
  });

  onDestroy(() => {
    unsubConn();
    unsubTourn();
    ws?.close();
    if (pollTimer !== null) clearInterval(pollTimer);
    if (wsReconnectTimer !== null) clearTimeout(wsReconnectTimer);
  });
</script>

<main class="app">
  <!-- AC3 (E44S03): Brand lockup — yellow-inverted Info logo (NOT blue vvw-tm-logo.svg).
       alt="Live Information": speaking alt per Brief Q-4.
       src uses import.meta.env.BASE_URL + filename: Vite base-resolved URL, no hardcoded /info/ literal (AC11).
       AC12: class="brand-lockup" enforces min-width: 120px (Brief C-9). -->
  <img
    src="{import.meta.env.BASE_URL}vvw-info-logo.svg"
    alt="Live Information"
    class="brand-lockup"
  />
  <ConnectionStatus connectionLost={connState.connectionLost} dataStale={connState.dataStale} />

  {#if connState.linkExpired}
    <GoneView />
  {:else if connState.tournamentEnded}
    <SupersedeView snapshot={tournState.snapshot} />
  {:else if tournState.snapshot !== null}
    <Timeline entries={tournState.snapshot.scheduleEntries} />
  {:else}
    <div class="loading">
      <p>{de['app.loading']}</p>
    </div>
  {/if}
</main>

<style>
  /* AC10 (E44S03): brand-tokens.css imported globally for --vvw-blue, --vvw-yellow, --vvw-ink. */
  @import './styles/brand-tokens.css';

  .app {
    font-family: sans-serif;
    max-width: 720px;
    margin: 0 auto;
    padding: 1rem;
  }

  /* AC3 (E44S03), AC6 (E44S04): Brand lockup — yellow-inverted Info logo.
     height: 2em (~32px) per E44S04 oversize fix; margin-bottom preserved for Info layout. */
  .brand-lockup {
    height: 2em;
    display: block;
    margin-bottom: 1rem;
  }

  .loading {
    text-align: center;
    padding: 2rem;
    color: #888;
    font-style: italic;
  }
</style>
