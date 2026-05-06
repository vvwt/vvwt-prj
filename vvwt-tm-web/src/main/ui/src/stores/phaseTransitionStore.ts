/**
 * Phase-Transition API functions for the Tournament Manager Admin SPA (E48S08).
 *
 * Provides types and API calls for the Drag&Drop Phase-Transition frontend:
 *   - fetchProposal: GET /api/phases/{phaseId}/transition-proposal
 *   - commitTransition: POST /api/phases/{phaseId}/transition-commit
 *
 * Backend DTO shape (E48S07 TeamAvatarAssignment Java record):
 *   teamId: UUID (string on wire), groupNumber: int, groupPosition: int
 *
 * DEC-9: structural identity (teamId, groupNumber, groupPosition).
 * Note: phaseId is always implicit from the URL path, NOT in the body.
 */
import { apiFetch } from '../lib/api.js';

// ── Types ──────────────────────────────────────────────────────────────────────

/**
 * A single team-to-(group, position) slot.
 *
 * Matches the shape of E48S07 TeamAvatarAssignment Java record:
 *   record TeamAvatarAssignment(UUID teamId, int groupNumber, int groupPosition)
 *
 * Also used as the proposal type (TeamAvatarProposal has the identical shape).
 * DEC-9: structural identity is (groupNumber, groupPosition); teamId is the booking.
 */
export interface TeamAvatarSlot {
    /** Team UUID (string on the JSON wire). */
    teamId: string;
    /** Group number within the target phase (1-based). */
    groupNumber: number;
    /** Position within the group (1-based). */
    groupPosition: number;
}

/** Alias — assignments submitted to commitTransition have the same shape as proposal slots. */
export type TeamAvatarAssignment = TeamAvatarSlot;

// ── API ────────────────────────────────────────────────────────────────────────

/**
 * Fetches the proposed team-to-(group, position) distribution for the target phase.
 *
 * GET /api/phases/{phaseId}/transition-proposal
 *
 * @param phaseId the UUID of the target (next) phase
 * @returns list of proposed assignments (initial distribution)
 * @throws Error when the server returns a non-2xx status
 */
export async function fetchProposal(phaseId: string): Promise<TeamAvatarSlot[]> {
    const res = await apiFetch(`/api/phases/${phaseId}/transition-proposal`);
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error((err as { message?: string }).message ?? `HTTP ${res.status}`);
    }
    return res.json() as Promise<TeamAvatarSlot[]>;
}

/**
 * Commits the (admin-corrected) team assignment for the target phase.
 *
 * POST /api/phases/{phaseId}/transition-commit
 * Body: JSON array of {teamId, groupNumber, groupPosition}
 *
 * phaseId is passed via URL — NOT in the body (DEC-9 governance,
 * AC-FRONTEND-COMMIT-PAYLOAD-SHAPE).
 *
 * @param phaseId the UUID of the target phase
 * @param assignments the final (possibly drag-corrected) assignments
 * @throws Error when the server returns a non-2xx status
 */
export async function commitTransition(
    phaseId: string,
    assignments: TeamAvatarAssignment[]
): Promise<void> {
    const res = await apiFetch(`/api/phases/${phaseId}/transition-commit`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(assignments),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error((err as { message?: string }).message ?? `HTTP ${res.status}`);
    }
}
