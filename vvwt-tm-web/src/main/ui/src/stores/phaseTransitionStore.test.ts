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
