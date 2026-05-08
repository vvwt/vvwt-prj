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

// E48S20 widened slot fixture helper (all 7 fields required by TeamAvatarSlot interface)
function makeSlot(overrides: {
    teamId: string;
    teamNumber?: number;
    teamDescription?: string;
    groupNumber: number;
    groupPosition: number;
    sourceGroupNumber?: number | null;
    sourceGroupPosition?: number | null;
}) {
    return {
        teamId: overrides.teamId,
        teamNumber: overrides.teamNumber ?? 0,
        teamDescription: overrides.teamDescription ?? 'Team',
        groupNumber: overrides.groupNumber,
        groupPosition: overrides.groupPosition,
        sourceGroupNumber: overrides.sourceGroupNumber ?? null,
        sourceGroupPosition: overrides.sourceGroupPosition ?? null,
    };
}

describe('swapSlots — DnD state mutation (AC-FRONTEND-VITEST-DRAGDROP-RED)', () => {
    it('swapSlots swaps teamId values while keeping groupNumber/groupPosition in place', async () => {
        const { swapSlots } = await import('./phaseTransitionUtils.js');
        const slots = [
            makeSlot({ teamId: 'team-A', teamNumber: 1, teamDescription: 'TSV A', groupNumber: 1, groupPosition: 1, sourceGroupNumber: 1, sourceGroupPosition: 1 }),
            makeSlot({ teamId: 'team-B', teamNumber: 2, teamDescription: 'TSV B', groupNumber: 1, groupPosition: 2, sourceGroupNumber: 1, sourceGroupPosition: 2 }),
            makeSlot({ teamId: 'team-C', teamNumber: 3, teamDescription: 'TSV C', groupNumber: 2, groupPosition: 1, sourceGroupNumber: 2, sourceGroupPosition: 1 }),
        ];
        const result = swapSlots(slots, 0, 1);
        // After swap: slot at index 0 has team-B's data at group 1, pos 1
        //             slot at index 1 has team-A's data at group 1, pos 2
        expect(result[0].teamId).toBe('team-B');
        expect(result[0].teamDescription).toBe('TSV B');
        expect(result[0].groupNumber).toBe(1);
        expect(result[0].groupPosition).toBe(1);
        expect(result[1].teamId).toBe('team-A');
        expect(result[1].teamDescription).toBe('TSV A');
        expect(result[1].groupNumber).toBe(1);
        expect(result[1].groupPosition).toBe(2);
        // Unrelated slot untouched
        expect(result[2].teamId).toBe('team-C');
        expect(result[2].groupNumber).toBe(2);
        expect(result[2].groupPosition).toBe(1);
    });

    it('swapSlots is a pure function — does not mutate the input array', async () => {
        const { swapSlots } = await import('./phaseTransitionUtils.js');
        const slots = [
            makeSlot({ teamId: 'team-X', groupNumber: 1, groupPosition: 1 }),
            makeSlot({ teamId: 'team-Y', groupNumber: 2, groupPosition: 1 }),
        ];
        const original = JSON.stringify(slots);
        swapSlots(slots, 0, 1);
        expect(JSON.stringify(slots)).toBe(original);
    });

    it('swapSlots with same source and target returns unchanged slots', async () => {
        const { swapSlots } = await import('./phaseTransitionUtils.js');
        const slots = [makeSlot({ teamId: 'team-A', groupNumber: 1, groupPosition: 1 })];
        const result = swapSlots(slots, 0, 0);
        expect(result[0].teamId).toBe('team-A');
    });

    it('after swapSlots, commit-payload body contains updated groupNumber/groupPosition per team (AC-TEST-FRONTEND-DRAGDROP-SWAP-REGRESSION-GREEN)', async () => {
        const { swapSlots } = await import('./phaseTransitionUtils.js');
        // Simulates the commit-button payload shape check after DnD correction
        const slots = [
            makeSlot({ teamId: 'team-A', teamNumber: 1, teamDescription: 'TSV A', groupNumber: 1, groupPosition: 1 }),
            makeSlot({ teamId: 'team-B', teamNumber: 2, teamDescription: 'TSV B', groupNumber: 2, groupPosition: 1 }),
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

// ── E48S20: New RED-first tests ───────────────────────────────────────────────

// Source for structural checks
const svelteSource = fs.readFileSync(
    path.resolve(__dirname_local, './PhaseTransition.svelte'),
    'utf8'
);
const storeSource = fs.readFileSync(
    path.resolve(__dirname_local, '../stores/phaseTransitionStore.ts'),
    'utf8'
);

/**
 * AC-TEST-FRONTEND-NO-UUID-IN-DOM-RED (E48S20):
 * PhaseTransition.svelte must NOT render teamId in DOM.
 * DEC-9: UUIDs must not surface in organizer-facing UI.
 */
describe('PhaseTransition.svelte — DEC-9 UUID-not-in-DOM (AC-TEST-FRONTEND-NO-UUID-IN-DOM-RED)', () => {
    it('component source does NOT render slot.teamId in template', () => {
        // The old violation was: <span class="phase-transition__team-id">{slot.teamId}</span>
        // After E48S20, teamId must NOT appear in any Svelte template expression
        // We check: no {slot.teamId} or {slot?.teamId} in template binding
        expect(svelteSource).not.toContain('{slot.teamId}');
        expect(svelteSource).not.toContain('{slot?.teamId}');
    });

    it('component source renders slot.teamDescription (organizer-facing label)', () => {
        expect(svelteSource).toContain('slot.teamDescription');
    });
});

/**
 * AC-TEST-FRONTEND-SOURCE-PANE-RENDERED-RED (E48S20):
 * PhaseTransition.svelte must render a source pane with sourcePaneHeading i18n key.
 */
describe('PhaseTransition.svelte — source pane rendered (AC-TEST-FRONTEND-SOURCE-PANE-RENDERED-RED)', () => {
    it('component source references sourcePaneHeading i18n key', () => {
        expect(svelteSource).toContain('phaseTransition.sourcePaneHeading');
    });

    it('component source references targetPaneHeading i18n key', () => {
        expect(svelteSource).toContain('phaseTransition.targetPaneHeading');
    });

    it('component source references sourceLabelPhase1 i18n key', () => {
        expect(svelteSource).toContain('phaseTransition.sourceLabelPhase1');
    });

    it('component source references sourceLabelPhase2plus i18n key', () => {
        expect(svelteSource).toContain('phaseTransition.sourceLabelPhase2plus');
    });
});

/**
 * AC-TEST-FRONTEND-ALL-TARGET-SLOTS-VISIBLE-RED (E48S20):
 * PhaseTransition.svelte must render all (group, position) target cells, including empty ones.
 */
describe('PhaseTransition.svelte — all target slots visible with empty indicator (AC-TEST-FRONTEND-ALL-TARGET-SLOTS-VISIBLE-RED)', () => {
    it('component source references targetLabelEmpty i18n key', () => {
        expect(svelteSource).toContain('phaseTransition.targetLabelEmpty');
    });

    it('de.json targetLabelEmpty key exists and is non-empty', () => {
        const pt = (deMessages as unknown as Record<string, Record<string, string>>).phaseTransition;
        expect(pt).toHaveProperty('targetLabelEmpty');
        expect(pt.targetLabelEmpty).toBeTypeOf('string');
        expect(pt.targetLabelEmpty.length).toBeGreaterThan(0);
    });
});

/**
 * AC-TEST-FRONTEND-SOURCE-PANE-NOT-DROP-TARGET-RED (E48S20):
 * Source pane rows must NOT be drag sources (no ondragstart on source-pane rows).
 */
describe('PhaseTransition.svelte — source pane is not a drag source (AC-TEST-FRONTEND-SOURCE-PANE-NOT-DROP-TARGET-RED)', () => {
    it('source-pane rows do not have ondragstart attribute', () => {
        // The source pane is defined in the source-pane section.
        // Check that the source-table / source-row does not have ondragstart.
        // We verify by checking that ondragstart is NOT within the source-pane section marker.
        // Structural check: source-pane section contains teamDescription but no ondragstart binding
        const sourcePaneBlock = svelteSource.slice(
            svelteSource.indexOf('phase-transition__source-pane'),
            svelteSource.indexOf('phase-transition__target-pane')
        );
        expect(sourcePaneBlock).toContain('teamDescription');
        expect(sourcePaneBlock).not.toContain('ondragstart');
    });
});

/**
 * AC-TEST-FRONTEND-DRAGDROP-SWAP-REGRESSION-GREEN (E48S20):
 * swapSlots regression: after E48S20 widening, swapSlots must still correctly swap
 * team-bound fields (including the new display fields).
 */
describe('swapSlots — E48S20 regression: display fields swapped with teamId (AC-TEST-FRONTEND-DRAGDROP-SWAP-REGRESSION-GREEN)', () => {
    it('swapSlots swaps teamDescription along with teamId (E48S20 regression)', async () => {
        const { swapSlots } = await import('./phaseTransitionUtils.js');
        const slots = [
            makeSlot({ teamId: 'uuid-1', teamNumber: 3, teamDescription: 'TSV Erbach', groupNumber: 1, groupPosition: 1, sourceGroupNumber: 1, sourceGroupPosition: 1 }),
            makeSlot({ teamId: 'uuid-2', teamNumber: 7, teamDescription: 'SV Blau-Weiß', groupNumber: 1, groupPosition: 2, sourceGroupNumber: 2, sourceGroupPosition: 1 }),
        ];
        const result = swapSlots(slots, 0, 1);
        // After swap: index 0 (group=1, pos=1) has uuid-2's team data
        expect(result[0].teamId).toBe('uuid-2');
        expect(result[0].teamDescription).toBe('SV Blau-Weiß');
        expect(result[0].teamNumber).toBe(7);
        expect(result[0].groupNumber).toBe(1); // structural identity unchanged
        expect(result[0].groupPosition).toBe(1);
        // index 1 (group=1, pos=2) has uuid-1's team data
        expect(result[1].teamId).toBe('uuid-1');
        expect(result[1].teamDescription).toBe('TSV Erbach');
        expect(result[1].teamNumber).toBe(3);
        expect(result[1].groupNumber).toBe(1);
        expect(result[1].groupPosition).toBe(2);
    });

    it('phaseTransitionStore exports hasSourceSlot type guard', () => {
        expect(storeSource).toContain('hasSourceSlot');
        expect(storeSource).toContain('sourceGroupNumber');
        expect(storeSource).toContain('sourceGroupPosition');
    });
});
