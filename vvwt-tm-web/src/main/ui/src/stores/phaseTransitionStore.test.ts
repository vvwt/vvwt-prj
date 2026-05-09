/**
 * RED-first tests for phaseTransitionStore (E48S08).
 *
 * Verifies (DEC-22 TDD Iron Law):
 * - API exports: fetchProposal, commitTransition
 * - i18n: de.json contains all phaseTransition.* keys (AC-FRONTEND-VITEST-COMPLEMENTARY-COVERAGE-RED)
 * - fetch behaviour: proposal GET and commit POST payloads
 *
 * AC-FRONTEND-LOAD-PROPOSAL, AC-FRONTEND-COMMIT-BUTTON, AC-FRONTEND-COMMIT-PAYLOAD-SHAPE,
 * AC-FRONTEND-VITEST-COMPLEMENTARY-COVERAGE-RED
 */

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import deMessages from '../locales/de.json';

// ── i18n coverage — phaseTransition keys (AC-FRONTEND-VITEST-COMPLEMENTARY-COVERAGE-RED) ──

describe('de.json — phaseTransition translations (E48S08)', () => {
    it('should contain phaseTransition.pageTitle i18n key', () => {
        const pt = (deMessages as unknown as Record<string, Record<string, string>>).phaseTransition;
        expect(pt).toBeDefined();
        expect(pt).toHaveProperty('pageTitle');
        expect(pt.pageTitle).toBeTypeOf('string');
        expect(pt.pageTitle.length).toBeGreaterThan(0);
    });

    it('should contain phaseTransition.commitButton i18n key', () => {
        const pt = (deMessages as unknown as Record<string, Record<string, string>>).phaseTransition;
        expect(pt).toHaveProperty('commitButton');
        expect(pt.commitButton).toBeTypeOf('string');
        expect(pt.commitButton.length).toBeGreaterThan(0);
    });

    it('should contain phaseTransition.cancelButton i18n key', () => {
        const pt = (deMessages as unknown as Record<string, Record<string, string>>).phaseTransition;
        expect(pt).toHaveProperty('cancelButton');
        expect(pt.cancelButton).toBeTypeOf('string');
        expect(pt.cancelButton.length).toBeGreaterThan(0);
    });

    it('should contain phaseTransition.loading i18n key', () => {
        const pt = (deMessages as unknown as Record<string, Record<string, string>>).phaseTransition;
        expect(pt).toHaveProperty('loading');
        expect(pt.loading).toBeTypeOf('string');
        expect(pt.loading.length).toBeGreaterThan(0);
    });

    it('should contain phaseTransition.errorBanner i18n key', () => {
        const pt = (deMessages as unknown as Record<string, Record<string, string>>).phaseTransition;
        expect(pt).toHaveProperty('errorBanner');
        expect(pt.errorBanner).toBeTypeOf('string');
        expect(pt.errorBanner.length).toBeGreaterThan(0);
    });

    it('should contain phaseTransition.commitError i18n key', () => {
        const pt = (deMessages as unknown as Record<string, Record<string, string>>).phaseTransition;
        expect(pt).toHaveProperty('commitError');
        expect(pt.commitError).toBeTypeOf('string');
        expect(pt.commitError.length).toBeGreaterThan(0);
    });
});

// ── API surface — phaseTransitionStore exports (E48S08) ─────────────────────

describe('phaseTransitionStore — API exports (E48S08)', () => {
    it('should export fetchProposal function', async () => {
        const store = await import('./phaseTransitionStore.js');
        expect(typeof store.fetchProposal).toBe('function');
    });

    it('should export commitTransition function', async () => {
        const store = await import('./phaseTransitionStore.js');
        expect(typeof store.commitTransition).toBe('function');
    });
});

// ── fetchProposal — GET /api/phases/{id}/transition-proposal ────────────────

