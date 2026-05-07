/**
 * RED-first tests for PhaseTransition.svelte (E48S08).
 *
 * Covers AC-FRONTEND-VITEST-DRAGDROP-RED and AC-FRONTEND-VITEST-COMPLEMENTARY-COVERAGE-RED.
 *
 * JSDOM DnD limitation: HTML5 DragEvent.dataTransfer is read-only / not propagated in JSDOM.
 * Strategy per AC-FRONTEND-VITEST-DRAGDROP-RED: test the pure swap-logic function directly
 * via exported `swapSlots` from `phaseTransitionUtils.ts` (Direct-Handler-Invocation).
 * NOT via dispatchEvent(DragEvent).
 *
 * Source-code structural checks follow the existing pattern from other test files
 * (e.g., TimerAudio.test.ts): fs.readFileSync + path.resolve.
 *
 * DEC-22 Iron Law: all tests written RED-first.
 */

import { describe, expect, it } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import deMessages from '../locales/de.json';

const __dirname_local = path.dirname(new URL(import.meta.url).pathname);

// ── Group 1: AC-FRONTEND-VITEST-DRAGDROP-RED ─────────────────────────────────
// Direct-Handler-Invocation of swapSlots pure function from phaseTransitionUtils

describe('swapSlots — DnD state mutation (AC-FRONTEND-VITEST-DRAGDROP-RED)', () => {
    it('swapSlots swaps teamId values while keeping groupNumber/groupPosition in place', async () => {
        const { swapSlots } = await import('./phaseTransitionUtils.js');
        const slots = [
            { teamId: 'team-A', groupNumber: 1, groupPosition: 1 },
            { teamId: 'team-B', groupNumber: 1, groupPosition: 2 },
            { teamId: 'team-C', groupNumber: 2, groupPosition: 1 },
        ];
        const result = swapSlots(slots, 0, 1);
        // After swap: slot at index 0 should have team-B at group 1, pos 1
        //             slot at index 1 should have team-A at group 1, pos 2
        expect(result[0]).toEqual({ teamId: 'team-B', groupNumber: 1, groupPosition: 1 });
        expect(result[1]).toEqual({ teamId: 'team-A', groupNumber: 1, groupPosition: 2 });
        // Unrelated slot untouched
        expect(result[2]).toEqual({ teamId: 'team-C', groupNumber: 2, groupPosition: 1 });
    });

    it('swapSlots is a pure function — does not mutate the input array', async () => {
        const { swapSlots } = await import('./phaseTransitionUtils.js');
        const slots = [
            { teamId: 'team-X', groupNumber: 1, groupPosition: 1 },
            { teamId: 'team-Y', groupNumber: 2, groupPosition: 1 },
        ];
        const original = JSON.stringify(slots);
        swapSlots(slots, 0, 1);
        expect(JSON.stringify(slots)).toBe(original);
    });

    it('swapSlots with same source and target returns unchanged slots', async () => {
        const { swapSlots } = await import('./phaseTransitionUtils.js');
        const slots = [{ teamId: 'team-A', groupNumber: 1, groupPosition: 1 }];
        const result = swapSlots(slots, 0, 0);
        expect(result[0].teamId).toBe('team-A');
    });

    it('after swapSlots, commit-payload body contains updated groupNumber/groupPosition per team', async () => {
        const { swapSlots } = await import('./phaseTransitionUtils.js');
        // Simulates the commit-button payload shape check after DnD correction
        const slots = [
            { teamId: 'team-A', groupNumber: 1, groupPosition: 1 },
            { teamId: 'team-B', groupNumber: 2, groupPosition: 1 },
        ];
        const updated = swapSlots(slots, 0, 1);
        // team-A is now at group 2 pos 1; team-B is now at group 1 pos 1
        const teamA = updated.find(s => s.teamId === 'team-A')!;
        const teamB = updated.find(s => s.teamId === 'team-B')!;
        expect(teamA.groupNumber).toBe(2);
        expect(teamA.groupPosition).toBe(1);
        expect(teamB.groupNumber).toBe(1);
        expect(teamB.groupPosition).toBe(1);

        // Payload body — each item has teamId, groupNumber, groupPosition; no phaseId
        const body = JSON.parse(JSON.stringify(updated)) as Array<{
            teamId: string;
            groupNumber: number;
            groupPosition: number;
            phaseId?: unknown;
        }>;
        body.forEach(item => {
            expect(item).toHaveProperty('teamId');
            expect(item).toHaveProperty('groupNumber');
            expect(item).toHaveProperty('groupPosition');
            expect(item).not.toHaveProperty('phaseId');
        });
    });
});

// ── Group 2: AC-FRONTEND-VITEST-COMPLEMENTARY-COVERAGE-RED ───────────────────

