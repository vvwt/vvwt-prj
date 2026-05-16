/**
 * Central registry module for match generator data.
 *
 * E58S05 — AC2, AC3, AC5:
 * Loads the generator list from GET /api/match-generators exactly once and caches the result.
 * Both DraftConfig.svelte and TournamentForm.svelte consume this module; no component fetches
 * the endpoint independently.
 *
 * The cache is module-level (process-lifetime in tests, page-lifetime in browser).
 * Tests must call resetGeneratorCacheForTest() between test cases to ensure isolation.
 *
 * @see MatchGeneratorInfoController (backend, de.vvwt.tm.web)
 * @see DraftConfig.svelte (consumer 1 — phase-plan page)
 * @see TournamentForm.svelte (consumer 2 — tournament create/edit page)
 */

import { apiFetch } from '../lib/api.js';

/** A registered match generator's key and capability flag. */
export interface MatchGeneratorInfo {
    keyId: string;
    isLastPhaseGenerator: boolean;
}

/** Module-level cache. `null` means not yet fetched. */
let cache: MatchGeneratorInfo[] | null = null;

/**
 * Returns all registered match generators from the registry endpoint.
 *
 * On first call, fetches GET /api/match-generators and populates the module-level cache.
 * Subsequent calls return the cached result without issuing a second fetch (AC2).
 *
 * @returns The list of registered generators, ordered as returned by the server.
 * @throws Error if the HTTP response is not OK.
 */
export async function getGeneratorList(): Promise<MatchGeneratorInfo[]> {
    if (cache !== null) {
        return cache;
    }
    const response = await apiFetch('/api/match-generators');
    if (!response.ok) {
        throw new Error(`Failed to load generator list: HTTP ${response.status}`);
    }
    cache = await response.json() as MatchGeneratorInfo[];
    return cache;
}

