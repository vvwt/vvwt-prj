// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AudioEngine } from './audioEngine.js';
import type { AudioCategory } from './audioEngine.js';

// ── Mock HTMLAudioElement ─────────────────────────────────────────────────────

interface MockAudioElement {
  preload: string;
  currentTime: number;
  paused: boolean;
  src: string;
  play: ReturnType<typeof vi.fn>;
  pause: ReturnType<typeof vi.fn>;
  addEventListener: ReturnType<typeof vi.fn>;
  _triggerEvent: (name: 'canplaythrough' | 'error' | 'ended') => void;
}

function createMockAudioElement(url: string): MockAudioElement {
  const listeners: Record<string, (() => void)[]> = {};

  const el: MockAudioElement = {
    preload: 'auto',
    currentTime: 0,
    paused: true,
    src: url,
    play: vi.fn().mockImplementation(() => {
      el.paused = false;
      return Promise.resolve();
    }),
    pause: vi.fn().mockImplementation(() => {
      el.paused = true;
    }),
    addEventListener: vi.fn().mockImplementation((event: string, handler: () => void, options?: unknown) => {
      // Respect { once: true } semantics for the queue drain listeners
      const once = options && typeof options === 'object' && (options as { once?: boolean }).once === true;
      if (once) {
        const wrappedOnce = () => {
          handler();
          // Remove after first call
          if (listeners[event]) {
            listeners[event] = listeners[event].filter(h => h !== wrappedOnce);
          }
        };
        if (!listeners[event]) listeners[event] = [];
        listeners[event].push(wrappedOnce);
      } else {
        if (!listeners[event]) listeners[event] = [];
        listeners[event].push(handler);
      }
    }),
    _triggerEvent: (name: string) => {
      (listeners[name] ?? []).forEach(h => h());
    },
  };

  return el;
}

// ── Helper: create an engine with per-category distinct mock elements ─────────

function createEngineWithMocks(
  urls: { start?: string; end?: string; pause?: string },
): { engine: AudioEngine; mocks: Record<AudioCategory, MockAudioElement | null> } {
  const registry: Record<string, MockAudioElement> = {};
  const factory = (url: string): HTMLAudioElement => {
    const el = createMockAudioElement(url);
    registry[url] = el;
    return el as unknown as HTMLAudioElement;
  };
  const engine = new AudioEngine(factory);
  engine.preload(urls.start ?? null, urls.end ?? null, urls.pause ?? null);
  return {
    engine,
    mocks: {
      START: urls.start ? registry[urls.start] ?? null : null,
      END: urls.end ? registry[urls.end] ?? null : null,
      PAUSE: urls.pause ? registry[urls.pause] ?? null : null,
    },
  };
}

// ── Existing tests (preserved, AC15) ──────────────────────────────────────────

describe('AudioEngine.preload', () => {
  it('null URLs → idle status, no elements created (AC1)', () => {
    const mockFactory = vi.fn();
    const engine = new AudioEngine(mockFactory);
    engine.preload(null, null, null);
    expect(mockFactory).not.toHaveBeenCalled();
    const state = engine.getState();
    expect(state.statusStart).toBe('idle');
    expect(state.statusEnd).toBe('idle');
    expect(state.statusPause).toBe('idle');
  });

  it('non-null URL → loading status, element created (AC1)', () => {
    const mockEl = createMockAudioElement('/api/audio/tournaments/x/START/stream');
    const engine = new AudioEngine(() => mockEl as unknown as HTMLAudioElement);
    engine.preload('/api/audio/tournaments/x/START/stream', null, null);
    expect(engine.getState().statusStart).toBe('loading');
  });

  it('canplaythrough event → loaded status (AC1)', () => {
    const mockEl = createMockAudioElement('/url/start');
    const engine = new AudioEngine(() => mockEl as unknown as HTMLAudioElement);
    engine.preload('/url/start', null, null);
    mockEl._triggerEvent('canplaythrough');
    expect(engine.getState().statusStart).toBe('loaded');
  });

  it('error event → error status (AC8)', () => {
    const mockEl = createMockAudioElement('/url/start');
    const engine = new AudioEngine(() => mockEl as unknown as HTMLAudioElement);
    engine.preload('/url/start', null, null);
    mockEl._triggerEvent('error');
    expect(engine.getState().statusStart).toBe('error');
  });

  it('all three categories preloaded correctly', () => {
    const elements: MockAudioElement[] = [];
    const engine = new AudioEngine((url) => {
      const el = createMockAudioElement(url);
      elements.push(el);
      return el as unknown as HTMLAudioElement;
    });
    engine.preload('/start', '/end', '/pause');
    expect(elements).toHaveLength(3);
    expect(engine.getState().statusStart).toBe('loading');
    expect(engine.getState().statusEnd).toBe('loading');
    expect(engine.getState().statusPause).toBe('loading');
  });
});

