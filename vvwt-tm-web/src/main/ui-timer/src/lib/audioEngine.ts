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
 *
 * Queue semantics (E11S15):
 * - play(category) plays immediately when nothing is playing (AC1).
 * - play(category) queues when another sound is in progress (AC2).
 * - Queue drains on the playing element's `ended` DOM event (AC3).
 * - `error` events also drain the queue so a broken file does not freeze it (AC11).
 * - stopAll() clears the entire queue and stops the current sound (AC5).
 * - stopPauseMusic() prunes queued PAUSE-category entries and stops in-progress PAUSE (AC6).
 * - pauseAll() pauses the current element; queue is unchanged (AC7).
 * - resumeAll() resumes the paused element; queue drain continues on ended (AC8).
 */
export class AudioEngine {
  private elements: Partial<Record<AudioCategory, HTMLAudioElement>> = {};
  private status: Record<AudioCategory, AudioLoadStatus> = {
    START: 'idle',
    END: 'idle',
    PAUSE: 'idle',
  };

  // ── Queue state (E11S15) ──────────────────────────────────────────────────

  /**
   * The element currently being played by the queue machinery.
   * null when the engine is idle (nothing playing or in-progress).
   */
  private currentlyPlaying: HTMLAudioElement | null = null;

  /**
   * The AudioCategory of the currently-playing element.
   * Used by stopPauseMusic() to check if the in-progress sound is PAUSE.
   */
  private currentlyPlayingCategory: AudioCategory | null = null;

  /**
   * Plain FIFO queue of AudioCategory entries waiting to be played.
   * The head is shifted when the currentlyPlaying element ends (or errors).
   */
  private playQueue: AudioCategory[] = [];

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
   *
   * Queue semantics (E11S15):
   * - If nothing is currently playing, plays immediately (AC1).
   * - If another sound is playing, appends this category to the FIFO queue (AC2).
   * - The queue drains automatically when the playing element's `ended` event fires (AC3).
   * - No-op if the element is not present (AC8 resilience).
   *
   * @param category  Which audio category to play
   */
  play(category: AudioCategory): void {
    const el = this.elements[category];
    if (!el) return; // no element for this category → silent no-op (AC8)

    if (this.currentlyPlaying === null) {
      // Engine is idle — play immediately (AC1)
      this._startPlay(category, el);
    } else {
      // Another sound is in progress — append to FIFO queue (AC2)
      this.playQueue.push(category);
    }
  }

  /**
   * Internal: begins playback of `category` and registers drain listeners.
   * Called by play() (immediate path) and _drainQueue() (deferred path).
   *
   * Registers { once: true } listeners for both `ended` and `error` on the element
   * so that either event advances the queue (AC3, AC11).
   */
  private _startPlay(category: AudioCategory, el: HTMLAudioElement): void {
    this.currentlyPlaying = el;
    this.currentlyPlayingCategory = category;

    // Rewind before playing (consistent with pre-E11S15 behaviour)
    el.currentTime = 0;

    // Register drain listeners — { once: true } prevents double-drain
    const drainHandler = () => {
      this._drainQueue();
    };
    el.addEventListener('ended', drainHandler, { once: true });
    el.addEventListener('error', drainHandler, { once: true });

    el.play().catch(() => {
      // Play promise rejection (e.g., blocked by browser policy) → silently ignore.
      // The AudioContext user gesture is handled by ClockSyncDialog.
    });
  }

  /**
   * Internal: advances the queue after the playing element ends (or errors).
   * Clears `currentlyPlaying`; shifts the head of the queue if non-empty and plays it.
   */
  private _drainQueue(): void {
    this.currentlyPlaying = null;
    this.currentlyPlayingCategory = null;

    if (this.playQueue.length === 0) {
      return; // queue empty → engine returns to idle
    }

    const next = this.playQueue.shift()!;
    const el = this.elements[next];
    if (!el) {
      // Element not loaded for this category → skip and drain further
      this._drainQueue();
      return;
    }
    this._startPlay(next, el);
  }

  /**
   * Stops the pause music and prunes queued PAUSE-category entries (E11S15 AC6).
   *
   * - If PAUSE is currently playing: stops it, clears currentlyPlaying, drains the queue
   *   (the next non-PAUSE entry, if any, begins playing immediately).
   * - Removes any PAUSE-category entries from the queue regardless.
   * - START and END queued entries are unaffected.
   * - No-op if there is no PAUSE element or nothing PAUSE-related is active (AC12).
   */
  stopPauseMusic(): void {
    // Prune queued PAUSE entries (AC6)
    this.playQueue = this.playQueue.filter(cat => cat !== 'PAUSE');

    // Stop in-progress PAUSE if it is the currently-playing element (AC6)
    const pauseEl = this.elements['PAUSE'];
    if (pauseEl && this.currentlyPlayingCategory === 'PAUSE') {
      pauseEl.pause();
      pauseEl.currentTime = 0;
      this.currentlyPlaying = null;
      this.currentlyPlayingCategory = null;
      // Drain next entry (START or END) if queued
      this._drainQueue();
    } else if (pauseEl) {
      // PAUSE element exists but is not currently playing → just stop/rewind it silently
      pauseEl.pause();
      pauseEl.currentTime = 0;
    }
  }

  /**
   * Pauses all currently-playing audio (transport Pause, AC5).
   * - Pauses all three elements (only the actively playing one will have effect).
   * - Queue is NOT affected (AC7). The paused element does not fire `ended`,
   *   so the drain is naturally suspended until the element resumes.
   */
  pauseAll(): void {
    for (const el of Object.values(this.elements)) {
      if (el && !el.paused) {
        el.pause();
      }
    }
  }

  /**
   * Resumes the last-playing element if it was paused by pauseAll() (transport Play, AC5/AC8).
   * - Queue is NOT affected. When the resumed element eventually fires `ended`, the drain
   *   proceeds per normal (AC8).
   */
  resumeAll(): void {
    for (const el of Object.values(this.elements)) {
      if (el && el.paused && el.currentTime > 0) {
        el.play().catch(() => {});
      }
    }
  }

  /**
   * Stops all audio and clears the entire playback queue (transport Stop, AC5).
   * - After stopAll(), no audio is playing AND no queued entries remain.
   * - A subsequent play() call plays immediately (engine is idle, AC1).
   */
  stopAll(): void {
    // Clear the queue first (AC5) — before stopping the current element to prevent
    // any drain listener from replaying a queued entry
    this.playQueue = [];
    this.currentlyPlaying = null;
    this.currentlyPlayingCategory = null;

    for (const el of Object.values(this.elements)) {
      if (el) {
        el.pause();
        el.currentTime = 0;
      }
    }
  }
}
