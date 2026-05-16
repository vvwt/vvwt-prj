/**
 * Tests for generatorFilter — E58S05 AC4/AC8 TDD RED-first.
 * Extended by E58S06 AC1–AC5, AC11 (DEC-22 TDD RED-first).
 *
 * AC4 (E58S05): the phase-plan dropdown filters generators by the isLastPhaseGenerator capability
 * flag, NOT by matching the generator key string. These tests use a fixture that includes a
 * generator whose key is NOT 'awardCeremony' but has isLastPhaseGenerator=true, so that a
 * key-string-based filter would fail the test while a flag-based filter passes.
 *
 * AC1–AC5, AC11 (E58S06): new pure functions resolveNonLastDefault and
 * normalizeNonLastPhaseGameModes, tested at module level per AC5 constraint
 * (Svelte component mounting infeasible in Vitest+jsdom — E58S05 AC2 finding).
 *
 * RED-first per DEC-22 (AC5): new tests added BEFORE corresponding implementations.
 */

import { describe, it, expect } from 'vitest';
import {
    filterNonLastPhaseGenerators,
    filterLastPhaseGenerators,
    resolveNonLastDefault,
    normalizeNonLastPhaseGameModes,
} from './generatorFilter.js';

// AC4 fixture: keys are NOT the traditional 'roundRobin'/'awardCeremony' strings.
// A key-string-based filter (e.g., keyId === 'awardCeremony') would FAIL these tests.
const fixture = [
    { keyId: 'teamDuel', isLastPhaseGenerator: false },
    { keyId: 'groupStage', isLastPhaseGenerator: false },
    { keyId: 'trophyFinal', isLastPhaseGenerator: true },
];

describe('generatorFilter — AC4: filterLastPhaseGenerators uses flag, not key string (E58S05)', () => {
    it('returns only generators with isLastPhaseGenerator === true, regardless of key', () => {
        const result = filterLastPhaseGenerators(fixture);
        expect(result).toHaveLength(1);
        // The returned generator has isLastPhaseGenerator=true, key is NOT 'awardCeremony'
        expect(result[0].keyId).toBe('trophyFinal');
        expect(result[0].isLastPhaseGenerator).toBe(true);
    });

    it('does NOT return generators with isLastPhaseGenerator === false', () => {
        const result = filterLastPhaseGenerators(fixture);
        const falseOnes = result.filter(g => !g.isLastPhaseGenerator);
        expect(falseOnes).toHaveLength(0);
    });

    it('a key-string filter for "awardCeremony" would return empty (proving fixture independence)', () => {
        // This test documents WHY the fixture is designed as it is:
        // if the implementation used keyId === 'awardCeremony' instead of isLastPhaseGenerator,
        // filterLastPhaseGenerators would return [] instead of [trophyFinal].
        const keyStringFilter = fixture.filter(g => g.keyId === 'awardCeremony');
        expect(keyStringFilter).toHaveLength(0); // Key-string filter fails on this fixture
    });
});

describe('generatorFilter — AC4: filterNonLastPhaseGenerators uses flag, not key string (E58S05)', () => {
    it('returns only generators with isLastPhaseGenerator === false, regardless of key', () => {
        const result = filterNonLastPhaseGenerators(fixture);
        expect(result).toHaveLength(2);
        expect(result.every(g => !g.isLastPhaseGenerator)).toBe(true);
    });

    it('does NOT include the last-phase generator', () => {
        const result = filterNonLastPhaseGenerators(fixture);
        const trophyFinal = result.find(g => g.keyId === 'trophyFinal');
        expect(trophyFinal).toBeUndefined();
    });

    it('returns all non-last-phase generators by key-agnostic flag', () => {
        // teamDuel and groupStage are both isLastPhaseGenerator=false
        const result = filterNonLastPhaseGenerators(fixture);
        expect(result.map(g => g.keyId)).toContain('teamDuel');
        expect(result.map(g => g.keyId)).toContain('groupStage');
    });
});

