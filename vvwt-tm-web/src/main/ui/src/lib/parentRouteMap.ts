/**
 * Declarative parent-route map for the back-arrow navigation mechanism.
 * Story E47S01 — AC5, Brief D-12.
 *
 * Maps route pattern → parent path template. The map is a const — no
 * regex-derivation, no path-segment-trimming heuristic (per Brief D-12
 * rationale: heuristics break on edge cases like /tournaments/new vs /tournaments).
 *
 * resolveParent() substitutes :tournamentId with the concrete UUID from the
 * route params, returning the fully-resolved parent path. Returns null for
 * top-level routes (no back-arrow).
 *
 * S01 sub-routes (6): all map to /tournaments/:tournamentId/edit.
 * S02 adds TournamentForm-new (/tournaments/new → /tournaments),
 *          TournamentForm-edit (/tournaments/:id/edit → /tournaments),
 *          SlotOptimization (/tournaments/:tournamentId/slot-optimization → /tournaments/:tournamentId/edit).
 *
 * DEC-2: pure TypeScript module — no SvelteKit primitives, no new npm dependency.
 * DEC-22: TDD Iron Law — RED-first tests in Teams.test.ts (AC5).
 */

/**
 * Route pattern → parent path template.
 * `:tournamentId` is a placeholder resolved at runtime by resolveParent().
 */
export const PARENT_ROUTE_MAP: Record<string, string> = {
  // S01 entries (8 Pattern-a routes)
  '/tournaments/:tournamentId/teams': '/tournaments/:tournamentId/edit',
  '/tournaments/:tournamentId/draft': '/tournaments/:tournamentId/edit',
  '/tournaments/:tournamentId/audio': '/tournaments/:tournamentId/edit',
  '/tournaments/:tournamentId/timer-link': '/tournaments/:tournamentId/edit',
  '/tournaments/:tournamentId/photos': '/tournaments/:tournamentId/edit',
  '/tournaments/:tournamentId/certificate-template': '/tournaments/:tournamentId/edit',
  // S02 entries (3 special-pattern sub-routes — Brief D-12)
  '/tournaments/new': '/tournaments',
  '/tournaments/:id/edit': '/tournaments',
  '/tournaments/:tournamentId/slot-optimization': '/tournaments/:tournamentId/edit',
};

/**
 * Resolve the parent path for a given route pattern and concrete tournament UUID.
 *
 * @param pattern the route pattern key (e.g. '/tournaments/:tournamentId/teams')
 * @param tournamentId the concrete tournament UUID from route params
 * @returns resolved parent path (e.g. '/tournaments/abc-123/edit'), or null for top-level routes
 *
 * @example
 * resolveParent('/tournaments/:tournamentId/teams', 'abc-123')
 * // → '/tournaments/abc-123/edit'
 *
 * resolveParent('/tournaments', '')
 * // → null
 */
export function resolveParent(pattern: string, tournamentId: string): string | null {
  const template = PARENT_ROUTE_MAP[pattern];
  if (!template) return null;
  return template.replace(':tournamentId', tournamentId);
}
