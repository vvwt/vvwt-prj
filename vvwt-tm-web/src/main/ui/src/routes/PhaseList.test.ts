/**
 * RED-first tests for PhaseList.svelte (E48S19).
 *
 * Covers:
 *   - AC-TEST-PHASELIST-PREPARE-NAVIGATES-RED: handlePrepare navigates to /prepare route, NOT preparePhase API
 *   - AC-TEST-PHASELIST-START-UNCHANGED-GREEN: handleStart regression — push() never called
 *   - AC-TEST-I18N-PHASES-COLUMNS-RESOLVE-RED: de.json has all 7 phases.columns keys
 *   - AC-ERROR-HANDLING-INVALID-NAVIGATION-CONTEXT: handlePrepare sets actionError when context invalid
 *
 * Source-code structural checks (pattern: PhaseTransition.test.ts / fs.readFileSync).
 * DEC-22 Iron Law: all tests written RED-first before production-code changes.
 */

import { describe, expect, it } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import deMessages from '../locales/de.json';

const __dirname_local = path.dirname(new URL(import.meta.url).pathname);

// ── AC-TEST-PHASELIST-PREPARE-NAVIGATES-RED ───────────────────────────────────
// handlePrepare must navigate to /prepare route — NOT call preparePhase() API.
// Verified via source-code structural check: handlePrepare must contain push() call
// with /prepare and must NOT call preparePhase() in the same handler.

describe('PhaseList.svelte — handlePrepare navigates to prepare route (AC-TEST-PHASELIST-PREPARE-NAVIGATES-RED)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it('handlePrepare function calls push() with /prepare path', () => {
        // The function body of handlePrepare must contain push(`...prepare`)
        // Use a lookahead on the next function declaration to bound the match.
        const handlePrepareMatch = source.match(
            /function handlePrepare[\s\S]*?(?=\n  (?:async )?function \w)/
        );
        expect(handlePrepareMatch, 'handlePrepare function not found in source').toBeTruthy();
        const handlePrepareBody = handlePrepareMatch![0];
        expect(handlePrepareBody).toContain('push(');
        expect(handlePrepareBody).toContain('/prepare');
    });

    it('handlePrepare function does NOT call preparePhase() directly', () => {
        // After the fix, handlePrepare must NOT call preparePhase() — it only navigates
        const handlePrepareMatch = source.match(
            /function handlePrepare\(phaseId[^)]*\)[^{]*\{[^}]*(?:\{[^}]*\}[^}]*)*\}/
        );
        expect(handlePrepareMatch, 'handlePrepare function not found in source').toBeTruthy();
        const handlePrepareBody = handlePrepareMatch![0];
        expect(handlePrepareBody).not.toContain('preparePhase(');
    });

    it('handlePrepare navigates with tournamentId and phaseId in the path', () => {
        const handlePrepareMatch = source.match(
            /function handlePrepare\(phaseId[^)]*\)[^{]*\{[^}]*(?:\{[^}]*\}[^}]*)*\}/
        );
        const handlePrepareBody = handlePrepareMatch![0];
        // Path must include both tournamentId and phaseId variables
        expect(handlePrepareBody).toContain('tournamentId');
        expect(handlePrepareBody).toContain('phaseId');
    });
});

// ── AC-TEST-PHASELIST-START-UNCHANGED-GREEN ────────────────────────────────────
// handleStart regression: push() must NOT be called by handleStart.
// The navigation fix must not accidentally reroute the "Phase starten" button.

describe('PhaseList.svelte — handleStart regression: push() never called (AC-TEST-PHASELIST-START-UNCHANGED-GREEN)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it('handleStart function calls startPhase() API', () => {
        const handleStartMatch = source.match(
            /function handleStart\(phaseId[^)]*\)[^{]*\{[^}]*(?:\{[^}]*\}[^}]*)*\}/
        );
        expect(handleStartMatch, 'handleStart function not found in source').toBeTruthy();
        const handleStartBody = handleStartMatch![0];
        expect(handleStartBody).toContain('startPhase(');
    });

    it('handleStart function does NOT call push()', () => {
        const handleStartMatch = source.match(
            /function handleStart\(phaseId[^)]*\)[^{]*\{[^}]*(?:\{[^}]*\}[^}]*)*\}/
        );
        const handleStartBody = handleStartMatch![0];
        // handleStart must not navigate — navigation is post-complete only
        expect(handleStartBody).not.toContain('push(');
    });
});

// ── AC-TEST-I18N-PHASES-COLUMNS-RESOLVE-RED ──────────────────────────────────
// de.json must have all 7 phases.columns keys in a single block.
// Currently FAILS because duplicate phases.columns key → JSON parser keeps only "actions".

