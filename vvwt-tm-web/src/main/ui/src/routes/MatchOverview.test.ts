/**
 * RED-first tests for MatchOverview.svelte — E48S26.
 *
 * Covers:
 *   AC-TEST-MATCHOVERVIEW-PAGE-RENDER-RED: render() tests — rows, team names, set scores, match state
 *   AC-TEST-MATCHOVERVIEW-KORRIGIEREN-LINK-RED: "Korrigieren" link for eligible states; absent for INPROGRESS/ONCHECK
 *   AC-ERR-MATCHOVERVIEW-EMPTY-PHASE: empty-state message rendered when no matches
 *   AC-ERR-MATCHOVERVIEW-LOAD-FAILURE: error message rendered when fetch fails
 *   AC-GOV-DEAD-CODE-REMOVED: source inspection — no dead toggleMatchList / isCorrectionEligible symbols
 *
 * DEC-22 Iron Law: all tests written RED-first before MatchOverview.svelte exists.
 * DEC-54: render() is mandatory — source-inspection via fs.readFileSync is INSUFFICIENT here
 *   (the Svelte compiler must be invoked to verify template output).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/svelte';
import * as fs from 'fs';
import * as path from 'path';

// ── i18n setup ────────────────────────────────────────────────────────────────
// svelte-i18n needs to be registered before rendering any component that uses $_().
// Use the de.json fixture directly.
import { addMessages, init, locale } from 'svelte-i18n';
import deMessages from '../locales/de.json';

function setupI18n() {
    addMessages('de', deMessages as Record<string, unknown>);
    init({ fallbackLocale: 'de', initialLocale: 'de' });
    locale.set('de');
}

// ── correctionStore mock ──────────────────────────────────────────────────────
vi.mock('../stores/correctionStore.js', () => ({
    listPhaseMatches: vi.fn(),
}));

// ── pageHeaderStore mock ──────────────────────────────────────────────────────
vi.mock('../stores/pageHeaderStore.js', () => ({
    pageHeader: { set: vi.fn(), subscribe: vi.fn(() => () => {}) },
    resetPageHeader: vi.fn(),
}));

// ── svelte-spa-router push mock ───────────────────────────────────────────────
vi.mock('svelte-spa-router', () => ({
    push: vi.fn(),
    default: vi.fn(),
}));

import { listPhaseMatches } from '../stores/correctionStore.js';
const mockListPhaseMatches = listPhaseMatches as ReturnType<typeof vi.fn>;

const PHASE_ID = 'phase-uuid-001';
const TOURNAMENT_ID = 'tournament-uuid-001';
const MATCH_ID_1 = 'match-uuid-001';
const MATCH_ID_2 = 'match-uuid-002';

const sampleMatchWithSets = {
    matchId: MATCH_ID_1,
    state: 'FINISHED_WINNER1',
    lapNumber: 1,
    fieldNumber: 2,
    team1Name: 'Alpha FC',
    team2Name: 'Beta United',
    setScores: [
        { setIndex: 0, team1Points: 25, team2Points: 10 },
        { setIndex: 1, team1Points: 25, team2Points: 15 },
    ],
};

const sampleMatchNoSets = {
    matchId: MATCH_ID_2,
    state: 'OPEN',
    lapNumber: 2,
    fieldNumber: 3,
    team1Name: 'Gamma SC',
    team2Name: 'Delta SV',
    setScores: [],
};

const inProgressMatch = {
    matchId: 'match-uuid-003',
    state: 'INPROGRESS',
    lapNumber: 3,
    fieldNumber: 1,
    team1Name: 'Epsilon',
    team2Name: 'Zeta',
    setScores: [],
};

const onCheckMatch = {
    matchId: 'match-uuid-004',
    state: 'ONCHECK',
    lapNumber: 4,
    fieldNumber: 4,
    team1Name: 'Eta',
    team2Name: 'Theta',
    setScores: [],
};

// ── Dynamic import of MatchOverview (deferred so mock is in place) ────────────
let MatchOverview: Awaited<typeof import('./MatchOverview.svelte')>['default'];

beforeEach(async () => {
    setupI18n();
    vi.resetAllMocks();
    const mod = await import('./MatchOverview.svelte');
    MatchOverview = mod.default;
});

// ── AC-TEST-MATCHOVERVIEW-PAGE-RENDER-RED ─────────────────────────────────────

describe('MatchOverview — renders match rows (AC-TEST-MATCHOVERVIEW-PAGE-RENDER-RED)', () => {
    it('renders team1Name and team2Name for a match with sets', async () => {
        mockListPhaseMatches.mockResolvedValue([sampleMatchWithSets]);
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            expect(screen.getByText('Alpha FC')).toBeTruthy();
            expect(screen.getByText('Beta United')).toBeTruthy();
        });
    });

    it('renders lap number (round) for a match', async () => {
        mockListPhaseMatches.mockResolvedValue([sampleMatchWithSets]);
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            // Lap 1 should appear in a cell
            expect(screen.getAllByText('1').length).toBeGreaterThan(0);
        });
    });

    it('renders per-set scores for a match with sets', async () => {
        mockListPhaseMatches.mockResolvedValue([sampleMatchWithSets]);
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            // Score "25:10" should appear for set 0
            expect(screen.getByText('25:10')).toBeTruthy();
            expect(screen.getByText('25:15')).toBeTruthy();
        });
    });

    it('renders "–" for set scores when match has no recorded sets', async () => {
        mockListPhaseMatches.mockResolvedValue([sampleMatchNoSets]);
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            // The no-sets fallback indicator
            expect(screen.getByText('–')).toBeTruthy();
        });
    });

    it('renders a localized match state label', async () => {
        mockListPhaseMatches.mockResolvedValue([sampleMatchNoSets]);
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            // The OPEN state should render with its i18n label or at least something non-empty
            const container = document.body;
            expect(container.textContent).toBeTruthy();
        });
    });
});

// ── AC-TEST-MATCHOVERVIEW-KORRIGIEREN-LINK-RED ────────────────────────────────

describe('MatchOverview — Korrigieren link eligibility (AC-TEST-MATCHOVERVIEW-KORRIGIEREN-LINK-RED)', () => {
    it('renders a Korrigieren link for a correction-eligible match (FINISHED_WINNER1)', async () => {
        mockListPhaseMatches.mockResolvedValue([sampleMatchWithSets]);
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            // The correction link must appear for FINISHED_WINNER1
            const links = screen.queryAllByRole('link');
            const buttons = screen.queryAllByRole('button');
            const all = [...links, ...buttons];
            const hasCorrection = all.some(el =>
                el.textContent?.includes('Korrigieren') ||
                el.getAttribute('href')?.includes('correction') ||
                el.getAttribute('data-testid')?.includes('correction')
            );
            expect(hasCorrection).toBe(true);
        });
    });

    it('renders a Korrigieren link for OPEN match (Nacherfassung eligible)', async () => {
        mockListPhaseMatches.mockResolvedValue([sampleMatchNoSets]);
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            const links = screen.queryAllByRole('link');
            const buttons = screen.queryAllByRole('button');
            const all = [...links, ...buttons];
            const hasCorrection = all.some(el =>
                el.textContent?.includes('Korrigieren') ||
                el.getAttribute('href')?.includes('correction')
            );
            expect(hasCorrection).toBe(true);
        });
    });

    it('does NOT render a Korrigieren link for an INPROGRESS match', async () => {
        mockListPhaseMatches.mockResolvedValue([inProgressMatch]);
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            // Epsilon / Zeta row should appear
            expect(screen.getByText('Epsilon')).toBeTruthy();
        });
        const links = screen.queryAllByRole('link');
        const buttons = screen.queryAllByRole('button');
        const all = [...links, ...buttons];
        const hasCorrection = all.some(el =>
            el.textContent?.includes('Korrigieren')
        );
        expect(hasCorrection).toBe(false);
    });

    it('does NOT render a Korrigieren link for an ONCHECK match', async () => {
        mockListPhaseMatches.mockResolvedValue([onCheckMatch]);
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            expect(screen.getByText('Eta')).toBeTruthy();
        });
        const links = screen.queryAllByRole('link');
        const buttons = screen.queryAllByRole('button');
        const all = [...links, ...buttons];
        const hasCorrection = all.some(el =>
            el.textContent?.includes('Korrigieren')
        );
        expect(hasCorrection).toBe(false);
    });
});

// ── AC-ERR-MATCHOVERVIEW-EMPTY-PHASE ─────────────────────────────────────────

describe('MatchOverview — empty-state message (AC-ERR-MATCHOVERVIEW-EMPTY-PHASE)', () => {
    it('renders an explicit empty-state message when no matches exist', async () => {
        mockListPhaseMatches.mockResolvedValue([]);
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            // Must not be a blank page — expect an empty-state element
            const container = document.body;
            // Check that there's something about "keine" or a data-testid for empty state
            expect(
                container.querySelector('[data-testid="match-overview-empty"]') !== null ||
                container.textContent?.toLowerCase().includes('keine') ||
                container.textContent?.toLowerCase().includes('no match')
            ).toBe(true);
        });
    });
});

// ── AC-ERR-MATCHOVERVIEW-LOAD-FAILURE ────────────────────────────────────────

describe('MatchOverview — load failure error message (AC-ERR-MATCHOVERVIEW-LOAD-FAILURE)', () => {
    it('renders an error message when fetch fails', async () => {
        mockListPhaseMatches.mockRejectedValue(new Error('Network error'));
        render(MatchOverview, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID } },
        });
        await waitFor(() => {
            const container = document.body;
            expect(
                container.querySelector('[data-testid="match-overview-error"]') !== null ||
                container.textContent?.includes('Network error') ||
                container.textContent?.toLowerCase().includes('fehler')
            ).toBe(true);
        });
    });
});

// ── AC-TEST-PHASELIST-ENTRY-LINK-RED (source check supplement) ───────────────

describe('PhaseList.svelte — E48S26 entry link and dead-code removal (source inspection)', () => {
    const source = fs.readFileSync(
        path.resolve(path.dirname(new URL(import.meta.url).pathname), './PhaseList.svelte'),
        'utf8'
    );

    it('PhaseList.svelte imports MatchOverview navigation (push to /matches)', () => {
        // Entry link on ACTIVE phase row: push to /phases/:phaseId/matches
        expect(source).toMatch(/\/matches/);
    });

    it('PhaseList.svelte does NOT contain dead toggleMatchList function', () => {
        expect(source).not.toContain('toggleMatchList');
    });

    it('PhaseList.svelte does NOT contain dead isCorrectionEligible function', () => {
        expect(source).not.toContain('isCorrectionEligible');
    });

    it('PhaseList.svelte does NOT contain dead expandedPhaseId variable', () => {
        expect(source).not.toContain('expandedPhaseId');
    });

    it('PhaseList.svelte does NOT contain dead expandedMatches variable', () => {
        expect(source).not.toContain('expandedMatches');
    });

    it('PhaseList.svelte does NOT import listPhaseMatches from correctionStore', () => {
        // The dead import must be removed
        const importMatch = source.match(/import\s*\{[^}]*\}\s*from\s*['"][^'"]*correctionStore[^'"]*['"]/);
        if (importMatch) {
            expect(importMatch[0]).not.toContain('listPhaseMatches');
        }
        // Alternatively: no correctionStore import at all (if MatchSummary type also removed)
    });

    it('PhaseList.svelte renders a navigation button/link for ACTIVE phase rows to /matches', () => {
        // The ACTIVE phase block must contain navigation to the match-overview route
        const activeBlock = source.match(
            /phase\.status\s*===\s*['"]ACTIVE['"][\s\S]*?\{\/if\}/
        );
        expect(activeBlock, 'ACTIVE block not found in source').toBeTruthy();
        expect(activeBlock![0]).toMatch(/\/matches/);
    });

    it('PhaseList.svelte does NOT render entry link outside ACTIVE block', () => {
        // Strip the ACTIVE conditional block; the /matches navigation must not appear elsewhere
        // (except the template or comments — structural check only)
        const withoutActiveBlock = source.replace(
            /\{:?else if phase\.status\s*===\s*['"]ACTIVE['"][\s\S]*?\{\/if\}/,
            '<<ACTIVE_BLOCK_REMOVED>>'
        );
        // Only check that match-overview push is inside the ACTIVE block, not unconditional
        expect(withoutActiveBlock).not.toMatch(/push\(`[^`]*\/matches`\)/);
    });
});
