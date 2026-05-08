/**
 * Phase-Transition API functions for the Tournament Manager Admin SPA (E48S08).
 *
 * Provides types and API calls for the Drag&Drop Phase-Transition frontend:
 *   - fetchProposal: GET /api/phases/{phaseId}/transition-proposal
 *   - commitTransition: POST /api/phases/{phaseId}/transition-commit
 *
 * Backend DTO shape (E48S20 TeamAvatarProposal Java record — widened):
 *   teamId: UUID (string on wire), teamNumber: int, teamDescription: string,
 *   groupNumber: int, groupPosition: int,
 *   sourceGroupNumber: int|null, sourceGroupPosition: int|null
 *
 * DEC-9: structural identity (teamId, groupNumber, groupPosition). UUID must NOT be rendered in DOM.
 * Note: phaseId is always implicit from the URL path, NOT in the body.
 */
import { apiFetch } from '../lib/api.js';

// ── Types ──────────────────────────────────────────────────────────────────────

/**
 * A single team-to-(group, position) slot as returned by the proposal endpoint.
 *
 * Matches the shape of E48S20 TeamAvatarProposal Java record (widened):
 *   record TeamAvatarProposal(UUID teamId, int teamNumber, String teamDescription,
 *                             int groupNumber, int groupPosition,
 *                             Integer sourceGroupNumber, Integer sourceGroupPosition)
 *
 * DEC-9: structural identity is (groupNumber, groupPosition); teamId is the internal swap-key
 * (must NEVER be rendered in DOM per DEC-9).
 * E48S20: teamNumber and teamDescription are the organizer-facing display labels.
 * sourceGroupNumber/sourceGroupPosition are the team's slot in the previous phase (null for Phase 1).
 */
export interface TeamAvatarSlot {
    /** Team UUID (string on the JSON wire). Internal swap-key — NEVER render in DOM (DEC-9). */
    teamId: string;
    /** Human-readable registration number (Mannschaftsnummer). Rendered in source pane. */
    teamNumber: number;
    /** Human-readable team name / club label. Rendered in source pane. */
    teamDescription: string;
    /** Group number within the target phase (1-based). Structural identity (DEC-9). */
    groupNumber: number;
    /** Position within the group (1-based). Structural identity (DEC-9). */
    groupPosition: number;
    /** Team's group in the previous phase (null for Phase 1). */
    sourceGroupNumber: number | null;
    /** Team's position in the previous phase (null for Phase 1). */
    sourceGroupPosition: number | null;
}

/**
 * Type guard: returns true if slot has Phase-2+ source fields (non-null sourceGroupNumber).
 * Use to determine whether to render the "Gruppe {g}, Platz {p}" source label.
 */
export function hasSourceSlot(slot: TeamAvatarSlot): slot is TeamAvatarSlot & {
    sourceGroupNumber: number;
    sourceGroupPosition: number;
} {
    return slot.sourceGroupNumber !== null && slot.sourceGroupPosition !== null;
}

/**
 * Commit-path payload type: only the three structural identity fields are sent to
 * POST /api/phases/{phaseId}/transition-commit. Display fields are not needed by the server.
 * (AC-IMPL-CONTROLLER-PASSTHROUGH, E48S20)
 */
export interface TeamAvatarAssignment {
    teamId: string;
    groupNumber: number;
    groupPosition: number;
}

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
 * POST {commitEndpointOverride} (default: /api/phases/{phaseId}/transition-commit)
 * Body: JSON array of {teamId, groupNumber, groupPosition}
 *
 * phaseId is passed via URL — NOT in the body (DEC-9 governance,
 * AC-FRONTEND-COMMIT-PAYLOAD-SHAPE).
 *
 * The optional {@code commitEndpointOverride} parameter allows callers (e.g. the
 * Vorbereiten-Route, E48S18) to target a different commit endpoint such as
 * {@code /api/phases/{phaseId}/prepare} without duplicating the DnD logic.
 * When omitted, the default {@code transition-commit} endpoint is used (Phase 2+ path).
 *
 * @param phaseId the UUID of the target phase
 * @param assignments the final (possibly drag-corrected) assignments
 * @param commitEndpointOverride optional URL override; defaults to transition-commit endpoint
 * @throws Error when the server returns a non-2xx status
 */
export async function commitTransition(
    phaseId: string,
    assignments: TeamAvatarAssignment[],
    commitEndpointOverride?: string
): Promise<void> {
    const url = commitEndpointOverride ?? `/api/phases/${phaseId}/transition-commit`;
    const res = await apiFetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(assignments),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error((err as { message?: string }).message ?? `HTTP ${res.status}`);
    }
}