describe('phaseTransitionStore — fetchProposal (E48S08, AC-FRONTEND-LOAD-PROPOSAL)', () => {
    const phaseId = '550e8400-e29b-41d4-a716-446655440010';
    let originalFetch: typeof globalThis.fetch;

    beforeEach(() => {
        originalFetch = globalThis.fetch;
    });

    afterEach(() => {
        globalThis.fetch = originalFetch;
        vi.restoreAllMocks();
    });

    it('should call GET /api/phases/{id}/transition-proposal and return array on 200', async () => {
        const mockSlots = [
            { teamId: 'team-1', groupNumber: 1, groupPosition: 1 },
            { teamId: 'team-2', groupNumber: 1, groupPosition: 2 },
        ];
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => mockSlots,
        });

        const { fetchProposal } = await import('./phaseTransitionStore.js');
        const result = await fetchProposal(phaseId);

        expect(result).toEqual(mockSlots);
        expect(globalThis.fetch).toHaveBeenCalledWith(
            expect.stringContaining(`/api/phases/${phaseId}/transition-proposal`),
            expect.objectContaining({ credentials: 'same-origin' })
        );
    });

    it('should throw Error with message on HTTP 400 (draft_json null)', async () => {
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: false,
            status: 400,
            json: async () => ({ message: 'draft_json is null' }),
        });

        const { fetchProposal } = await import('./phaseTransitionStore.js');
        await expect(fetchProposal(phaseId)).rejects.toThrow('draft_json is null');
    });

    it('should throw Error with HTTP status fallback when no message', async () => {
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: false,
            status: 500,
            json: async () => ({}),
        });

        const { fetchProposal } = await import('./phaseTransitionStore.js');
        await expect(fetchProposal(phaseId)).rejects.toThrow('HTTP 500');
    });
});

// ── commitTransition — POST /api/phases/{id}/transition-commit ──────────────

describe('phaseTransitionStore — commitTransition (E48S08, AC-FRONTEND-COMMIT-PAYLOAD-SHAPE)', () => {
    const phaseId = '550e8400-e29b-41d4-a716-446655440011';
    const assignments = [
        { teamId: 'team-1', groupNumber: 2, groupPosition: 1 },
        { teamId: 'team-2', groupNumber: 1, groupPosition: 2 },
    ];
    let originalFetch: typeof globalThis.fetch;

    beforeEach(() => {
        originalFetch = globalThis.fetch;
    });

    afterEach(() => {
        globalThis.fetch = originalFetch;
        vi.restoreAllMocks();
    });

    it('should call POST /api/phases/{id}/transition-commit with JSON body on 200', async () => {
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({}),
        });

        const { commitTransition } = await import('./phaseTransitionStore.js');
        await expect(commitTransition(phaseId, assignments)).resolves.toBeUndefined();

        expect(globalThis.fetch).toHaveBeenCalledWith(
            expect.stringContaining(`/api/phases/${phaseId}/transition-commit`),
            expect.objectContaining({
                method: 'POST',
                headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
            })
        );

        // Verify payload shape: array of {teamId, groupNumber, groupPosition} (AC-FRONTEND-COMMIT-PAYLOAD-SHAPE)
        const callArgs = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
        const body = JSON.parse(callArgs[1].body as string) as typeof assignments;
        expect(body).toHaveLength(2);
        expect(body[0]).toHaveProperty('teamId');
        expect(body[0]).toHaveProperty('groupNumber');
        expect(body[0]).toHaveProperty('groupPosition');
        // phaseId must NOT appear in the body (it is in the URL only)
        expect(body[0]).not.toHaveProperty('phaseId');
    });

    it('should throw Error with message on HTTP 400', async () => {
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: false,
            status: 400,
            json: async () => ({ message: 'Invalid assignment' }),
        });

        const { commitTransition } = await import('./phaseTransitionStore.js');
        await expect(commitTransition(phaseId, assignments)).rejects.toThrow('Invalid assignment');
    });
});

// ── commitEndpoint override — AC-FRONTEND-PREPARE-COMMIT-CALLS-PREPARE-ENDPOINT-RED (E48S18) ──

describe('phaseTransitionStore — commitEndpoint override (E48S18, AC-FRONTEND-PREPARE-COMMIT-CALLS-PREPARE-ENDPOINT-RED)', () => {
    const phaseId = '550e8400-e29b-41d4-a716-446655440020';
    const assignments = [
        { teamId: 'team-1', groupNumber: 1, groupPosition: 1 },
    ];
    let originalFetch: typeof globalThis.fetch;

    beforeEach(() => {
        originalFetch = globalThis.fetch;
    });

    afterEach(() => {
        globalThis.fetch = originalFetch;
        vi.restoreAllMocks();
    });

    it('should call POST /api/phases/{id}/prepare when commitEndpoint override is provided (AC-FRONTEND-PREPARE-COMMIT-CALLS-PREPARE-ENDPOINT-RED)', async () => {
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({}),
        });

        const { commitTransition } = await import('./phaseTransitionStore.js');
        const prepareEndpoint = `/api/phases/${phaseId}/prepare`;
        await commitTransition(phaseId, assignments, prepareEndpoint);

        // Verify the override URL was used — NOT transition-commit
        expect(globalThis.fetch).toHaveBeenCalledWith(
            expect.stringContaining(`/api/phases/${phaseId}/prepare`),
            expect.objectContaining({ method: 'POST' })
        );
        expect(globalThis.fetch).not.toHaveBeenCalledWith(
            expect.stringContaining('transition-commit'),
            expect.anything()
        );
    });

    it('should fall back to transition-commit when no commitEndpoint override is given (regression)', async () => {
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({}),
        });

        const { commitTransition } = await import('./phaseTransitionStore.js');
        await commitTransition(phaseId, assignments);

        // Default URL must still be transition-commit (existing Phase 2+ path)
        expect(globalThis.fetch).toHaveBeenCalledWith(
            expect.stringContaining('transition-commit'),
            expect.objectContaining({ method: 'POST' })
        );
    });
});

