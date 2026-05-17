// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import type { MatchGeneratorInfo } from '../stores/generatorStore.js';

/**
 * Returns generators that can be used for non-last phases.
 * Filters by isLastPhaseGenerator === false — NOT by key string.
 */
export function filterNonLastPhaseGenerators(
    generators: MatchGeneratorInfo[],
): MatchGeneratorInfo[] {
    return generators.filter(g => !g.isLastPhaseGenerator);
}

/**
 * Returns generators that can be used for the last phase only.
 * Filters by isLastPhaseGenerator === true — NOT by key string.
 */
export function filterLastPhaseGenerators(
    generators: MatchGeneratorInfo[],
): MatchGeneratorInfo[] {
    return generators.filter(g => g.isLastPhaseGenerator);
}

/**
 * Resolves the default non-last-phase generator key.
 *
 * E58S06 AC2: the tournament form's default match-generator is the first non-last-phase
 * generator in the registry list — NOT the first generator overall. This prevents
 * accidentally defaulting to a last-phase generator (e.g. awardCeremony).
 *
 * E58S06 AC11: if the tournament's stored generator is already a valid non-last-phase
 * generator, it is returned as-is (preserving an operator's explicit choice). If no
 * non-last-phase generator is registered, returns null — callers must degrade gracefully.
 *
 * @param generators - the full registry list (from generatorStore)
 * @param tournamentDefault - the tournament's stored matchGeneratorId (may be last-phase or blank)
 * @returns the key of the best non-last-phase generator to use, or null if none exists
 */
export function resolveNonLastDefault(
    generators: MatchGeneratorInfo[],
    tournamentDefault: string,
): string | null {
    const nonLast = filterNonLastPhaseGenerators(generators);
    if (nonLast.length === 0) {
        // AC11: graceful degradation — no non-last generator registered
        return null;
    }
    // If the tournament's chosen default is already a valid non-last generator, keep it
    const isCurrentValid = nonLast.some(g => g.keyId === tournamentDefault);
    if (isCurrentValid) {
        return tournamentDefault;
    }
    // Fall back to the first non-last-phase generator (AC2)
    return nonLast[0].keyId;
}

/**
 * Normalizes the gameMode of every non-last phase in the phase list.
 *
 * E58S06 AC3: for each phase that is NOT the last phase in the array:
 *   - If its gameMode is blank (empty string) or refers to a last-phase generator key,
 *     replace it with nonLastDefault.
 *   - If its gameMode is already a valid non-last-phase generator key, leave it unchanged
 *     (preserves operator's explicit per-phase choice).
 *   - The last phase (final element of the array) is never modified.
 *
 * E58S06 AC4: applied after addSection — the demoted phase (was last, holds last-phase key)
 * is treated like any other non-last phase and its gameMode is replaced with nonLastDefault.
 *
 * E58S06 AC11: when nonLastDefault is null (no non-last generator registered), blank
 * gameModes are left blank and no invalid key is written.
 *
 * @param phases - the mutable phase array; each element must have a gameMode: string field
 * @param generators - the full registry list (used to classify keys by flag)
 * @param nonLastDefault - the resolved non-last default key, or null for zero-generator degradation
 * @returns a new array with the same objects, with gameMode replaced where normalization applies
 */
export function normalizeNonLastPhaseGameModes<T extends { gameMode: string }>(
    phases: T[],
    generators: MatchGeneratorInfo[],
    nonLastDefault: string | null,
): T[] {
    if (phases.length === 0) return [];
    const lastIdx = phases.length - 1;
    const nonLastKeys = new Set(filterNonLastPhaseGenerators(generators).map(g => g.keyId));

    return phases.map((phase, idx) => {
        if (idx === lastIdx) {
            // Last phase — never normalize
            return phase;
        }
        const isValid = phase.gameMode !== '' && nonLastKeys.has(phase.gameMode);
        if (isValid) {
            // Valid non-last gameMode — preserve operator's choice
            return phase;
        }
        // Blank or last-phase key — normalize to non-last default
        if (nonLastDefault === null) {
            // AC11: no non-last generator registered — leave blank
            return phase.gameMode === '' ? phase : { ...phase, gameMode: '' };
        }
        return { ...phase, gameMode: nonLastDefault };
    });
}
