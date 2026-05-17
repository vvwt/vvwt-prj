// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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

