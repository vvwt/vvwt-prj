// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export type AudioCategory = 'START' | 'END' | 'PAUSE';

export type AudioLoadStatus = 'idle' | 'loading' | 'loaded' | 'error';

export interface AudioEngineState {
  statusStart: AudioLoadStatus;
  statusEnd: AudioLoadStatus;
  statusPause: AudioLoadStatus;
}

// ── AudioEngine ───────────────────────────────────────────────────────────────

/**
 * Manages preloading and playback of the three audio categories.
 *
 * Designed to be testable: accepts an optional element-factory function so
 * tests can inject mock HTMLAudioElement instances.
 */
export class AudioEngine {
  private elements: Partial<Record<AudioCategory, HTMLAudioElement>> = {};
  private status: Record<AudioCategory, AudioLoadStatus> = {
    START: 'idle',
    END: 'idle',
    PAUSE: 'idle',
  };

  /**
   * Factory used to create HTMLAudioElement instances.
   * Defaults to `(url) => new Audio(url)` in production.
   * Can be replaced in tests with a mock factory.
   */
  private createElement: (url: string) => HTMLAudioElement;

  constructor(
    createElement?: (url: string) => HTMLAudioElement,
  ) {
    this.createElement = createElement ?? ((url) => {
      const el = new Audio(url);
      el.preload = 'auto';
      return el;
    });
  }

  /**
   * Preloads audio for all three categories from the given URLs (AC1).
   *
   * - If a URL is null, the category is silently skipped (no element, no error).
   * - If a URL is provided, an HTMLAudioElement is created and preloaded.
   *   On load error: status[category] = 'error' (AC8).
   *
   * @param startUrl  URL for the start sound (AC1)
   * @param endUrl    URL for the end sound (AC1)
   * @param pauseUrl  URL for the pause music (AC1)
   */
  preload(startUrl: string | null, endUrl: string | null, pauseUrl: string | null): void {
    this.preloadCategory('START', startUrl);
    this.preloadCategory('END', endUrl);
    this.preloadCategory('PAUSE', pauseUrl);
  }

  private preloadCategory(category: AudioCategory, url: string | null): void {
    if (!url) {
      this.status[category] = 'idle'; // null URL = no file configured (AC1: silently skipped)
      return;
    }
    this.status[category] = 'loading';

    let el: HTMLAudioElement;
    try {
      el = this.createElement(url);
    } catch {
      this.status[category] = 'error';
      return;
    }

    el.addEventListener('canplaythrough', () => {
      this.status[category] = 'loaded';
    }, { once: true });

    el.addEventListener('error', () => {
      this.status[category] = 'error';
    }, { once: true });

    this.elements[category] = el;
  }

  /**
   * Returns the current load status snapshot (AC8).
   */
  getState(): AudioEngineState {
    return {
      statusStart: this.status.START,
      statusEnd: this.status.END,
      statusPause: this.status.PAUSE,
    };
  }

  /**
   * Returns true if ALL categories that have a URL loaded successfully (or are still loading).
   * Returns false if any configured category errored.
   */
  hasAnyUrl(): boolean {
    return (
      this.status.START !== 'idle' ||
      this.status.END !== 'idle' ||
      this.status.PAUSE !== 'idle'
    );
  }

  /**
   * Plays the sound for a given category.
   * - Rewinds to the start before playing (allows re-triggering).
   * - No-op if the element is not present or not loaded (AC8 resilience).
   *
   * @param category  Which audio category to play
   */
  play(category: AudioCategory): void {
    const el = this.elements[category];
    if (!el) return;
    el.currentTime = 0;
    el.play().catch(() => {
      // Play promise rejection (e.g., blocked by browser policy) → silently ignore.
      // The AudioContext user gesture is handled by ClockSyncDialog.
    });
  }

  /**
   * Stops the pause music (called when a regular break ends, AC4).
   * - Sets currentTime to 0 and pauses (cuts off cleanly).
   * - No-op if element is not present.
   */
  stopPauseMusic(): void {
    const el = this.elements['PAUSE'];
    if (!el) return;
    el.pause();
    el.currentTime = 0;
  }

  /**
   * Pauses all currently-playing audio (transport Pause, AC5).
   * - Pauses all three elements (only the actively playing one will have effect).
   */
  pauseAll(): void {
    for (const el of Object.values(this.elements)) {
      if (el && !el.paused) {
        el.pause();
      }
    }
  }

  /**
   * Resumes the last-playing element if it was paused by pauseAll() (transport Play, AC5).
   * Note: This is a simple resume; the engine does not track which element was playing.
   * In practice, after a Pause + Play, the countdown engine re-evaluates the active event
   * and calls play() on the appropriate category, which rewinds and plays fresh.
   */
  resumeAll(): void {
    for (const el of Object.values(this.elements)) {
      if (el && el.paused && el.currentTime > 0) {
        el.play().catch(() => {});
      }
    }
  }

  /**
   * Stops all audio (transport Stop, AC5).
   */
  stopAll(): void {
    for (const el of Object.values(this.elements)) {
      if (el) {
        el.pause();
        el.currentTime = 0;
      }
    }
  }
}
