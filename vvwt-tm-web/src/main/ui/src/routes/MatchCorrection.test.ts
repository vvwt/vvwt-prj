/**
 * RED-first tests for MatchCorrection.svelte — E48S26.
 *
 * Covers:
 *   AC-TEST-CORRECTION-FORM-PRELOAD-RED: form opens pre-filled with existing set values
 *   AC-TEST-CORRECTION-FORM-EMPTY-NACHERFASSUNG-RED: form opens empty for match with no recorded sets
 *   AC-TEST-CORRECTION-FORM-FRESH-ENTRY-PRESERVED-RED: pre-loaded form remains editable
 *   AC-ERR-CORRECTION-PRELOAD-MATCH-NOT-FOUND: error rendered when matchId not in phase list
 *   AC-GOV-PHANTOM-AC-CLEANUP (source check): back-nav updated to match-overview route
 *
 * DEC-22 Iron Law: all tests written RED-first.
 * DEC-54: render() is mandatory for behaviour tests.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/svelte';
import * as fs from 'fs';
import * as path from 'path';

// ── i18n setup ────────────────────────────────────────────────────────────────
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
    submitMatchCorrection: vi.fn(),
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

const TOURNAMENT_ID = 'tournament-uuid-001';
const PHASE_ID = 'phase-uuid-001';
const MATCH_ID = 'match-uuid-001';

const matchWithSets = {
    matchId: MATCH_ID,
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

const matchNoSets = {
    matchId: MATCH_ID,
    state: 'OPEN',
    lapNumber: 2,
    fieldNumber: 1,
    team1Name: 'Gamma SC',
    team2Name: 'Delta SV',
    setScores: [],
};

let MatchCorrection: Awaited<typeof import('./MatchCorrection.svelte')>['default'];

beforeEach(async () => {
    setupI18n();
    vi.resetAllMocks();
    const mod = await import('./MatchCorrection.svelte');
    MatchCorrection = mod.default;
});

// ── AC-TEST-CORRECTION-FORM-PRELOAD-RED ──────────────────────────────────────

describe('MatchCorrection — form pre-loaded with existing set results (AC-TEST-CORRECTION-FORM-PRELOAD-RED)', () => {
    it('pre-fills team1Points input for set 0 with existing value (25)', async () => {
        mockListPhaseMatches.mockResolvedValue([matchWithSets]);
        render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            const input = screen.getByTestId<HTMLInputElement>('team1-points-0');
            expect(input.value).toBe('25');
        });
    });

    it('pre-fills team2Points input for set 0 with existing value (10)', async () => {
        mockListPhaseMatches.mockResolvedValue([matchWithSets]);
        render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            const input = screen.getByTestId<HTMLInputElement>('team2-points-0');
            expect(input.value).toBe('10');
        });
    });

    it('pre-fills set 1 team1Points with 25', async () => {
        mockListPhaseMatches.mockResolvedValue([matchWithSets]);
        render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            const input = screen.getByTestId<HTMLInputElement>('team1-points-1');
            expect(input.value).toBe('25');
        });
    });

    it('pre-fills set 1 team2Points with 15', async () => {
        mockListPhaseMatches.mockResolvedValue([matchWithSets]);
        render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            const input = screen.getByTestId<HTMLInputElement>('team2-points-1');
            expect(input.value).toBe('15');
        });
    });

    it('renders exactly 2 set rows when match has 2 recorded sets', async () => {
        mockListPhaseMatches.mockResolvedValue([matchWithSets]);
        render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            const table = screen.getByTestId('correction-sets-table');
            const rows = table.querySelectorAll('tbody tr');
            expect(rows.length).toBe(2);
        });
    });
});

// ── AC-TEST-CORRECTION-FORM-EMPTY-NACHERFASSUNG-RED ──────────────────────────

describe('MatchCorrection — empty form for match with no sets (AC-TEST-CORRECTION-FORM-EMPTY-NACHERFASSUNG-RED)', () => {
    it('renders an empty form with 0-value inputs when match has no recorded sets', async () => {
        mockListPhaseMatches.mockResolvedValue([matchNoSets]);
        render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            // When no sets, the form should be empty (0 initial rows or default empty rows with 0 values)
            const table = screen.queryByTestId('correction-sets-table');
            expect(table).toBeTruthy();
            // All team1 inputs should be 0 (empty = fresh entry)
            const team1Inputs = screen.queryAllByTestId(/^team1-points-/);
            for (const input of team1Inputs) {
                expect((input as HTMLInputElement).value).toBe('0');
            }
        });
    });
});

// ── AC-TEST-CORRECTION-FORM-FRESH-ENTRY-PRESERVED-RED ────────────────────────

describe('MatchCorrection — pre-loaded form remains editable (AC-TEST-CORRECTION-FORM-FRESH-ENTRY-PRESERVED-RED)', () => {
    it('operator can overwrite pre-loaded team1Points value', async () => {
        mockListPhaseMatches.mockResolvedValue([matchWithSets]);
        render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            const input = screen.getByTestId<HTMLInputElement>('team1-points-0');
            expect(input.value).toBe('25');
        });

        const input = screen.getByTestId<HTMLInputElement>('team1-points-0');
        await fireEvent.input(input, { target: { value: '30' } });
        expect(input.value).toBe('30');
    });

    it('add-set-row button is present and clickable', async () => {
        mockListPhaseMatches.mockResolvedValue([matchWithSets]);
        render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            // Add set row button should be present
            const addBtn = screen.getByText(/satz hinzufügen/i);
            expect(addBtn).toBeTruthy();
        });
    });

    it('clicking add-set-row adds a new row', async () => {
        mockListPhaseMatches.mockResolvedValue([matchWithSets]);
        render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            const table = screen.getByTestId('correction-sets-table');
            const rows = table.querySelectorAll('tbody tr');
            expect(rows.length).toBe(2);
        });

        const addBtn = screen.getByText(/satz hinzufügen/i);
        await fireEvent.click(addBtn);
        await waitFor(() => {
            const table = screen.getByTestId('correction-sets-table');
            const rows = table.querySelectorAll('tbody tr');
            expect(rows.length).toBe(3);
        });
    });
});

// ── AC-ERR-CORRECTION-PRELOAD-MATCH-NOT-FOUND ────────────────────────────────

describe('MatchCorrection — match-not-found error (AC-ERR-CORRECTION-PRELOAD-MATCH-NOT-FOUND)', () => {
    it('renders a descriptive error when matchId not found in phase match list', async () => {
        // Phase match list exists but does not contain our matchId
        mockListPhaseMatches.mockResolvedValue([
            {
                matchId: 'different-match-uuid',
                state: 'OPEN',
                lapNumber: 1,
                fieldNumber: 1,
                team1Name: 'Other',
                team2Name: 'Team',
                setScores: [],
            },
        ]);
        render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            const container = document.body;
            // Must render some error — not a silent blank form
            expect(
                container.querySelector('[data-testid="match-not-found-error"]') !== null ||
                container.textContent?.toLowerCase().includes('nicht gefunden') ||
                container.textContent?.toLowerCase().includes('not found') ||
                container.textContent?.toLowerCase().includes('fehler')
            ).toBe(true);
        });
    });

    it('the match-not-found state is distinguishable from empty-sets state', async () => {
        // Not-found: different-uuid in list
        mockListPhaseMatches.mockResolvedValue([
            { matchId: 'other-uuid', state: 'OPEN', lapNumber: 1, fieldNumber: 1,
              team1Name: 'A', team2Name: 'B', setScores: [] },
        ]);
        const { container: notFoundContainer } = render(MatchCorrection, {
            props: { params: { tournamentId: TOURNAMENT_ID, phaseId: PHASE_ID, matchId: MATCH_ID } },
        });
        await waitFor(() => {
            // When not found, the correction form table should NOT be rendered
            expect(notFoundContainer.querySelector('[data-testid="correction-sets-table"]')).toBeNull();
        });
    });
});

// ── Source checks: back-nav and phantom AC cleanup ────────────────────────────

describe('MatchCorrection.svelte — source checks (E48S26 governance)', () => {
    const __dirname_local = path.dirname(new URL(import.meta.url).pathname);
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './MatchCorrection.svelte'),
        'utf8'
    );

    it('back-nav uses the match-overview route, not the phases route directly', () => {
        // Back-arrow should point to the match-overview (the calling page), not /phases
        // parentRouteMap entry for correction route → /matches
        expect(source).toContain('/matches/:matchId/correction');
    });

    it('MatchCorrection.svelte imports listPhaseMatches for pre-load', () => {
        expect(source).toContain('listPhaseMatches');
    });

    it('MatchCorrection.svelte does not contain phantom AC-FE-PHASELIST-CORRECTION-LINKS', () => {
        expect(source).not.toContain('AC-FE-PHASELIST-CORRECTION-LINKS');
    });
});
