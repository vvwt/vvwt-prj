/**
 * Tests for generatorStore — E58S05 AC2 + AC8 TDD RED-first.
 *
 * AC2 (testing): a central TypeScript module loads the generator list and caches it;
 * the phase-plan page and the tournament page both consume that shared module, and no
 * component fetches the endpoint independently. Verified by a test that asserts the
 * generator-list endpoint is fetched exactly once across both consumer calls (not once
 * per call).
 *
 * RED-first per DEC-22 (AC8): these tests fail before generatorStore.ts is created.
 *
 * Test isolation: vi.resetModules() between tests ensures the module-level cache is
 * reset (each dynamic import() gets a fresh module instance).
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// ─────────────────────────────────────────────────────────────────────────────
// AC2 — central module, endpoint fetched exactly once across multiple calls
// ─────────────────────────────────────────────────────────────────────────────

describe('generatorStore — AC2: single fetch, shared cache (E58S05)', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('getGeneratorList() fetches /api/match-generators exactly once even when called twice', async () => {
    // Arrange: mock global fetch to return a valid generator list
    const mockGenerators = [
      { keyId: 'roundRobin', isLastPhaseGenerator: false },
      { keyId: 'awardCeremony', isLastPhaseGenerator: true },
    ];
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => mockGenerators,
    });
    vi.stubGlobal('fetch', fetchMock);

    // Act: import the module fresh (vi.resetModules ensures no cached singleton)
    const { getGeneratorList } = await import('./generatorStore.js');

    // First call triggers the fetch
    const result1 = await getGeneratorList();
    // Second call should return cached result — no second fetch
    const result2 = await getGeneratorList();

    // Assert: fetch called exactly once
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/match-generators', expect.any(Object));

    // Both results should be the same list
    expect(result1).toEqual(mockGenerators);
    expect(result2).toEqual(mockGenerators);
  });

  it('getGeneratorList() returns a list of MatchGeneratorInfo objects with keyId and isLastPhaseGenerator', async () => {
    const mockGenerators = [
      { keyId: 'roundRobin', isLastPhaseGenerator: false },
      { keyId: 'awardCeremony', isLastPhaseGenerator: true },
    ];
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => mockGenerators,
    });
    vi.stubGlobal('fetch', fetchMock);

    const { getGeneratorList } = await import('./generatorStore.js');

    const result = await getGeneratorList();

    expect(result).toHaveLength(2);
    expect(result[0]).toMatchObject({ keyId: 'roundRobin', isLastPhaseGenerator: false });
    expect(result[1]).toMatchObject({ keyId: 'awardCeremony', isLastPhaseGenerator: true });
  });

  it('getGeneratorList() propagates fetch errors', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
    });
    vi.stubGlobal('fetch', fetchMock);

    const { getGeneratorList } = await import('./generatorStore.js');

    await expect(getGeneratorList()).rejects.toThrow();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC2 — cross-consumer: DraftConfig + TournamentForm both import from generatorStore
// Verified via source inspection: no component fetches the endpoint independently
// ─────────────────────────────────────────────────────────────────────────────

describe('generatorStore — AC2: both consumers import from generatorStore (E58S05)', () => {
  it('DraftConfig.svelte imports from generatorStore and NOT from a direct fetch', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const draftConfigSrc = path.resolve(
      __dirname,
      '../routes/DraftConfig.svelte',
    );
    const source = fs.readFileSync(draftConfigSrc, 'utf8');
    expect(source).toContain("from '../stores/generatorStore.js'");
    // Must NOT fetch the endpoint independently
    expect(source).not.toContain("'/api/match-generators'");
    expect(source).not.toContain('"/api/match-generators"');
  });

  it('TournamentForm.svelte imports from generatorStore and NOT from a direct fetch', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const tournamentFormSrc = path.resolve(
      __dirname,
      '../routes/TournamentForm.svelte',
    );
    const source = fs.readFileSync(tournamentFormSrc, 'utf8');
    expect(source).toContain("from '../stores/generatorStore.js'");
    // Must NOT fetch the endpoint independently
    expect(source).not.toContain("'/api/match-generators'");
    expect(source).not.toContain('"/api/match-generators"');
  });

  it('both consumers share the same module cache: getGeneratorList() called from two consumers fetches once', async () => {
    // Simulate DraftConfig + TournamentForm both calling getGeneratorList() on the same module instance
    const mockGenerators = [
      { keyId: 'roundRobin', isLastPhaseGenerator: false },
      { keyId: 'awardCeremony', isLastPhaseGenerator: true },
    ];
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => mockGenerators,
    });
    vi.stubGlobal('fetch', fetchMock);

    const { getGeneratorList } = await import('./generatorStore.js');

    // Consumer 1 (DraftConfig): calls getGeneratorList
    await getGeneratorList();
    // Consumer 2 (TournamentForm): calls getGeneratorList on same module instance
    await getGeneratorList();

    // Assert: fetch called exactly once (not once per consumer)
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
