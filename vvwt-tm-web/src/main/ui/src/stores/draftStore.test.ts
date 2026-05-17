// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

// ─────────────────────────────────────────────────────────────────────────────
// draftStore — exported API functions (E21S21 signature check)
// ─────────────────────────────────────────────────────────────────────────────

describe('draftStore — exported API functions', () => {
  it('should export all required API functions (E05S06 + E08S05 + E21S21)', async () => {
    const module = await import('./draftStore.ts');
    expect(typeof module.getDraft).toBe('function');
    expect(typeof module.saveDraft).toBe('function');
    expect(typeof module.previewDraft).toBe('function');
    expect(typeof module.applyDraft).toBe('function');
  });

  it('previewDraft should accept 2 arguments (tournamentId + config) — E21S21 AC-IMPL-FE-DRAFTSTORE-BODY', async () => {
    const module = await import('./draftStore.ts');
    // Function arity: previewDraft(tournamentId, config) — length should be 2
    expect(module.previewDraft.length).toBe(2);
  });

  it('applyDraft should accept 2 arguments (tournamentId + config) — E21S21 AC-IMPL-FE-DRAFTSTORE-BODY', async () => {
    const module = await import('./draftStore.ts');
    expect(module.applyDraft.length).toBe(2);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// draftStore — API calls (mocked fetch)
// AC-TEST-FE-PREVIEW-NO-BODY-RED: previewDraft sends Content-Type + JSON body
// AC-TEST-FE-APPLY-NO-BODY-RED: applyDraft sends Content-Type + JSON body
// ─────────────────────────────────────────────────────────────────────────────

describe('draftStore — API calls (mocked fetch)', () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  const TOURNAMENT_ID = '02376d90-f8bf-424c-881e-5468e9d3b938';

  const mockConfig = {
    sections: [
      {
        sectionNumber: 1,
        sortType: 'team_number',
        groupCount: 4,
        gameMode: 'roundrobin',
        lapBreakTimeMinutes: 5,
        sectionBreakTimeMinutes: 10,
        lapTimeMinutes: 15,
        setQuantity: 1,
        breaks: [],
      },
    ],
  };

  beforeEach(() => {
    fetchSpy = vi.fn();
    global.fetch = fetchSpy;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ── previewDraft ──────────────────────────────────────────────────────────

  it('previewDraft — POST /draft/preview with Content-Type and JSON body (AC-TEST-FE-PREVIEW-NO-BODY-RED)', async () => {
    const mockPreview = {
      sections: [{ phaseNumber: 1, groupCount: 4, teamsPerGroup: 2, matchesPerGroup: 1, totalLaps: 1, totalMatches: 4, estimatedTimeMinutes: 60 }],
      timeline: [],
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => mockPreview,
    });

    const { previewDraft } = await import('./draftStore.ts');
    const result = await previewDraft(TOURNAMENT_ID, mockConfig);

    expect(fetchSpy).toHaveBeenCalledOnce();
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(`/api/tournaments/${TOURNAMENT_ID}/draft/preview`);
    expect(init.method).toBe('POST');
    expect(init.headers?.['Content-Type']).toBe('application/json');
    expect(init.body).toBe(JSON.stringify(mockConfig));
    expect(result.sections[0].teamsPerGroup).toBe(2);
  });

  it('previewDraft — throws on non-ok response with status 400', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ message: 'Bad Request' }),
    });

    const { previewDraft } = await import('./draftStore.ts');
    await expect(previewDraft(TOURNAMENT_ID, mockConfig)).rejects.toThrow('Bad Request');
  });

  // ── applyDraft ────────────────────────────────────────────────────────────

  it('applyDraft — POST /draft/apply with Content-Type and JSON body (AC-TEST-FE-APPLY-NO-BODY-RED)', async () => {
    const mockApplyResponse = {
      phaseIds: ['phase-uuid-1'],
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => mockApplyResponse,
    });

    const { applyDraft } = await import('./draftStore.ts');
    const result = await applyDraft(TOURNAMENT_ID, mockConfig);

    expect(fetchSpy).toHaveBeenCalledOnce();
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(`/api/tournaments/${TOURNAMENT_ID}/draft/apply`);
    expect(init.method).toBe('POST');
    expect(init.headers?.['Content-Type']).toBe('application/json');
    expect(init.body).toBe(JSON.stringify(mockConfig));
    expect(result.phaseIds).toContain('phase-uuid-1');
  });

  it('applyDraft — throws on non-ok response with status 409', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ message: 'Draft already applied' }),
    });

    const { applyDraft } = await import('./draftStore.ts');
    await expect(applyDraft(TOURNAMENT_ID, mockConfig)).rejects.toThrow('Draft already applied');
  });

  // ── AC-TEST-FE-LOCAL-STATE-DIVERGENCE-RED ────────────────────────────────
  // previewDraft uses the config passed in (local state), not persisted state

  it('previewDraft — sends the provided config as body (local state, not persisted)', async () => {
    const localSections = [
      {
        sectionNumber: 1,
        sortType: 'ranking',   // local in-memory edit not yet saved
        groupCount: 2,
        gameMode: 'roundrobin',
        lapBreakTimeMinutes: 0,
        sectionBreakTimeMinutes: 0,
        lapTimeMinutes: 10,
        setQuantity: 1,
        breaks: [],
      },
    ];
    const localConfig = { sections: localSections };

    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({ sections: [], timeline: [] }),
    });

    const { previewDraft } = await import('./draftStore.ts');
    await previewDraft(TOURNAMENT_ID, localConfig);

    const [, init] = fetchSpy.mock.calls[0];
    // Body must reflect localConfig (local state), not any other state
    expect(JSON.parse(init.body)).toEqual(localConfig);
    // Exactly one fetch call — no saveDraft PUT call before preview
    expect(fetchSpy).toHaveBeenCalledOnce();
  });
});