// ── E51S13 RED-first tests ────────────────────────────────────────────────────

/**
 * AC-TEST-FRONTEND-LABEL-PHASE-1-RED (E51S13):
 * sourceLabelBySortType with sortType="team_number" and teamNumber=5 returns "Nr. 5".
 *
 * DEC-22 Iron Law: written before sourceLabelBySortType exists → RED.
 */
describe('phaseTransitionStore — sourceLabelBySortType Phase-1 label (AC-TEST-FRONTEND-LABEL-PHASE-1-RED)', () => {
    it('returns "Nr. 5" for sortType=team_number, teamNumber=5 (AC-TEST-FRONTEND-LABEL-PHASE-1-RED)', async () => {
        const store = await import('./phaseTransitionStore.js');
        // sourceLabelBySortType must exist as an exported function
        expect(typeof (store as Record<string, unknown>)['sourceLabelBySortType']).toBe('function');
        const sourceLabelBySortType = (store as Record<string, unknown>)['sourceLabelBySortType'] as
            (slot: Record<string, unknown>, t: (key: string, opts?: Record<string, unknown>) => string) => string;
        // Minimal i18n translator: returns "Nr. {n}" pattern
        const t = (key: string, opts?: Record<string, unknown>) => {
            if (key === 'phaseTransition.sourceLabelPhase1') return opts?.values ? `Nr. ${(opts.values as Record<string, unknown>)['n']}` : 'Nr. {n}';
            if (key === 'phaseTransition.sourceLabelPhase2plus') return opts?.values ? `Gruppe ${(opts.values as Record<string, unknown>)['g']}, Platz ${(opts.values as Record<string, unknown>)['p']}` : 'Gruppe {g}, Platz {p}';
            return key;
        };
        const slot = { teamId: 'uuid-1', teamNumber: 5, teamDescription: 'TSV A', groupNumber: 1, groupPosition: 1, sourceGroupNumber: null, sourceGroupPosition: null, sortType: 'team_number' };
        const label = sourceLabelBySortType(slot, t);
        expect(label).toContain('5');
        expect(label).not.toContain('undefined');
    });

    it('returns "Gruppe 2, Platz 3" for sortType=placement_group, group=2, pos=3 (AC-TEST-FRONTEND-LABEL-PHASE-2-RED)', async () => {
        const store = await import('./phaseTransitionStore.js');
        const sourceLabelBySortType = (store as Record<string, unknown>)['sourceLabelBySortType'] as
            (slot: Record<string, unknown>, t: (key: string, opts?: Record<string, unknown>) => string) => string;
        const t = (key: string, opts?: Record<string, unknown>) => {
            if (key === 'phaseTransition.sourceLabelPhase1') return opts?.values ? `Nr. ${(opts.values as Record<string, unknown>)['n']}` : 'Nr. {n}';
            if (key === 'phaseTransition.sourceLabelPhase2plus') return opts?.values ? `Gruppe ${(opts.values as Record<string, unknown>)['g']}, Platz ${(opts.values as Record<string, unknown>)['p']}` : 'Gruppe {g}, Platz {p}';
            return key;
        };
        const slot = { teamId: 'uuid-1', teamNumber: 5, teamDescription: 'TSV A', groupNumber: 1, groupPosition: 1, sourceGroupNumber: 2, sourceGroupPosition: 3, sortType: 'placement_group' };
        const label = sourceLabelBySortType(slot, t);
        expect(label).toContain('2');
        expect(label).toContain('3');
        expect(label).not.toContain('undefined');
    });

    it('returns non-undefined string for null sortType (AC-ERROR-HANDLING-NULL-SORTTYPE)', async () => {
        const store = await import('./phaseTransitionStore.js');
        const sourceLabelBySortType = (store as Record<string, unknown>)['sourceLabelBySortType'] as
            (slot: Record<string, unknown>, t: (key: string, opts?: Record<string, unknown>) => string) => string;
        const t = (_key: string, _opts?: Record<string, unknown>) => '';
        const slot = { teamId: 'uuid-1', teamNumber: 5, teamDescription: 'TSV A', groupNumber: 1, groupPosition: 1, sourceGroupNumber: null, sourceGroupPosition: null, sortType: null };
        const label = sourceLabelBySortType(slot, t);
        expect(label).not.toContain('undefined');
    });

    it('returns non-undefined string for unknown sortType (AC-ERROR-HANDLING-UNKNOWN-SORTTYPE)', async () => {
        const store = await import('./phaseTransitionStore.js');
        const sourceLabelBySortType = (store as Record<string, unknown>)['sourceLabelBySortType'] as
            (slot: Record<string, unknown>, t: (key: string, opts?: Record<string, unknown>) => string) => string;
        const t = (_key: string, _opts?: Record<string, unknown>) => '';
        const slot = { teamId: 'uuid-1', teamNumber: 5, teamDescription: 'TSV A', groupNumber: 1, groupPosition: 1, sourceGroupNumber: null, sourceGroupPosition: null, sortType: 'future_unknown_mode' };
        const label = sourceLabelBySortType(slot, t);
        expect(label).not.toContain('undefined');
    });
});