describe('de.json — all 7 phases.columns keys present (AC-TEST-I18N-PHASES-COLUMNS-RESOLVE-RED)', () => {
    type PhasesSection = {
        columns: Record<string, string>;
    };
    const phases = (deMessages as unknown as Record<string, PhasesSection>).phases;
    const cols = phases.columns;

    it('phases.columns.sequenceNumber exists and resolves to non-empty string', () => {
        expect(cols).toHaveProperty('sequenceNumber');
        expect(cols.sequenceNumber).toBeTypeOf('string');
        expect(cols.sequenceNumber.length).toBeGreaterThan(0);
        // Must NOT be the raw i18n key (unresolved fallback pattern)
        expect(cols.sequenceNumber).not.toContain('phases.columns.');
    });

    it('phases.columns.description exists and resolves to non-empty string', () => {
        expect(cols).toHaveProperty('description');
        expect(cols.description).toBeTypeOf('string');
        expect(cols.description.length).toBeGreaterThan(0);
        expect(cols.description).not.toContain('phases.columns.');
    });

    it('phases.columns.status exists and resolves to non-empty string', () => {
        expect(cols).toHaveProperty('status');
        expect(cols.status).toBeTypeOf('string');
        expect(cols.status.length).toBeGreaterThan(0);
        expect(cols.status).not.toContain('phases.columns.');
    });

    it('phases.columns.gameMode exists and resolves to non-empty string', () => {
        expect(cols).toHaveProperty('gameMode');
        expect(cols.gameMode).toBeTypeOf('string');
        expect(cols.gameMode.length).toBeGreaterThan(0);
        expect(cols.gameMode).not.toContain('phases.columns.');
    });

    it('phases.columns.currentLap exists and resolves to non-empty string', () => {
        expect(cols).toHaveProperty('currentLap');
        expect(cols.currentLap).toBeTypeOf('string');
        expect(cols.currentLap.length).toBeGreaterThan(0);
        expect(cols.currentLap).not.toContain('phases.columns.');
    });

    it('phases.columns.matches exists and resolves to non-empty string', () => {
        expect(cols).toHaveProperty('matches');
        expect(cols.matches).toBeTypeOf('string');
        expect(cols.matches.length).toBeGreaterThan(0);
        expect(cols.matches).not.toContain('phases.columns.');
    });

    it('phases.columns.actions exists and resolves to non-empty string (AC-TEST-PHASESTORE-EXISTING-COLUMNS-ACTIONS-GREEN regression)', () => {
        expect(cols).toHaveProperty('actions');
        expect(cols.actions).toBeTypeOf('string');
        expect(cols.actions.length).toBeGreaterThan(0);
        expect(cols.actions).not.toContain('phases.columns.');
    });

    it('phases.columns contains exactly 7 keys (no extra, no missing)', () => {
        const keys = Object.keys(cols);
        expect(keys).toHaveLength(7);
        expect(keys.sort()).toEqual(
            ['actions', 'currentLap', 'description', 'gameMode', 'matches', 'sequenceNumber', 'status'].sort()
        );
    });
});

// ── AC-ERROR-HANDLING-INVALID-NAVIGATION-CONTEXT ──────────────────────────────
// handlePrepare must guard against empty/falsy tournamentId or phaseId.
// Verified via source-code structural check.

describe('PhaseList.svelte — handlePrepare fallback on invalid navigation context (AC-ERROR-HANDLING-INVALID-NAVIGATION-CONTEXT)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it('handlePrepare source contains guard for empty tournamentId or phaseId', () => {
        const handlePrepareMatch = source.match(
            /function handlePrepare\(phaseId[^)]*\)[^{]*\{[^}]*(?:\{[^}]*\}[^}]*)*\}/
        );
        expect(handlePrepareMatch, 'handlePrepare function not found in source').toBeTruthy();
        const handlePrepareBody = handlePrepareMatch![0];
        // Guard condition: !tournamentId || !phaseId (or equivalent falsy check)
        expect(handlePrepareBody).toMatch(/!tournamentId|!phaseId|falsy/);
    });

    it('handlePrepare source sets actionError when context is invalid', () => {
        const handlePrepareMatch = source.match(
            /function handlePrepare\(phaseId[^)]*\)[^{]*\{[^}]*(?:\{[^}]*\}[^}]*)*\}/
        );
        const handlePrepareBody = handlePrepareMatch![0];
        expect(handlePrepareBody).toContain('actionError');
    });
});

// ── AC-IMPL-PHASELIST-IMPORT-CLEANUP ─────────────────────────────────────────
// preparePhase import must be removed if unused after the handler refactor.
// Check the import block specifically — it may appear in comments but must not be imported.

describe('PhaseList.svelte — import cleanup (AC-IMPL-PHASELIST-IMPORT-CLEANUP)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it('preparePhase is not in the phaseStore import statement', () => {
        // Extract the import block from phaseStore
        const importMatch = source.match(/import\s*\{[^}]*\}\s*from\s*['"][^'"]*phaseStore[^'"]*['"]/);
        expect(importMatch, 'phaseStore import not found').toBeTruthy();
        const importBlock = importMatch![0];
        expect(importBlock).not.toContain('preparePhase');
    });
});
