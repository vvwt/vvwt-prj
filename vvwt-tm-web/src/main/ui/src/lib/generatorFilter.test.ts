// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import {
    filterNonLastPhaseGenerators,
    filterLastPhaseGenerators,
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