describe('AudioEngine.hasAnyUrl', () => {
  it('returns false when all null', () => {
    const engine = new AudioEngine(vi.fn());
    engine.preload(null, null, null);
    expect(engine.hasAnyUrl()).toBe(false);
  });

  it('returns true when at least one URL configured', () => {
    const mockEl = createMockAudioElement('/url');
    const engine = new AudioEngine(() => mockEl as unknown as HTMLAudioElement);
    engine.preload('/start', null, null);
    expect(engine.hasAnyUrl()).toBe(true);
  });
});

describe('AudioEngine.play (existing behavior preserved, AC15)', () => {
  it('no-op when no element for category (AC8 resilience)', () => {
    const engine = new AudioEngine(vi.fn());
    engine.preload(null, null, null);
    expect(() => engine.play('START')).not.toThrow();
  });

  it('rewinds and calls play() on element when nothing else is playing (AC1-queue)', () => {
    const mockEl = createMockAudioElement('/start');
    mockEl.currentTime = 5;
    const engine = new AudioEngine(() => mockEl as unknown as HTMLAudioElement);
    engine.preload('/start', null, null);
    engine.play('START');
    expect(mockEl.currentTime).toBe(0);
    expect(mockEl.play).toHaveBeenCalledOnce();
  });
});

describe('AudioEngine.stopPauseMusic', () => {
  it('pauses and resets currentTime for PAUSE element (AC4)', () => {
    const mockEl = createMockAudioElement('/pause');
    const engine = new AudioEngine(() => mockEl as unknown as HTMLAudioElement);
    engine.preload(null, null, '/pause');
    mockEl.currentTime = 10;
    engine.stopPauseMusic();
    expect(mockEl.pause).toHaveBeenCalled();
    expect(mockEl.currentTime).toBe(0);
  });

  it('no-op when no PAUSE element', () => {
    const engine = new AudioEngine(vi.fn());
    engine.preload(null, null, null);
    expect(() => engine.stopPauseMusic()).not.toThrow();
  });
});

describe('AudioEngine.stopAll', () => {
  it('pauses all elements and resets currentTime (AC5)', () => {
    const elements: MockAudioElement[] = [];
    const engine = new AudioEngine((url) => {
      const el = createMockAudioElement(url);
      elements.push(el);
      return el as unknown as HTMLAudioElement;
    });
    engine.preload('/start', '/end', '/pause');
    elements.forEach(el => { el.currentTime = 5; });
    engine.stopAll();
    elements.forEach(el => {
      expect(el.pause).toHaveBeenCalled();
      expect(el.currentTime).toBe(0);
    });
  });
});

// ── NEW: Queue behavior tests (RED-first per AC14 / DEC-22 Iron Law) ──────────

describe('AudioEngine queue — AC1: immediate play when nothing is playing', () => {
  it('play(START) when idle → element plays immediately, no queuing', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s', end: '/e' });
    engine.play('START');
    // START element's play() must have been called
    expect(mocks.START!.play).toHaveBeenCalledOnce();
    // END must not have been played
    expect(mocks.END!.play).not.toHaveBeenCalled();
  });
});

describe('AudioEngine queue — AC2: queues when another sound is playing', () => {
  it('play(END) while START is playing → END is deferred to queue', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s', end: '/e' });
    // Start playing START
    engine.play('START');
    expect(mocks.START!.play).toHaveBeenCalledOnce();
    // While START plays, request END
    engine.play('END');
    // END must NOT have been played yet
    expect(mocks.END!.play).not.toHaveBeenCalled();
  });
});

describe('AudioEngine queue — AC3: queue drains on ended event', () => {
  it('when START ends, queued END plays', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s', end: '/e' });
    engine.play('START');
    engine.play('END'); // queued
    // Simulate START finishing
    mocks.START!._triggerEvent('ended');
    // END must now be playing
    expect(mocks.END!.play).toHaveBeenCalledOnce();
  });
});

describe('AudioEngine queue — AC4: recursive drain (multiple queued entries)', () => {
  it('three sounds drain in FIFO order via successive ended events', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s', end: '/e', pause: '/p' });
    // Play START (immediate), queue END and PAUSE
    engine.play('START');
    engine.play('END');
    engine.play('PAUSE');
    expect(mocks.END!.play).not.toHaveBeenCalled();
    expect(mocks.PAUSE!.play).not.toHaveBeenCalled();
    // START ends → END plays
    mocks.START!._triggerEvent('ended');
    expect(mocks.END!.play).toHaveBeenCalledOnce();
    expect(mocks.PAUSE!.play).not.toHaveBeenCalled();
    // END ends → PAUSE plays
    mocks.END!._triggerEvent('ended');
    expect(mocks.PAUSE!.play).toHaveBeenCalledOnce();
  });
});