/**
 * AC-TEST-FRONTEND-NO-UNDEFINED-RENDER-RED (E51S13):
 * For ANY slot input combination, the label never contains "undefined".
 */
describe('phaseTransitionStore — no "undefined" rendering for any slot (AC-TEST-FRONTEND-NO-UNDEFINED-RENDER-RED)', () => {
    it('never produces "undefined" label for Phase-1 slot with null source fields', async () => {
        const store = await import('./phaseTransitionStore.js');
        const sourceLabelBySortType = (store as Record<string, unknown>)['sourceLabelBySortType'] as
            (slot: Record<string, unknown>, t: (key: string, opts?: Record<string, unknown>) => string) => string;
        const t = (key: string, opts?: Record<string, unknown>) => {
            if (key === 'phaseTransition.sourceLabelPhase1') return opts?.values ? `Nr. ${(opts.values as Record<string, unknown>)['n']}` : 'Nr. {n}';
            if (key === 'phaseTransition.sourceLabelPhase2plus') return opts?.values ? `Gruppe ${(opts.values as Record<string, unknown>)['g']}, Platz ${(opts.values as Record<string, unknown>)['p']}` : 'Gruppe {g}, Platz {p}';
            return key;
        };
        const variants = [
            { teamId: 'u1', teamNumber: 1, teamDescription: 'T1', groupNumber: 1, groupPosition: 1, sourceGroupNumber: null, sourceGroupPosition: null, sortType: 'team_number' },
            { teamId: 'u2', teamNumber: 2, teamDescription: 'T2', groupNumber: 1, groupPosition: 2, sourceGroupNumber: 1, sourceGroupPosition: 1, sortType: 'placement_group' },
            { teamId: 'u3', teamNumber: 3, teamDescription: 'T3', groupNumber: 2, groupPosition: 1, sourceGroupNumber: 2, sourceGroupPosition: 1, sortType: 'group_placement' },
            { teamId: 'u4', teamNumber: 4, teamDescription: 'T4', groupNumber: 1, groupPosition: 1, sourceGroupNumber: undefined, sourceGroupPosition: undefined, sortType: null },
        ];
        for (const slot of variants) {
            const label = sourceLabelBySortType(slot as Record<string, unknown>, t);
            expect(label).not.toContain('undefined');
        }
    });
});

/**
 * AC-TEST-HASSOURCESLOT-DELETED-RED (E51S13):
 * hasSourceSlot must NOT be exported from phaseTransitionStore after this story.
 *
 * DEC-22: test written before hasSourceSlot is deleted — passes AFTER deletion.
 * At test-write time (pre-fix), hasSourceSlot exists → test FAILS (RED).
 */
describe('phaseTransitionStore — hasSourceSlot deleted after E51S13 (AC-TEST-HASSOURCESLOT-DELETED-RED)', () => {
    it('phaseTransitionStore does NOT export hasSourceSlot (AC-TEST-HASSOURCESLOT-DELETED-RED)', async () => {
        const store = await import('./phaseTransitionStore.js');
        expect((store as Record<string, unknown>)['hasSourceSlot']).toBeUndefined();
    });
});