// ── E58S06: resolveNonLastDefault — AC2, AC11 ────────────────────────────────
//
// AC2: the tournament-form's default selection is the first non-last-phase generator,
//      not the first generator overall. The fixture is designed so that the first
//      registry entry is a last-phase generator — a key-string-based or first-overall
//      pick would select the wrong generator.
// AC11: when no non-last generator exists, resolveNonLastDefault returns null (graceful
//       degradation — no crash, no invalid key).

// AC2 fixture: first entry is last-phase — a "first-overall" pick would fail
const fixtureFirstIsLast = [
    { keyId: 'awardCeremony', isLastPhaseGenerator: true },
    { keyId: 'roundRobin', isLastPhaseGenerator: false },
    { keyId: 'groupStage', isLastPhaseGenerator: false },
];

describe('generatorFilter — AC2: resolveNonLastDefault picks first non-last generator (E58S06)', () => {
    it('returns the first non-last-phase generator when tournament default is a last-phase generator', () => {
        // Tournament default is 'awardCeremony' (last-phase) — should fall back to first non-last
        const result = resolveNonLastDefault(fixtureFirstIsLast, 'awardCeremony');
        expect(result).toBe('roundRobin');
    });

    it('returns the tournament default when it is already a valid non-last generator', () => {
        // Tournament default 'groupStage' is non-last — should be preserved
        const result = resolveNonLastDefault(fixtureFirstIsLast, 'groupStage');
        expect(result).toBe('groupStage');
    });

    it('returns the first non-last generator when tournament default is empty string', () => {
        const result = resolveNonLastDefault(fixtureFirstIsLast, '');
        expect(result).toBe('roundRobin');
    });

    it('proves a first-overall pick would return the wrong generator on this fixture', () => {
        // This is a documentation test: fixtureFirstIsLast[0] is a last-phase generator.
        // A naive "first overall" implementation would return 'awardCeremony' (wrong).
        expect(fixtureFirstIsLast[0].isLastPhaseGenerator).toBe(true);
        expect(fixtureFirstIsLast[0].keyId).toBe('awardCeremony');
        // The correct result must NOT be awardCeremony
        const result = resolveNonLastDefault(fixtureFirstIsLast, 'awardCeremony');
        expect(result).not.toBe('awardCeremony');
    });
});

describe('generatorFilter — AC11: resolveNonLastDefault graceful degradation (E58S06)', () => {
    it('returns null when all generators are last-phase (no non-last generator registered)', () => {
        const allLast = [
            { keyId: 'awardCeremony', isLastPhaseGenerator: true },
            { keyId: 'celebration', isLastPhaseGenerator: true },
        ];
        const result = resolveNonLastDefault(allLast, 'awardCeremony');
        expect(result).toBeNull();
    });

    it('returns null when generator list is empty', () => {
        const result = resolveNonLastDefault([], '');
        expect(result).toBeNull();
    });
});

// ── E58S06: normalizeNonLastPhaseGameModes — AC3, AC4, AC11 ─────────────────
//
// AC3: for every non-last phase, if the gameMode is blank OR is a last-phase generator key,
//      replace it with the non-last default. A valid non-last-phase gameMode is left unchanged.
//      The last phase is excluded (identified by position: the last element of the array).
//      A single-phase plan has no non-last phase — normalization is a no-op.
// AC4: when a phase is added, the previously-last phase becomes non-last while still holding
//      the last-phase generator — normalization replaces it with the non-last default.
// AC11: when no non-last generator exists, leave game mode blank (no crash, no invalid key).

const fixtureGenerators = [
    { keyId: 'roundRobin', isLastPhaseGenerator: false },
    { keyId: 'awardCeremony', isLastPhaseGenerator: true },
];

