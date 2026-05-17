// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
  // E63S05: embedded worker control — top-level admin route, back-arrow to /tournaments
  '/embedded-worker': '/tournaments',
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
