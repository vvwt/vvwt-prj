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