describe('generatorFilter — AC3: normalizeNonLastPhaseGameModes (E58S06)', () => {
    it('replaces blank gameMode on a non-last phase with the non-last default', () => {
        const phases = [
            { gameMode: '' },       // non-last, blank — should be normalized
            { gameMode: 'awardCeremony' }, // last phase — untouched
        ];
        const result = normalizeNonLastPhaseGameModes(phases, fixtureGenerators, 'roundRobin');
        expect(result[0].gameMode).toBe('roundRobin');
        expect(result[1].gameMode).toBe('awardCeremony'); // last phase untouched
    });

    it('replaces a last-phase generator key on a non-last phase with the non-last default', () => {
        const phases = [
            { gameMode: 'awardCeremony' }, // non-last, has last-phase key — should be normalized
            { gameMode: 'awardCeremony' }, // last phase — untouched
        ];
        const result = normalizeNonLastPhaseGameModes(phases, fixtureGenerators, 'roundRobin');
        expect(result[0].gameMode).toBe('roundRobin'); // normalized
        expect(result[1].gameMode).toBe('awardCeremony'); // last phase untouched
    });

    it('leaves an already-valid non-last gameMode unchanged (operator choice preserved)', () => {
        const extraFixture = [
            { keyId: 'roundRobin', isLastPhaseGenerator: false },
            { keyId: 'groupStage', isLastPhaseGenerator: false },
            { keyId: 'awardCeremony', isLastPhaseGenerator: true },
        ];
        const phases = [
            { gameMode: 'groupStage' }, // non-last, already valid non-last — preserve it
            { gameMode: 'awardCeremony' }, // last phase
        ];
        const result = normalizeNonLastPhaseGameModes(phases, extraFixture, 'roundRobin');
        expect(result[0].gameMode).toBe('groupStage'); // operator choice preserved
        expect(result[1].gameMode).toBe('awardCeremony');
    });

    it('is a no-op for a single-phase plan (no non-last phase)', () => {
        const phases = [{ gameMode: '' }]; // only phase = last phase
        const result = normalizeNonLastPhaseGameModes(phases, fixtureGenerators, 'roundRobin');
        expect(result[0].gameMode).toBe(''); // single-phase: no normalization
    });

    it('is a no-op for an empty phase list', () => {
        const result = normalizeNonLastPhaseGameModes([], fixtureGenerators, 'roundRobin');
        expect(result).toHaveLength(0);
    });

    it('normalizes multiple non-last phases independently', () => {
        const phases = [
            { gameMode: '' },             // non-last, blank
            { gameMode: 'awardCeremony' }, // non-last, last-phase key
            { gameMode: 'roundRobin' },    // non-last, already valid
            { gameMode: 'awardCeremony' }, // last phase — untouched
        ];
        const result = normalizeNonLastPhaseGameModes(phases, fixtureGenerators, 'roundRobin');
        expect(result[0].gameMode).toBe('roundRobin'); // blank → default
        expect(result[1].gameMode).toBe('roundRobin'); // last-phase key → default
        expect(result[2].gameMode).toBe('roundRobin'); // valid — preserved (roundRobin IS the default)
        expect(result[3].gameMode).toBe('awardCeremony'); // last phase — untouched
    });
});

describe('generatorFilter — AC4: normalizeNonLastPhaseGameModes demoted-phase (E58S06)', () => {
    it('normalizes a demoted phase (was last, now non-last, holds last-phase generator)', () => {
        // Simulates: 1-phase plan had awardCeremony; a phase is added;
        // the old last-phase (now index 0, non-last) still holds awardCeremony.
        const phases = [
            { gameMode: 'awardCeremony' }, // was last, now non-last after addSection
            { gameMode: 'awardCeremony' }, // new last phase
        ];
        const result = normalizeNonLastPhaseGameModes(phases, fixtureGenerators, 'roundRobin');
        expect(result[0].gameMode).toBe('roundRobin'); // demoted phase normalized
        expect(result[1].gameMode).toBe('awardCeremony'); // new last phase untouched
    });
});

describe('generatorFilter — AC11: normalizeNonLastPhaseGameModes zero non-last generators (E58S06)', () => {
    it('leaves gameMode blank when no non-last generator is registered (graceful degradation)', () => {
        const allLastFixture = [
            { keyId: 'awardCeremony', isLastPhaseGenerator: true },
        ];
        const phases = [
            { gameMode: '' },
            { gameMode: 'awardCeremony' },
        ];
        // When there is no non-last default, blank stays blank (no crash, no invalid key)
        const result = normalizeNonLastPhaseGameModes(phases, allLastFixture, null);
        expect(result[0].gameMode).toBe('');
        expect(result[1].gameMode).toBe('awardCeremony');
    });
});
