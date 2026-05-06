/**
 * Unit tests for phaseStore lifecycle functions (E48S06).
 *
 * Verifies (RED-first per DEC-22 TDD Iron Law):
 * - startPhase, completePhase, forceCompletePhase export existence and API contract
 * - i18n: de.json contains all E48S06 phase lifecycle translation keys
 *   (AC-FRONTEND-PHASE-LIFECYCLE-BUTTONS, AC-FRONTEND-COMPLETE-DISABLED-LOGIC,
 *   AC-FRONTEND-FORCE-COMPLETE-CONFIRMATION)
 * - phaseStore exports expected API surface
 */

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import deMessages from '../locales/de.json';

// ─────────────────────────────────────────────────────────────────────────────
// i18n coverage — AC-FRONTEND-PHASE-LIFECYCLE-BUTTONS i18n keys
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — phase lifecycle translations (E48S06)', () => {
  it('should contain phases.startButton i18n key', () => {
    const phases = (deMessages as Record<string, Record<string, string>>).phases;
    expect(phases).toHaveProperty('startButton');
    expect(phases.startButton).toBeTypeOf('string');
    expect(phases.startButton.length).toBeGreaterThan(0);
  });

  it('should contain phases.completeButton i18n key', () => {
    const phases = (deMessages as Record<string, Record<string, string>>).phases;
    expect(phases).toHaveProperty('completeButton');
    expect(phases.completeButton).toBeTypeOf('string');
  });

  it('should contain phases.forceCompleteButton i18n key', () => {
    const phases = (deMessages as Record<string, Record<string, string>>).phases;
    expect(phases).toHaveProperty('forceCompleteButton');
    expect(phases.forceCompleteButton).toBeTypeOf('string');
  });

  it('should contain phases.completeDisabledTooltip i18n key (AC-FRONTEND-COMPLETE-DISABLED-LOGIC)', () => {
    const phases = (deMessages as Record<string, Record<string, string>>).phases;
    expect(phases).toHaveProperty('completeDisabledTooltip');
    expect(phases.completeDisabledTooltip).toBeTypeOf('string');
    expect(phases.completeDisabledTooltip.length).toBeGreaterThan(0);
  });

  it('should contain phases.forceCompleteConfirm i18n key (AC-FRONTEND-FORCE-COMPLETE-CONFIRMATION)', () => {
    const phases = (deMessages as Record<string, Record<string, string>>).phases;
    expect(phases).toHaveProperty('forceCompleteConfirm');
    expect(phases.forceCompleteConfirm).toBeTypeOf('string');
    // Must contain {count} placeholder for dynamic unfinished match count
    expect(phases.forceCompleteConfirm).toContain('{count}');
  });

  it('should contain phases.lifecycleError i18n key', () => {
    const phases = (deMessages as Record<string, Record<string, string>>).phases;
    expect(phases).toHaveProperty('lifecycleError');
    expect(phases.lifecycleError).toBeTypeOf('string');
  });

  it('should contain phases.columns.actions i18n key', () => {
    const cols = (deMessages as Record<string, Record<string, Record<string, string>>>).phases.columns;
    expect(cols).toHaveProperty('actions');
    expect(cols.actions).toBeTypeOf('string');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// phaseStore API surface — exports lifecycle functions (E48S06)
// ─────────────────────────────────────────────────────────────────────────────

describe('phaseStore — lifecycle API exports (E48S06)', () => {
  it('should export startPhase function', async () => {
    const store = await import('./phaseStore.js');
    expect(typeof store.startPhase).toBe('function');
  });

  it('should export completePhase function', async () => {
    const store = await import('./phaseStore.js');
    expect(typeof store.completePhase).toBe('function');
  });

  it('should export forceCompletePhase function', async () => {
    const store = await import('./phaseStore.js');
    expect(typeof store.forceCompletePhase).toBe('function');
  });

  it('should export listPhases function (E48S05 read-path preserved)', async () => {
    const store = await import('./phaseStore.js');
    expect(typeof store.listPhases).toBe('function');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// phaseStore lifecycle API — fetch behaviour (E48S06)
// ─────────────────────────────────────────────────────────────────────────────

describe('phaseStore — startPhase (E48S06)', () => {
  const phaseId = '550e8400-e29b-41d4-a716-446655440000';
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('should call POST /api/phases/{id}/start and resolve on 200', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({}),
    });

    const { startPhase } = await import('./phaseStore.js');
    await expect(startPhase(phaseId)).resolves.toBeUndefined();

    expect(globalThis.fetch).toHaveBeenCalledWith(
      expect.stringContaining(`/api/phases/${phaseId}/start`),
      expect.objectContaining({ method: 'POST' })
    );
  });

  it('should throw Error with message on HTTP 409', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ message: 'Invalid transition' }),
    });

    const { startPhase } = await import('./phaseStore.js');
    await expect(startPhase(phaseId)).rejects.toThrow('Invalid transition');
  });
});

describe('phaseStore — completePhase (E48S06)', () => {
  const phaseId = '550e8400-e29b-41d4-a716-446655440001';
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('should call POST /api/phases/{id}/complete and resolve on 200', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({}),
    });

    const { completePhase } = await import('./phaseStore.js');
    await expect(completePhase(phaseId)).resolves.toBeUndefined();

    expect(globalThis.fetch).toHaveBeenCalledWith(
      expect.stringContaining(`/api/phases/${phaseId}/complete`),
      expect.objectContaining({ method: 'POST' })
    );
  });

  it('should throw Error on HTTP 409 (unfinished matches)', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ message: '3 unfinished match(es) remain' }),
    });

    const { completePhase } = await import('./phaseStore.js');
    await expect(completePhase(phaseId)).rejects.toThrow('unfinished');
  });
});

describe('phaseStore — forceCompletePhase (E48S06)', () => {
  const phaseId = '550e8400-e29b-41d4-a716-446655440002';
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('should call POST /api/phases/{id}/force-complete and resolve on 200', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({}),
    });

    const { forceCompletePhase } = await import('./phaseStore.js');
    await expect(forceCompletePhase(phaseId)).resolves.toBeUndefined();

    expect(globalThis.fetch).toHaveBeenCalledWith(
      expect.stringContaining(`/api/phases/${phaseId}/force-complete`),
      expect.objectContaining({ method: 'POST' })
    );
  });
});
