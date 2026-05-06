/**
 * Unit tests for audioStore — Story E11S08 (AC6).
 *
 * Verifies that each audioStore export issues the canonical Wave-2 URL:
 *   GET    /api/audio/tournaments/${tournamentId}           — listAudio
 *   POST   /api/audio/tournaments/${tournamentId}/${cat}   — uploadAudio
 *   DELETE /api/audio/tournaments/${tournamentId}/${cat}   — deleteAudio
 *   GET    /api/audio/tournaments/${tournamentId}/${cat}/stream — (served via TimerAudio.svelte AC4)
 *
 * DEC-22 RED-first: these specs are committed against the legacy audioStore.ts that
 * still uses `/api/tournaments/${id}/...` paths — they FAIL on first commit (RED),
 * and PASS after AC1–AC3 fixes land (GREEN).
 *
 * Canonical URL strings are hardcoded per Brief Q4=(F) (no shared SoT module).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { listAudio, uploadAudio, deleteAudio } from './audioStore.js';

// ─────────────────────────────────────────────────────────────────────────────
// Shared fixtures
// ─────────────────────────────────────────────────────────────────────────────

const TOURNAMENT_ID = '550e8400-e29b-41d4-a716-446655440000';
const CATEGORY = 'START' as const;

beforeEach(() => {
    vi.restoreAllMocks();
});

// ─────────────────────────────────────────────────────────────────────────────
// AC6 Spec-1: listAudio — canonical GET URL
// ─────────────────────────────────────────────────────────────────────────────

describe('listAudio (AC6 — canonical Wave-2 URL)', () => {
    it('issues GET to /api/audio/tournaments/${tournamentId} (canonical Wave-2)', async () => {
        // Arrange: stub fetch to return an empty array
        const mockFetch = vi.fn().mockResolvedValue({
            ok: true,
            json: vi.fn().mockResolvedValue([]),
        });
        vi.stubGlobal('fetch', mockFetch);

        // Act
        await listAudio(TOURNAMENT_ID);

        // Assert: called with the canonical Wave-2 path (NOT legacy /api/tournaments/{id}/audio)
        expect(mockFetch).toHaveBeenCalledOnce();
        const [calledUrl] = mockFetch.mock.calls[0] as [string, RequestInit];
        expect(calledUrl).toBe(`/api/audio/tournaments/${TOURNAMENT_ID}`);
    });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC6 Spec-2: uploadAudio — canonical POST URL
// ─────────────────────────────────────────────────────────────────────────────

describe('uploadAudio (AC6 — canonical Wave-2 URL)', () => {
    it('issues POST to /api/audio/tournaments/${tournamentId}/${category} (canonical Wave-2)', async () => {
        // Arrange: stub XMLHttpRequest (uploadAudio uses XHR for progress events)
        const xhrMock = {
            withCredentials: false,
            upload: { addEventListener: vi.fn() },
            addEventListener: vi.fn((event: string, handler: () => void) => {
                if (event === 'load') {
                    // Simulate immediate successful load with status 201
                    xhrMock._loadHandler = handler;
                }
            }),
            open: vi.fn(),
            send: vi.fn(() => {
                // Trigger the load handler with a 201 response
                xhrMock.status = 201;
                xhrMock.responseText = JSON.stringify({
                    category: CATEGORY,
                    filename: 'start.mp3',
                    sizeBytes: 1024,
                    uploadedAt: '2026-05-06T12:00:00Z',
                });
                if (xhrMock._loadHandler) {
                    (xhrMock._loadHandler as () => void)();
                }
            }),
            status: 0,
            responseText: '',
            _loadHandler: null as (() => void) | null,
        };
        vi.stubGlobal('XMLHttpRequest', vi.fn(() => xhrMock));

        const file = new File(['audio-data'], 'start.mp3', { type: 'audio/mpeg' });

        // Act
        await uploadAudio(TOURNAMENT_ID, CATEGORY, file);

        // Assert: XHR opened with the canonical Wave-2 POST path
        expect(xhrMock.open).toHaveBeenCalledWith(
            'POST',
            `/api/audio/tournaments/${TOURNAMENT_ID}/${CATEGORY}`,
        );
    });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC6 Spec-3: deleteAudio — canonical DELETE URL
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteAudio (AC6 — canonical Wave-2 URL)', () => {
    it('issues DELETE to /api/audio/tournaments/${tournamentId}/${category} (canonical Wave-2)', async () => {
        // Arrange: stub fetch to return 204
        const mockFetch = vi.fn().mockResolvedValue({
            ok: true,
        });
        vi.stubGlobal('fetch', mockFetch);

        // Act
        await deleteAudio(TOURNAMENT_ID, CATEGORY);

        // Assert: called with the canonical Wave-2 DELETE path
        expect(mockFetch).toHaveBeenCalledOnce();
        const [calledUrl, options] = mockFetch.mock.calls[0] as [string, RequestInit];
        expect(calledUrl).toBe(`/api/audio/tournaments/${TOURNAMENT_ID}/${CATEGORY}`);
        expect(options?.method).toBe('DELETE');
    });
});