describe('de.json — phaseTransition i18n completeness (AC-FRONTEND-VITEST-COMPLEMENTARY-COVERAGE-RED)', () => {
    // Type cast: top-level keys we know are flat string records
    const pt = (deMessages as unknown as Record<string, Record<string, string>>).phaseTransition;

    it('phaseTransition.pageTitle key exists in de.json (route-title / E47-header)', () => {
        expect(pt).toBeDefined();
        expect(pt.pageTitle).toBeTypeOf('string');
        expect(pt.pageTitle.length).toBeGreaterThan(0);
    });

    it('phaseTransition.loading key exists in de.json (loading state)', () => {
        expect(pt.loading).toBeTypeOf('string');
        expect(pt.loading.length).toBeGreaterThan(0);
    });

    it('phaseTransition.errorBanner key exists in de.json (error state)', () => {
        expect(pt.errorBanner).toBeTypeOf('string');
        expect(pt.errorBanner.length).toBeGreaterThan(0);
    });

    it('phaseTransition.cancelButton key exists in de.json (cancel navigation)', () => {
        expect(pt.cancelButton).toBeTypeOf('string');
        expect(pt.cancelButton.length).toBeGreaterThan(0);
    });

    it('phaseTransition.commitButton key exists in de.json (commit action)', () => {
        expect(pt.commitButton).toBeTypeOf('string');
        expect(pt.commitButton.length).toBeGreaterThan(0);
    });
});

// ── Component source-code structural checks ───────────────────────────────────

describe('PhaseTransition.svelte — source structural checks (AC-FRONTEND-LOADING-AND-ERROR-STATES, AC-FRONTEND-DRAGDROP-NATIVE)', () => {
    const source = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseTransition.svelte'),
        'utf8'
    );

    it('component source references loading state (AC-FRONTEND-LOADING-AND-ERROR-STATES)', () => {
        expect(source).toContain('loading');
    });

    it('component source references error state (AC-FRONTEND-LOADING-AND-ERROR-STATES)', () => {
        expect(source).toContain('error');
    });

    it('component source renders commitButton i18n key (AC-FRONTEND-COMMIT-BUTTON)', () => {
        expect(source).toContain('phaseTransition.commitButton');
    });

    it('component source renders cancelButton i18n key (AC-FRONTEND-CANCEL-BUTTON)', () => {
        expect(source).toContain('phaseTransition.cancelButton');
    });

    it('component source registers E47 pageHeader (AC-FRONTEND-E47-HEADER)', () => {
        expect(source).toContain('pageHeader');
        expect(source).toContain('phaseTransition.pageTitle');
    });

    it('component source contains ondragstart and ondragover and ondrop (AC-FRONTEND-DRAGDROP-NATIVE)', () => {
        expect(source).toContain('ondragstart');
        expect(source).toContain('ondragover');
        expect(source).toContain('ondrop');
    });

    it('component source uses draggable attribute (AC-FRONTEND-DRAGDROP-NATIVE)', () => {
        expect(source).toContain('draggable');
    });

    it('component source calls commitTransition (AC-FRONTEND-COMMIT-BUTTON, AC-FRONTEND-COMMIT-PAYLOAD-SHAPE)', () => {
        expect(source).toContain('commitTransition');
    });
});

// ── Routing hook presence in PhaseList.svelte (AC-FRONTEND-ROUTING-HOOK) ─────

describe('PhaseList.svelte — routing hook for transition navigation (AC-FRONTEND-ROUTING-HOOK)', () => {
    const phaseListSource = fs.readFileSync(
        path.resolve(__dirname_local, './PhaseList.svelte'),
        'utf8'
    );

    it('PhaseList source navigates to transition route after completePhase success', () => {
        expect(phaseListSource).toContain('/transition');
    });

    it('PhaseList source finds next phase by sequenceNumber for routing', () => {
        expect(phaseListSource).toContain('sequenceNumber');
    });
});

// ── E48S18: Vorbereiten-Route structural checks ─────────────────────────────────

describe('App.svelte — Vorbereiten-Route registration (E48S18, AC-FRONTEND-PREPARE-ROUTE-RED)', () => {
    const appSource = fs.readFileSync(
        path.resolve(__dirname_local, '../App.svelte'),
        'utf8'
    );

    it('App.svelte route map includes /prepare route (AC-FRONTEND-PREPARE-ROUTE-RED)', () => {
        expect(appSource).toContain('/prepare');
    });

    it('App.svelte imports PhasePreparation component for prepare route (AC-IMPL-FRONTEND-PREPARE-ROUTE)', () => {
        expect(appSource).toContain('PhasePreparation');
    });
});

describe('parentRouteMap.ts — prepare route back-navigation (E48S18)', () => {
    const parentRouteMapSource = fs.readFileSync(
        path.resolve(__dirname_local, '../lib/parentRouteMap.ts'),
        'utf8'
    );

    it('parentRouteMap contains prepare route entry (back to phases)', () => {
        expect(parentRouteMapSource).toContain('/prepare');
    });
});

describe('de.json — phases.prepareTitle i18n key (E48S18)', () => {
    const pt = (deMessages as unknown as Record<string, Record<string, string>>).phases;

    it('phases.prepareTitle key exists in de.json (AC-IMPL-FRONTEND-PREPARE-ROUTE)', () => {
        expect(pt).toBeDefined();
        expect(pt).toHaveProperty('prepareTitle');
        expect(pt.prepareTitle).toBeTypeOf('string');
        expect(pt.prepareTitle.length).toBeGreaterThan(0);
    });
});
