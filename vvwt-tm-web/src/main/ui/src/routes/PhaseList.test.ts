/**
 * RED-first tests for PhaseList.svelte (E48S19 + E48S23).
 *
 * Covers (E48S19):
 *   - AC-TEST-PHASELIST-PREPARE-NAVIGATES-RED: handlePrepare navigates to /prepare route, NOT preparePhase API
 *   - AC-TEST-PHASELIST-START-UNCHANGED-GREEN: handleStart regression — push() never called
 *   - AC-TEST-I18N-PHASES-COLUMNS-RESOLVE-RED: de.json has all 7 phases.columns keys
 *   - AC-ERROR-HANDLING-INVALID-NAVIGATION-CONTEXT: handlePrepare sets actionError when context invalid
 *
 * Covers (E48S23 — Reset-Plan affordance on Phasen-Übersicht):
 *   - AC-TEST-RESET-PLAN-VISIBLE-WHEN-PLANNED-RED: button with draft.resetPlanButton key rendered inside PLANNED conditional
 *   - AC-TEST-RESET-PLAN-HIDDEN-WHEN-NOT-PLANNED-RED: button NOT rendered outside PLANNED conditional
 *   - AC-TEST-RESET-PLAN-CLICK-CALLS-CONFIRM-RED: handler calls window.confirm with draft.resetPlanConfirm key
 *   - AC-TEST-RESET-PLAN-CONFIRM-INVOKES-API-RED: handler calls resetPlan(tournamentId) from tournamentStore
 *   - AC-TEST-RESET-PLAN-ERROR-RENDERS-MESSAGEKEY-RED: handler extracts apiError.messageKey + renders i18n-resolved error
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

// ── E48S23: AC-TEST-RESET-PLAN-VISIBLE-WHEN-PLANNED-RED ──────────────────────
// PhaseList.svelte must render a button with i18n key draft.resetPlanButton
// inside a block conditioned on tournamentStatus === 'PLANNED'.
// RED-first: test fails before the button conditional render is added.

describe("PhaseList.svelte — Reset-Plan button visible when PLANNED (AC-TEST-RESET-PLAN-VISIBLE-WHEN-PLANNED-RED)", () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("source contains draft.resetPlanButton i18n key reference", () => {
        expect(source).toContain("draft.resetPlanButton");
    });

    it("draft.resetPlanButton reference is inside a PLANNED-only conditional block", () => {
        // The button must appear inside an {#if tournamentStatus === 'PLANNED'} block.
        // Structural check: find the PLANNED conditional, verify button key is within it.
        const plannedBlockMatch = source.match(
            /\{#if tournamentStatus\s*===\s*['"]PLANNED['"]\}[\s\S]*?\{\/if\}/
        );
        expect(plannedBlockMatch, "{#if tournamentStatus === 'PLANNED'} block not found in source").toBeTruthy();
        const plannedBlock = plannedBlockMatch![0];
        expect(plannedBlock).toContain("draft.resetPlanButton");
    });
});

// ── E48S23: AC-TEST-RESET-PLAN-HIDDEN-WHEN-NOT-PLANNED-RED ───────────────────
// Button must NOT appear outside the PLANNED conditional.
// RED-first: test fails before conditional render is added (button may be unconditional).

describe("PhaseList.svelte — Reset-Plan button hidden when not PLANNED (AC-TEST-RESET-PLAN-HIDDEN-WHEN-NOT-PLANNED-RED)", () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("draft.resetPlanButton appears only inside the PLANNED conditional, not unconditionally", () => {
        // All occurrences of draft.resetPlanButton must be inside a {#if tournamentStatus === 'PLANNED'} block.
        // Simple structural check: strip the PLANNED block and verify key is no longer present.
        const withoutPlannedBlock = source.replace(
            /\{#if tournamentStatus\s*===\s*['"]PLANNED['"]\}[\s\S]*?\{\/if\}/,
            '<<PLANNED_BLOCK_REMOVED>>'
        );
        expect(withoutPlannedBlock).not.toContain("draft.resetPlanButton");
    });
});

// ── E48S23: AC-TEST-RESET-PLAN-CLICK-CALLS-CONFIRM-RED ───────────────────────
// The handleResetPlan handler must call window.confirm (or bare confirm()) with
// the i18n key draft.resetPlanConfirm.
// RED-first: test fails before handler is added.

describe("PhaseList.svelte — Reset-Plan handler calls confirm with draft.resetPlanConfirm (AC-TEST-RESET-PLAN-CLICK-CALLS-CONFIRM-RED)", () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("source contains a handleResetPlan function", () => {
        expect(source).toMatch(/function handleResetPlan/);
    });

    it("handleResetPlan calls confirm() with draft.resetPlanConfirm i18n key", () => {
        // Match the handleResetPlan async function body
        const handlerMatch = source.match(
            /async function handleResetPlan\(\)[^{]*\{[\s\S]*?\n  \}/
        );
        expect(handlerMatch, "handleResetPlan function not found in source").toBeTruthy();
        const handlerBody = handlerMatch![0];
        expect(handlerBody).toMatch(/confirm\s*\(/);
        expect(handlerBody).toContain("draft.resetPlanConfirm");
    });
});

// ── E48S23: AC-TEST-RESET-PLAN-CONFIRM-INVOKES-API-RED ───────────────────────
// When confirm returns true, handleResetPlan must call resetPlan(tournamentId)
// from tournamentStore.
// RED-first: test fails before handler is added.

describe("PhaseList.svelte — Reset-Plan handler calls resetPlan(tournamentId) (AC-TEST-RESET-PLAN-CONFIRM-INVOKES-API-RED)", () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("source imports resetPlan from tournamentStore", () => {
        const tournamentStoreImportMatch = source.match(
            /import\s*\{[^}]*\}\s*from\s*['"][^'"]*tournamentStore[^'"]*['"]/
        );
        expect(tournamentStoreImportMatch, "tournamentStore import not found").toBeTruthy();
        const importBlock = tournamentStoreImportMatch![0];
        expect(importBlock).toContain("resetPlan");
    });

    it("handleResetPlan calls resetPlan(tournamentId) — invokes API with the tournament id", () => {
        const handlerMatch = source.match(
            /async function handleResetPlan\(\)[^{]*\{[\s\S]*?\n  \}/
        );
        expect(handlerMatch, "handleResetPlan function not found in source").toBeTruthy();
        const handlerBody = handlerMatch![0];
        expect(handlerBody).toContain("resetPlan(tournamentId)");
    });
});

// ── E51S07: AC-TEST-PHASELIST-JOB-STATUS-ICONS-RED ───────────────────────────
// PhaseList.svelte must render job-status icons per phase.jobStatus value (DEC-55 D-8):
//   - spinner (⏳) for match_gen_running / slot_opt_running
//   - check-mark (✅) for jobStatus != null && optimized === true (done)
//   - warning (⚠️) for cancelled / failed states
//   - no icon (—) when jobStatus is null
// Verified via source-code structural check.

describe('PhaseList.svelte — E51S07: job-status icon helper function (DEC-55 D-8)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it('source contains a jobStatusIcon function', () => {
        expect(source).toMatch(/function jobStatusIcon/);
    });

    it('jobStatusIcon returns spinner emoji for match_gen_running', () => {
        const fnMatch = source.match(/function jobStatusIcon[\s\S]*?\n  \}/);
        expect(fnMatch, 'jobStatusIcon function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        expect(fnBody).toContain('match_gen_running');
        expect(fnBody).toContain('⏳');
    });

    it('jobStatusIcon returns spinner emoji for slot_opt_running', () => {
        const fnMatch = source.match(/function jobStatusIcon[\s\S]*?\n  \}/);
        const fnBody = fnMatch![0];
        expect(fnBody).toContain('slot_opt_running');
        expect(fnBody).toContain('⏳');
    });

    it('jobStatusIcon returns check-mark for optimized=true phase', () => {
        const fnMatch = source.match(/function jobStatusIcon[\s\S]*?\n  \}/);
        const fnBody = fnMatch![0];
        expect(fnBody).toContain('✅');
    });

    it('jobStatusIcon returns warning emoji for cancelled or failed state', () => {
        const fnMatch = source.match(/function jobStatusIcon[\s\S]*?\n  \}/);
        const fnBody = fnMatch![0];
        expect(fnBody).toContain('⚠');
    });

    it('jobStatusIcon returns null or "—" when jobStatus is null', () => {
        const fnMatch = source.match(/function jobStatusIcon[\s\S]*?\n  \}/);
        const fnBody = fnMatch![0];
        // The null case must be handled (returns null so template shows "—")
        expect(fnBody).toMatch(/null/);
    });

    // E55S08 / DEC-66 D-2: AC-TEST-FE-JOBSTATUSICON-CLOCK-FOR-QUEUED
    it('jobStatusIcon returns clock emoji 🕐 for slot_opt_queued (DEC-66 D-2)', () => {
        const fnMatch = source.match(/function jobStatusIcon[\s\S]*?\n  \}/);
        expect(fnMatch, 'jobStatusIcon function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        expect(fnBody).toContain('slot_opt_queued');
        expect(fnBody).toContain('🕐');
    });
});

// ── E51S07: AC-TEST-PHASELIST-ACTIVATE-GUARD-RED ─────────────────────────────
// When tournament.optimize=true and phase.optimized=false, the "Phase starten"
// button must be disabled with a tooltip (DEC-55 D-5 activate guard).

describe('PhaseList.svelte — E51S07: activate guard on start button (DEC-55 D-5)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it('source contains an activateGuardFails function or equivalent guard expression', () => {
        expect(source).toMatch(/activateGuardFails|activateGuard/);
    });

    it('activateGuardFails checks tournamentOptimize and phase.optimized', () => {
        const fnMatch = source.match(/function activateGuardFails[\s\S]*?\n  \}/);
        expect(fnMatch, 'activateGuardFails function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        expect(fnBody).toContain('tournamentOptimize');
        expect(fnBody).toContain('optimized');
    });

    it('source uses phases.activateGuardTooltip i18n key for the guard tooltip', () => {
        expect(source).toContain('phases.activateGuardTooltip');
    });

    it('de.json phases.activateGuardTooltip is defined and non-empty', () => {
        type PhasesSection = { activateGuardTooltip: string };
        const phases = (deMessages as unknown as Record<string, PhasesSection>).phases;
        expect(phases).toHaveProperty('activateGuardTooltip');
        expect(phases.activateGuardTooltip.length).toBeGreaterThan(0);
    });
});

// ── E51S07: AC-TEST-PHASELIST-JOB-COLUMN-RED ─────────────────────────────────
// PhaseList.svelte must render a "Job" column header and per-row job-status cell.

describe('PhaseList.svelte — E51S07: Job column in phase table (DEC-55 D-8)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it('source renders a job-status cell per phase row using jobStatusIcon', () => {
        // The template must call jobStatusIcon(phase) to render the icon
        expect(source).toContain('jobStatusIcon(phase)');
    });

    it('source contains isJobRunning function for spinner-click navigation', () => {
        expect(source).toMatch(/function isJobRunning/);
    });

    it('isJobRunning navigates to slot-optimization route when clicked', () => {
        // The template must wire a clickable element (for running state) to navigate to slot-optimization
        expect(source).toContain('slot-optimization');
    });
});

// ── E51S07: AC-TEST-PHASELIST-ASSIGNED-STATUS-RED ────────────────────────────
// PhaseList.svelte must handle ASSIGNED phase status (DEC-55 D-3):
//   - statusBadgeClass maps ASSIGNED to a CSS class
//   - the ASSIGNED start button invokes the same handleStart path as PREPARED

describe('PhaseList.svelte — E51S07: ASSIGNED status handling (DEC-55 D-3)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it('statusBadgeClass function or expression handles ASSIGNED status', () => {
        expect(source).toContain('ASSIGNED');
    });

    it('de.json phases.status.ASSIGNED is defined and non-empty', () => {
        type PhasesSection = { status: Record<string, string> };
        const phases = (deMessages as unknown as Record<string, PhasesSection>).phases;
        expect(phases.status).toHaveProperty('ASSIGNED');
        expect(phases.status.ASSIGNED.length).toBeGreaterThan(0);
    });
});

// ── E51S07: AC-TEST-PHASELIST-TOURNAMENT-OPTIMIZE-STATE-RED ──────────────────
// PhaseList.svelte must maintain a tournamentOptimize state variable that is set
// from the tournament.optimize field fetched from the API (DEC-55 D-5).

describe('PhaseList.svelte — E51S07: tournamentOptimize state loaded from tournament (DEC-55 D-5)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it('source declares a tournamentOptimize state variable', () => {
        expect(source).toMatch(/tournamentOptimize/);
    });

    it('PhaseOverview interface in phaseStore.ts includes jobStatus field', async () => {
        const fs2 = await import('fs');
        const path2 = await import('path');
        const storeSrc = path2.resolve(__dirname_local, '../stores/phaseStore.ts');
        const storeSource = fs2.readFileSync(storeSrc, 'utf8');
        const ifaceMatch = storeSource.match(/export interface PhaseOverview \{([\s\S]*?)\}/);
        expect(ifaceMatch).not.toBeNull();
        const ifaceBlock = ifaceMatch![1];
        expect(ifaceBlock).toContain('jobStatus');
    });

    it('PhaseOverview interface in phaseStore.ts includes optimized field', async () => {
        const fs2 = await import('fs');
        const path2 = await import('path');
        const storeSrc = path2.resolve(__dirname_local, '../stores/phaseStore.ts');
        const storeSource = fs2.readFileSync(storeSrc, 'utf8');
        const ifaceMatch = storeSource.match(/export interface PhaseOverview \{([\s\S]*?)\}/);
        expect(ifaceMatch).not.toBeNull();
        const ifaceBlock = ifaceMatch![1];
        expect(ifaceBlock).toContain('optimized');
    });
});

// ── E51S14: AC-TEST-PHASELIST-BUTTON-AFTER-FLIP-RED ──────────────────────────
// PhaseList.svelte must render "Phase starten" (startButton) — NOT "Vorbereiten"
// (prepareButton) — when phase.status === 'PREPARED'.
// Structural regression guard (DEC-41 spec-anchored): verifies existing correct
// frontend behaviour is preserved. The bug is backend-only (phase stays PENDING);
// the frontend conditional is already correct and must not regress.

describe("PhaseList.svelte — PREPARED status shows startButton, not prepareButton (AC-TEST-PHASELIST-BUTTON-AFTER-FLIP-RED)", () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("source contains {:else if phase.status === 'PREPARED'} conditional block", () => {
        expect(source).toContain("phase.status === 'PREPARED'");
    });

    it("startButton i18n key is rendered inside the PREPARED conditional block", () => {
        const preparedBlockMatch = source.match(
            /\{:else if phase\.status === 'PREPARED'\}[\s\S]*?(?=\{:else if|$)/
        );
        expect(preparedBlockMatch, "{:else if phase.status === 'PREPARED'} block not found in source").toBeTruthy();
        const preparedBlock = preparedBlockMatch![0];
        expect(preparedBlock).toContain("phases.startButton");
    });

    it("prepareButton i18n key is NOT rendered inside the PREPARED conditional block", () => {
        const preparedBlockMatch = source.match(
            /\{:else if phase\.status === 'PREPARED'\}[\s\S]*?(?=\{:else if|$)/
        );
        expect(preparedBlockMatch, "{:else if phase.status === 'PREPARED'} block not found in source").toBeTruthy();
        const preparedBlock = preparedBlockMatch![0];
        expect(preparedBlock).not.toContain("phases.prepareButton");
    });
});

// ── E48S23: AC-TEST-RESET-PLAN-ERROR-RENDERS-MESSAGEKEY-RED ──────────────────
// handleResetPlan must extract apiError.messageKey and render the i18n-resolved
// text — mirroring DraftConfig.svelte:366-372 typed-error pattern.
// RED-first: test fails before typed-error handling is added.

describe("PhaseList.svelte — Reset-Plan error renders typed messageKey (AC-TEST-RESET-PLAN-ERROR-RENDERS-MESSAGEKEY-RED)", () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("handleResetPlan catch block extracts apiError.messageKey", () => {
        const handlerMatch = source.match(
            /async function handleResetPlan\(\)[^{]*\{[\s\S]*?\n  \}/
        );
        expect(handlerMatch, "handleResetPlan function not found in source").toBeTruthy();
        const handlerBody = handlerMatch![0];
        // Must extract apiError field (mirrors DraftConfig.svelte:366 pattern)
        expect(handlerBody).toContain("apiError");
        expect(handlerBody).toContain("messageKey");
    });

    it("handleResetPlan falls back to draft.error.resetPlanFailed when no messageKey", () => {
        const handlerMatch = source.match(
            /async function handleResetPlan\(\)[^{]*\{[\s\S]*?\n  \}/
        );
        const handlerBody = handlerMatch![0];
        // Fallback key per E48S13 family contract
        expect(handlerBody).toContain("draft.error.resetPlanFailed");
    });

    it("handleResetPlan sets a resetPlanError state variable on error", () => {
        const handlerMatch = source.match(
            /async function handleResetPlan\(\)[^{]*\{[\s\S]*?\n  \}/
        );
        const handlerBody = handlerMatch![0];
        expect(handlerBody).toContain("resetPlanError");
    });
});

// ═══════════════════════════════════════════════════════════════════════════
// E51S21 — Befund 1: PREPARED phase "Mannschaften zuordnen" action button
// ═══════════════════════════════════════════════════════════════════════════

// ── AC-TEST-PHASELIST-PREPARED-PHASE-1-RENDERS-ASSIGN-ACTION-RED ─────────
// PREPARED block must render phases.assignButton (not phases.startButton)
// for Phase 1 or Phase N where predecessor is COMPLETED.
// RED-first: FAILS on staging HEAD (PREPARED block only shows phases.startButton).

describe('PhaseList.svelte — PREPARED phase renders assignButton (AC-TEST-PHASELIST-PREPARED-PHASE-1-RENDERS-ASSIGN-ACTION-RED)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("source contains phases.assignButton i18n key reference", () => {
        // RED: FAILS on baseline — only phases.startButton in PREPARED block
        expect(source).toContain('phases.assignButton');
    });

    it("phases.assignButton is inside the PREPARED conditional block", () => {
        const preparedBlockMatch = source.match(
            /\{:else if phase\.status === 'PREPARED'\}[\s\S]*?(?=\{:else if phase\.status|$)/
        );
        expect(preparedBlockMatch, "{:else if phase.status === 'PREPARED'} block not found").toBeTruthy();
        const preparedBlock = preparedBlockMatch![0];
        // RED: FAILS on baseline — no assignButton in PREPARED block
        expect(preparedBlock).toContain('phases.assignButton');
    });

    it("source contains a handleAssign function for team assignment navigation", () => {
        // RED: FAILS on baseline — no handleAssign function exists
        expect(source).toMatch(/function handleAssign/);
    });

    it("handleAssign navigates to /prepare for Phase 1", () => {
        const fnMatch = source.match(/function handleAssign[\s\S]*?\n  \}/);
        expect(fnMatch, 'handleAssign function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        expect(fnBody).toContain('/prepare');
    });

    it("handleAssign navigates to /transition for Phase N+1", () => {
        const fnMatch = source.match(/function handleAssign[\s\S]*?\n  \}/);
        expect(fnMatch, 'handleAssign function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        expect(fnBody).toContain('/transition');
    });
});

// ── AC-TEST-PHASELIST-PREPARED-PHASE-N-PREDECESSOR-NOT-COMPLETED-NO-ACTION
// When predecessor is not COMPLETED, no actionable button (or disabled with tooltip).
// Structural check: assignWaitingPredecessor key used.

describe('PhaseList.svelte — PREPARED predecessor-not-COMPLETED shows disabled/no-action (AC-TEST-PHASELIST-PREPARED-PHASE-N-PREDECESSOR-NOT-COMPLETED-NO-ACTION)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("source references phases.assignWaitingPredecessor i18n key", () => {
        // Used as disabled tooltip when predecessor not COMPLETED
        expect(source).toContain('phases.assignWaitingPredecessor');
    });

    it("de.json phases.assignWaitingPredecessor is defined and non-empty", () => {
        type PhasesSection = { assignWaitingPredecessor: string };
        const phases = (deMessages as unknown as Record<string, PhasesSection>).phases;
        expect(phases).toHaveProperty('assignWaitingPredecessor');
        expect(phases.assignWaitingPredecessor.length).toBeGreaterThan(0);
    });

    it("de.json phases.assignButton is defined and non-empty", () => {
        type PhasesSection = { assignButton: string };
        const phases = (deMessages as unknown as Record<string, PhasesSection>).phases;
        expect(phases).toHaveProperty('assignButton');
        expect(phases.assignButton.length).toBeGreaterThan(0);
    });
});

// ── AC-TEST-PHASELIST-ASSIGNED-RENDERS-START-ACTION (regression guard)
// ASSIGNED block must still contain phases.startButton.

describe('PhaseList.svelte — ASSIGNED status still shows startButton regression guard (AC-TEST-PHASELIST-ASSIGNED-RENDERS-START-ACTION)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("ASSIGNED conditional block still contains phases.startButton", () => {
        const assignedBlockMatch = source.match(
            /\{:else if phase\.status === 'ASSIGNED'\}[\s\S]*?(?=\{:else if phase\.status|$)/
        );
        expect(assignedBlockMatch, "{:else if phase.status === 'ASSIGNED'} block not found").toBeTruthy();
        const assignedBlock = assignedBlockMatch![0];
        expect(assignedBlock).toContain('phases.startButton');
    });
});

// ═══════════════════════════════════════════════════════════════════════════
// E51S21 — Befund 2: activateGuardFails siegerehrung carve-out (DEC-59 Clause F)
// ═══════════════════════════════════════════════════════════════════════════

// ── AC-TEST-PHASELIST-SIEGEREHRUNG-START-ENABLED-WITH-OPTIMIZE-TRUE-RED ──
// activateGuardFails must NOT return true for siegerehrung phases.
// RED-first: FAILS on staging HEAD — no siegerehrung carve-out in the function.

describe('PhaseList.svelte — activateGuardFails siegerehrung carve-out RED (AC-TEST-PHASELIST-SIEGEREHRUNG-START-ENABLED-WITH-OPTIMIZE-TRUE-RED)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("activateGuardFails function contains siegerehrung carve-out (DEC-59 Clause F)", () => {
        const fnMatch = source.match(/function activateGuardFails[\s\S]*?\n  \}/);
        expect(fnMatch, 'activateGuardFails function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        // RED: FAILS on baseline — no gameMode or siegerehrung check
        expect(fnBody).toMatch(/siegerehrung|gameMode/);
    });

    it("activateGuardFails uses !== true for optimized (defensive null handling)", () => {
        const fnMatch = source.match(/function activateGuardFails[\s\S]*?\n  \}/);
        expect(fnMatch, 'activateGuardFails function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        // RED: FAILS on baseline — uses === false not !== true
        expect(fnBody).toContain('!== true');
    });
});

// ── AC-TEST-PHASELIST-NON-SIEGEREHRUNG-START-DISABLED-WITH-OPTIMIZE-TRUE-OPTIMIZED-FALSE
// Regression guard: roundRobin phase with optimized=false still gets disabled.

describe('PhaseList.svelte — activateGuardFails still blocks non-siegerehrung unoptimized (regression guard)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("activateGuardFails still checks tournamentOptimize and optimized", () => {
        const fnMatch = source.match(/function activateGuardFails[\s\S]*?\n  \}/);
        expect(fnMatch, 'activateGuardFails function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        expect(fnBody).toContain('tournamentOptimize');
        expect(fnBody).toContain('optimized');
    });
});

// ═══════════════════════════════════════════════════════════════════════════
// E51S21 — Befund 3: JOB column rendering (once optimized field arrives from backend)
// ═══════════════════════════════════════════════════════════════════════════

// ── AC-TEST-PHASELIST-JOB-COLUMN-OPTIMIZED-COMPLETED-RENDERS-CHECK ────────
// jobStatusIcon with jobStatus='idle' and phase.optimized=true should return '✅'.
// Source check: existing function body already has idle+phase.optimized branch.

describe('PhaseList.svelte — jobStatusIcon returns ✅ for idle+optimized=true (AC-TEST-PHASELIST-JOB-COLUMN-OPTIMIZED-COMPLETED-RENDERS-CHECK)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("jobStatusIcon function has idle + phase.optimized === true branch returning ✅", () => {
        const fnMatch = source.match(/function jobStatusIcon[\s\S]*?\n  \}/);
        expect(fnMatch, 'jobStatusIcon function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        expect(fnBody).toContain('idle');
        expect(fnBody).toContain('phase.optimized');
        expect(fnBody).toContain('✅');
    });
});

// ── AC-TEST-PHASELIST-JOB-COLUMN-NOT-OPTIMIZED-RENDERS-DASH ──────────────
// jobStatusIcon returns null (renders "—") for idle+optimized=false.

describe('PhaseList.svelte — jobStatusIcon returns null for idle+optimized=false (AC-TEST-PHASELIST-JOB-COLUMN-NOT-OPTIMIZED-RENDERS-DASH)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("jobStatusIcon function returns null for null/undefined jobStatus (shows —)", () => {
        const fnMatch = source.match(/function jobStatusIcon[\s\S]*?\n  \}/);
        expect(fnMatch, 'jobStatusIcon function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        // The null-return path is the "—" path — must be present
        expect(fnBody).toContain('null');
    });
});

// ── AC-ERROR-OPTIMIZED-FIELD-NULL-IS-FALSE-DEFENSIVE ─────────────────────
// If backend returns optimized=null, frontend treats it as false.
// activateGuardFails uses !== true (null !== true === true → guard fires for null → button disabled).
// But story says "no ✅ rendered, guard does NOT pass on phase.optimized term alone" for null.
// Actual: with !== true, null IS treated as unoptimized (guard fires) — correct.

describe('PhaseList.svelte — optimized=null treated as false defensive (AC-ERROR-OPTIMIZED-FIELD-NULL-IS-FALSE-DEFENSIVE)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it("activateGuardFails uses !== true so null is treated as unoptimized", () => {
        const fnMatch = source.match(/function activateGuardFails[\s\S]*?\n  \}/);
        expect(fnMatch, 'activateGuardFails function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        // !== true: null !== true === true → guard fires → button disabled → correct
        expect(fnBody).toContain('!== true');
    });

    it("jobStatusIcon does NOT return ✅ without phase.optimized being truthy", () => {
        const fnMatch = source.match(/function jobStatusIcon[\s\S]*?\n  \}/);
        expect(fnMatch, 'jobStatusIcon function not found').toBeTruthy();
        const fnBody = fnMatch![0];
        // The ✅ branch requires phase.optimized (truthy) — null is falsy so ✅ never shown for null
        // Structural check: optimized is referenced in the condition alongside 'idle'
        expect(fnBody).toMatch(/idle.*optimized|optimized.*idle/s);
    });
});
