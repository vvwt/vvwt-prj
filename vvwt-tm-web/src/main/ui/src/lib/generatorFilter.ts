/**
 * Generator filter utilities for capability-flag-based filtering.
 *
 * E58S05 AC4: the phase-plan dropdown filters generators by the isLastPhaseGenerator
 * capability flag — NOT by matching the generator key string. This module provides
 * pure functions to make that logic independently testable.
 *
 * @see DraftConfig.svelte (consumer)
 * @see generatorStore.ts (data source)
 * @see MatchGeneratorInfo (type)
 */

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
