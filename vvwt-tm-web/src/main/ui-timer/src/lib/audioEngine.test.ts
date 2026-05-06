/**
 * Unit tests for audioEngine.ts (E11S04 AC1, AC8).
 *
 * Tests:
 *   1. preload with null URLs → idle status, no element created
 *   2. preload with URLs → loading status, element created
 *   3. canplaythrough event → loaded status
 *   4. error event → error status
 *   5. play() on loaded element → currentTime=0, play() called
 *   6. play() when no element → no-op (no crash)
 *   7. stopPauseMusic() → pauses and resets currentTime
 *   8. pauseAll() → pauses playing elements
 *   9. stopAll() → pauses all and resets currentTime
 *  10. hasAnyUrl() → true when at least one URL configured
 */

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
  _triggerEvent: (name: 'canplaythrough' | 'error') => void;
}

function createMockAudioElement(url: string): MockAudioElement {
  const listeners: Record<string, (() => void)[]> = {};

  const el: MockAudioElement = {
    preload: 'auto',
    currentTime: 0,
    paused: true,
    src: url,
    play: vi.fn().mockResolvedValue(undefined),
    pause: vi.fn().mockImplementation(() => {
      el.paused = true;
    }),
    addEventListener: vi.fn().mockImplementation((event: string, handler: () => void) => {
      if (!listeners[event]) listeners[event] = [];
      listeners[event].push(handler);
    }),
    _triggerEvent: (name: string) => {
      (listeners[name] ?? []).forEach(h => h());
    },
  };

  return el;
}

// ── Tests ─────────────────────────────────────────────────────────────────────

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

describe('AudioEngine.play', () => {
  it('no-op when no element for category (AC8 resilience)', () => {
    const engine = new AudioEngine(vi.fn());
    engine.preload(null, null, null);
    // Should not throw
    expect(() => engine.play('START')).not.toThrow();
  });

  it('rewinds and calls play() on element', () => {
    const mockEl = createMockAudioElement('/start');
    mockEl.currentTime = 5; // simulate mid-play
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