describe('AudioEngine queue — AC5: stopAll() clears queue and stops current', () => {
  it('stopAll() while playing with queued entry → nothing plays after', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s', end: '/e' });
    engine.play('START');
    engine.play('END'); // queued
    engine.stopAll();
    // Trigger ended on START to confirm queue is empty — END should NOT play
    mocks.START!._triggerEvent('ended');
    expect(mocks.END!.play).not.toHaveBeenCalled();
  });

  it('after stopAll(), subsequent play() acts immediately (engine is idle)', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s' });
    engine.play('START');
    engine.stopAll();
    // Reset call count on the mock
    mocks.START!.play.mockClear();
    engine.play('START');
    expect(mocks.START!.play).toHaveBeenCalledOnce();
  });
});

describe('AudioEngine queue — AC6: stopPauseMusic() prunes PAUSE entries', () => {
  it('stopPauseMusic() removes queued PAUSE entries, leaves START/END entries intact', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s', end: '/e', pause: '/p' });
    // START playing, END and PAUSE queued
    engine.play('START');
    engine.play('END');
    engine.play('PAUSE');
    // Stop pause music — PAUSE in queue should be pruned
    engine.stopPauseMusic();
    // Simulate START ending → END plays (not PAUSE)
    mocks.START!._triggerEvent('ended');
    expect(mocks.END!.play).toHaveBeenCalledOnce();
    // END ends → queue should be empty (PAUSE was pruned)
    mocks.END!._triggerEvent('ended');
    expect(mocks.PAUSE!.play).not.toHaveBeenCalled();
  });

  it('stopPauseMusic() stops currently-playing PAUSE and drains next entry', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s', end: '/e', pause: '/p' });
    // PAUSE plays immediately (nothing else playing)
    engine.play('PAUSE');
    expect(mocks.PAUSE!.play).toHaveBeenCalledOnce();
    // Queue END while PAUSE plays
    engine.play('END');
    // Stop PAUSE — it is the currently playing element
    engine.stopPauseMusic();
    // END should now drain and play immediately
    expect(mocks.END!.play).toHaveBeenCalledOnce();
    // PAUSE must have been stopped
    expect(mocks.PAUSE!.pause).toHaveBeenCalled();
  });

  it('stopPauseMusic() on empty queue is a no-op (AC12)', () => {
    const { engine } = createEngineWithMocks({ start: '/s', pause: '/p' });
    // Nothing playing
    expect(() => engine.stopPauseMusic()).not.toThrow();
  });

  it('stopPauseMusic() while PAUSE is playing with no queue → engine becomes idle', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s', pause: '/p' });
    engine.play('PAUSE');
    engine.stopPauseMusic();
    // After stopping PAUSE, START should play immediately when called
    mocks.START!.play.mockClear();
    engine.play('START');
    expect(mocks.START!.play).toHaveBeenCalledOnce();
  });
});

describe('AudioEngine queue — AC7: pauseAll() does not affect queue', () => {
  it('pauseAll() while playing with queued entry: queue unchanged', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s', end: '/e' });
    engine.play('START');
    engine.play('END'); // queued
    engine.pauseAll();
    // ended does NOT fire on paused element; END must still be queued (not played yet)
    expect(mocks.END!.play).not.toHaveBeenCalled();
    // Simulate transport PLAY triggering ended (when element eventually finishes)
    mocks.START!._triggerEvent('ended');
    expect(mocks.END!.play).toHaveBeenCalledOnce();
  });
});

describe('AudioEngine queue — AC8: resumeAll() resumes paused element', () => {
  it('resumeAll() calls play() on paused element with non-zero currentTime (source-inspection behavior)', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s' });
    engine.play('START');
    mocks.START!.currentTime = 3; // simulate mid-play state
    engine.pauseAll();
    expect(mocks.START!.paused).toBe(true);
    mocks.START!.play.mockClear();
    engine.resumeAll();
    expect(mocks.START!.play).toHaveBeenCalledOnce();
  });
});

describe('AudioEngine queue — AC11: error event drains queue (broken audio does not freeze queue)', () => {
  it('when playing element fires error, queue drains to next entry', () => {
    const { engine, mocks } = createEngineWithMocks({ start: '/s', end: '/e' });
    engine.play('START');
    engine.play('END'); // queued
    // START fires error instead of ended
    mocks.START!._triggerEvent('error');
    // Queue must still drain → END plays
    expect(mocks.END!.play).toHaveBeenCalledOnce();
  });
});

describe('AudioEngine queue — AC13: queue depth is not bounded', () => {
  it('many play() calls do not throw (source-inspection: no depth limit enforcement)', () => {
    const { engine } = createEngineWithMocks({ start: '/s', end: '/e', pause: '/p' });
    engine.play('START'); // plays immediately
    // Queue many entries — should never throw regardless of depth
    expect(() => {
      for (let i = 0; i < 20; i++) {
        engine.play(i % 3 === 0 ? 'END' : i % 3 === 1 ? 'PAUSE' : 'START');
      }
    }).not.toThrow();
  });
});
