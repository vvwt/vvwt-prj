/**
 * Declarative parent-route map for the back-arrow navigation mechanism.
 * Story E47S01 — AC5, Brief D-12. Updated by E48S15 — Brief D-2 (P→Tournaments).
 *
 * Maps route pattern → parent path template. The map is a const — no
 * regex-derivation, no path-segment-trimming heuristic (per Brief D-12
 * rationale: heuristics break on edge cases like /tournaments/new vs /tournaments).
 *
 * resolveParent() substitutes :tournamentId with the concrete UUID from the
 * route params, returning the fully-resolved parent path. Returns null for
 * top-level routes (no back-arrow).
 *
 * Convention (E48S15, User decision 2026-05-06):
 *   - Tournament-sub-routes (/tournaments/:tournamentId/{subroute}) target /tournaments (the list).
 *   - Sub-sub-routes (/tournaments/:tournamentId/phases/:phaseId/transition) target
 *     their immediate parent (/phases).
 *
 * Rationale: Tournament-Edit is a one-time-setup page. Routing back through it strands
 * the user and forces a 2-step navigation to reach the list. /tournaments is the user's
 * natural navigation hub (User feedback 2026-05-06).
 *
 * Reference: Story E48S15, Brief discovery-2026-05-06-tournament-form-bugs D-2 (P→Tournaments).
 *
 * Future Discovery sessions adding new Tournament-sub-routes MUST default to /tournaments
 * as the back-arrow target. Sub-sub-routes (per-phase, per-match drill-downs) target their
 * immediate parent.
 *
 * DEC-2: pure TypeScript module — no SvelteKit primitives, no new npm dependency.
 * DEC-22: TDD Iron Law — RED-first tests in Teams.test.ts.
 */

/**
 * Route pattern → parent path template.
 * `:tournamentId` is a placeholder resolved at runtime by resolveParent().
 */
export const PARENT_ROUTE_MAP: Record<string, string> = {
  // Tournament sub-routes: all target /tournaments (the list) — E48S15 P→Tournaments convention
  '/tournaments/:tournamentId/teams':                '/tournaments',
  '/tournaments/:tournamentId/draft':                '/tournaments',
  '/tournaments/:tournamentId/audio':                '/tournaments',
  '/tournaments/:tournamentId/timer-link':           '/tournaments',
  '/tournaments/:tournamentId/photos':               '/tournaments',
  '/tournaments/:tournamentId/certificate-template': '/tournaments',
  // E52S02: certificates generation route — back-arrow to tournament list (E48S15 P→Tournaments convention)
  '/tournaments/:tournamentId/certificates':         '/tournaments',
  '/tournaments/:tournamentId/slot-optimization':    '/tournaments',
  // Tournament CRUD routes (special-pattern — Brief D-12)
  '/tournaments/new': '/tournaments',
  '/tournaments/:id/edit': '/tournaments',
  // E48S05: phases overview — back-arrow to tournament list
  '/tournaments/:tournamentId/phases': '/tournaments',
  // E48S08: phase-transition DnD — sub-sub-route targets immediate parent (/phases)
  '/tournaments/:tournamentId/phases/:phaseId/transition': '/tournaments/:tournamentId/phases',
  // E48S18: prepare route (Phase 1) — sub-sub-route targets immediate parent (/phases)
  '/tournaments/:tournamentId/phases/:phaseId/prepare': '/tournaments/:tournamentId/phases',
  // E48S26: match-overview route (new) — back-arrow to phases overview
  '/tournaments/:tournamentId/phases/:phaseId/matches': '/tournaments/:tournamentId/phases',
  // E48S25 → E48S26: match correction route — back-arrow to match-overview (the calling page)
  '/tournaments/:tournamentId/phases/:phaseId/matches/:matchId/correction': '/tournaments/:tournamentId/phases/:phaseId/matches',
};

/**
 * Resolve the parent path for a given route pattern and concrete route params.
 *
 * Substitutes `:tournamentId` and (optionally) `:phaseId` in the parent path template.
 * E48S26: phaseId is required for sub-sub-routes whose parent path includes `:phaseId`
 * (e.g. the match-overview and correction routes).
 *
 * @param pattern the route pattern key (e.g. '/tournaments/:tournamentId/teams')
 * @param tournamentId the concrete tournament UUID from route params
 * @param phaseId (optional) the concrete phase UUID — required for routes whose parent includes :phaseId
 * @returns resolved parent path (e.g. '/tournaments/abc-123/phases/def-456/matches'), or null for top-level routes
 *
 * @example
 * resolveParent('/tournaments/:tournamentId/teams', 'abc-123')
 * // → '/tournaments'  (E48S15: P→Tournaments convention)
 *
 * resolveParent('/tournaments/:tournamentId/phases/:phaseId/matches/:matchId/correction', 'abc-123', 'def-456')
 * // → '/tournaments/abc-123/phases/def-456/matches'  (E48S26: correction → match-overview)
 *
 * resolveParent('/tournaments', '')
 * // → null
 */
export function resolveParent(pattern: string, tournamentId: string, phaseId?: string): string | null {
  const template = PARENT_ROUTE_MAP[pattern];
  if (!template) return null;
  let resolved = template.replace(':tournamentId', tournamentId);
  if (phaseId) {
    resolved = resolved.replace(':phaseId', phaseId);
  }
  return resolved;
}
