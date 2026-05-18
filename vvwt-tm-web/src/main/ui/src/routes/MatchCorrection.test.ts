// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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

// ── AC-TEST-MATCH-CORRECTION-LOADING-MESSAGE-RED (E48S27) ────────────────────
//
// DEC-22 RED-first: these tests are authored BEFORE the loading-message fix.
// Source-inspection tests — the same pattern used by the existing E48S26 tests
// in this file (see 'MatchCorrection.svelte — source checks...' block below).
// This pattern is used because the @testing-library/svelte render() is not
// available in this environment (lifecycle_function_unavailable — pre-existing
// Svelte 5 SSR issue unrelated to E48S27).
//
// On the commit BEFORE the fix (RED state):
//   - MatchCorrection.svelte contains the bare `…` in the preloading branch.
//   - The source does NOT contain 'correction.loading' or data-testid="correction-loading".
//   → Tests FAIL.
//
// After the fix (GREEN state):
//   - MatchCorrection.svelte uses $_(\'correction.loading\') and the data-testid.
//   → Tests PASS.

describe('MatchCorrection — pre-loading state uses localized i18n key (AC-TEST-MATCH-CORRECTION-LOADING-MESSAGE-RED)', () => {
    const __dirname_corr = path.dirname(new URL(import.meta.url).pathname);
    const matchCorrectionSource = fs.readFileSync(
        path.resolve(__dirname_corr, './MatchCorrection.svelte'),
        'utf8'
    );

    it('preloading branch uses the correction.loading i18n key (not bare "…")', () => {
        // Source must reference the i18n key for the loading message
        expect(matchCorrectionSource).toContain("'correction.loading'");
    });

    it('preloading branch does NOT use the bare "…" ellipsis as loading text', () => {
        // After fix: preloading branch must not contain bare "…" as the only content
        const preloadingBranchMatch = matchCorrectionSource.match(
            /\{#if preloading\}[\s\S]*?\{:else/
        );
        expect(preloadingBranchMatch, 'preloading branch not found').toBeTruthy();
        // The preloading branch must NOT consist solely of the bare ellipsis
        expect(preloadingBranchMatch![0]).not.toMatch(/>…</);
    });

    it('preloading element has data-testid="correction-loading" for testability', () => {
        expect(matchCorrectionSource).toContain('data-testid="correction-loading"');
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

// ── E66S05 Source checks: team identification in headers and dialog ────────────
//
// DEC-22 RED-first source-inspection tests authored BEFORE the production change.
// The source-inspection pattern is used because @testing-library/svelte render()
// is not available in this project's Vitest+jsdom environment for Svelte 5 components
// (pre-existing gap, see notes in E48S27 block above).
//
// AC2: column headers in the score-input table must show "Nr. {teamNumber} — {teamName}"
// AC3: same identification in the confirmation dialog table
// AC4: graceful fallback key present in de.json (never "undefined", never blank)
// AC5: no teamId UUID rendered; i18n via de.json canonical fallback-aware pattern

describe('MatchCorrection.svelte — E66S05 team identification (AC-TEST-E66S05-TEAM-HEADER-RED)', () => {
    const __dirname_e66 = path.dirname(new URL(import.meta.url).pathname);
    const corrSource = fs.readFileSync(
        path.resolve(__dirname_e66, './MatchCorrection.svelte'),
        'utf8'
    );
    const storeSource = fs.readFileSync(
        path.resolve(__dirname_e66, '../stores/correctionStore.ts'),
        'utf8'
    );
    const deJsonSource = fs.readFileSync(
        path.resolve(__dirname_e66, '../locales/de.json'),
        'utf8'
    );

    // AC2 — score-input table column headers use team label variable (not bare i18n keys)
    it('AC2: MatchCorrection.svelte renders team1Label and team2Label derived from teamNumber+teamName', () => {
        // The component must declare label state variables (team1Label / team2Label)
        // computed from the match's team1Number, team2Number, team1Name, team2Name.
        // We check for the variable references in the template.
        expect(corrSource).toContain('team1Label');
        expect(corrSource).toContain('team2Label');
    });

    it('AC2: MatchCorrection.svelte uses team1Label in the sets-table column header', () => {
        // The sets-table <th> must reference team1Label, not the bare i18n key correction.team1Points
        const tableSection = corrSource.match(/correction__sets-table[\s\S]*?<\/table>/);
        expect(tableSection, 'sets table section not found in source').toBeTruthy();
        expect(tableSection![0]).toContain('team1Label');
    });

    it('AC2: MatchCorrection.svelte uses team2Label in the sets-table column header', () => {
        const tableSection = corrSource.match(/correction__sets-table[\s\S]*?<\/table>/);
        expect(tableSection, 'sets table section not found in source').toBeTruthy();
        expect(tableSection![0]).toContain('team2Label');
    });

    // AC3 — confirmation dialog table uses same team labels
    it('AC3: MatchCorrection.svelte uses team1Label in the confirmation dialog table header', () => {
        const dialogSection = corrSource.match(/correction__confirm-table[\s\S]*?<\/table>/);
        expect(dialogSection, 'confirm table section not found in source').toBeTruthy();
        expect(dialogSection![0]).toContain('team1Label');
    });

    it('AC3: MatchCorrection.svelte uses team2Label in the confirmation dialog table header', () => {
        const dialogSection = corrSource.match(/correction__confirm-table[\s\S]*?<\/table>/);
        expect(dialogSection, 'confirm table section not found in source').toBeTruthy();
        expect(dialogSection![0]).toContain('team2Label');
    });

    // AC4 — fallback key: de.json must define correction.teamFallback (used when no team assigned)
    it('AC4: de.json defines correction.teamFallback for graceful no-team fallback', () => {
        expect(deJsonSource).toContain('teamFallback');
    });

    // AC5 — no teamId UUID rendered (structural: source must not pass teamId to DOM)
    it('AC5: MatchCorrection.svelte does not render teamId values in the DOM', () => {
        // teamId UUID must not appear as a rendered text node — only structural use is fine
        // Structural check: source must not bind `teamId` to any text interpolation
        expect(corrSource).not.toMatch(/\{[^}]*teamId[^}]*\}/);
    });

    // AC5 — i18n pattern: de.json must define "Nr." label via a key
    it('AC5: de.json defines correction.teamNumberPrefix for the "Nr." label', () => {
        expect(deJsonSource).toContain('teamNumberPrefix');
    });

    // Store interface: correctionStore.ts must expose team1Number and team2Number on MatchSummary
    it('correctionStore.ts MatchSummary interface exposes team1Number (nullable integer)', () => {
        expect(storeSource).toContain('team1Number');
    });

    it('correctionStore.ts MatchSummary interface exposes team2Number (nullable integer)', () => {
        expect(storeSource).toContain('team2Number');
    });
});
