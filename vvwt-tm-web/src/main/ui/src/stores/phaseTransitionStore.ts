/**
 * Phase-Transition API functions for the Tournament Manager Admin SPA (E48S08 + E51S13).
 *
 * Provides types and API calls for the Drag&Drop Phase-Transition frontend:
 *   - fetchProposal: GET /api/phases/{phaseId}/transition-proposal
 *   - commitTransition: POST /api/phases/{phaseId}/transition-commit
 *   - sourceLabelBySortType: source-pane label resolver (E51S13 — replaces hasSourceSlot)
 *
 * Backend DTO shape (E51S13 TeamAvatarProposal Java record — extended with sortType):
 *   teamId: UUID (string on wire), teamNumber: int, teamDescription: string,
 *   groupNumber: int, groupPosition: int,
 *   sourceGroupNumber: int|null, sourceGroupPosition: int|null,
 *   sortType: string|null
 *
 * DEC-9: structural identity (teamId, groupNumber, groupPosition). UUID must NOT be rendered in DOM.
 * Note: phaseId is always implicit from the URL path, NOT in the body.
 */
import { apiFetch } from '../lib/api.js';

// ── Types ──────────────────────────────────────────────────────────────────────

/**
 * A single team-to-(group, position) slot as returned by the proposal endpoint.
 *
 * Matches the shape of E51S13 TeamAvatarProposal Java record (8 fields):
 *   record TeamAvatarProposal(UUID teamId, int teamNumber, String teamDescription,
 *                             int groupNumber, int groupPosition,
 *                             Integer sourceGroupNumber, Integer sourceGroupPosition,
 *                             String sortType)
 *
 * DEC-9: structural identity is (groupNumber, groupPosition); teamId is the internal swap-key
 * (must NEVER be rendered in DOM per DEC-9).
 * E48S20: teamNumber and teamDescription are the organizer-facing display labels.
 * sourceGroupNumber/sourceGroupPosition are the team's slot in the previous phase (null for Phase 1).
 * E51S13: sortType is the canonical domain trigger for source-pane label rendering. Nullable for
 *   backward-compat (e.g., legacy proposals without sortType). Value is a domain String
 *   (e.g., "team_number"), NOT a UUID — DEC-9 invariant preserved (AC-IMPL-DEC-9-NO-UUID-IN-DOM).
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
    /**
     * Domain sortType from the target DraftSection (e.g. "team_number", "placement_group",
     * "group_placement"). Nullable for backward-compat (legacy proposals without field).
     * Used by sourceLabelBySortType to drive source-pane label without data-presence heuristics.
     * Value is a domain String, NOT a UUID (DEC-9, AC-IMPL-DEC-9-NO-UUID-IN-DOM, E51S13).
     */
    sortType: string | null;
}

/**
 * Returns the human-readable source-slot label for a slot, driven by {@code sortType}.
 *
 * This is the E51S13 replacement for the deleted {@code hasSourceSlot} type-guard (Brief D-8,
 * Brief D-9): instead of inferring the phase from data-presence, we switch on the canonical domain
 * trigger {@code sortType} from the backend.
 *
 * Label semantics:
 * - {@code sortType = "team_number"} → "Nr. {n}" using existing i18n key
 *   {@code phaseTransition.sourceLabelPhase1} (Phase 1 case)
 * - {@code sortType = "placement_group"} or {@code "group_placement"} → "Gruppe {g}, Platz {p}"
 *   using existing i18n key {@code phaseTransition.sourceLabelPhase2plus} (Phase 2+ case)
 * - {@code sortType = null/undefined} or unknown value → empty string (defensive fallback;
 *   never renders "undefined" — AC-ERROR-HANDLING-NULL-SORTTYPE,
 *   AC-ERROR-HANDLING-UNKNOWN-SORTTYPE)
 *
 * AC-IMPL-NO-NEW-I18N-KEYS (DEC-52): reuses existing keys
 * {@code phaseTransition.sourceLabelPhase1} and {@code phaseTransition.sourceLabelPhase2plus}.
 * No new keys added to de.json.
 *
 * @param slot the TeamAvatarSlot whose source label should be computed
 * @param t the svelte-i18n translation function (accepts key + optional {values} opts)
 * @returns human-readable source-pane label string; never contains "undefined"
 * @see <a href="E51S13">E51S13 — Bug 2a sortType-driven source-pane label</a>
 * @see <a href="DEC-9">DEC-9 — no UUID in DOM; sortType is a domain String</a>
 * @see <a href="DEC-52">DEC-52 — V1-DE-only i18n; no new keys</a>
 */
export function sourceLabelBySortType(
    slot: TeamAvatarSlot,
    t: (key: string, opts?: { values?: Record<string, unknown> }) => string
): string {
    const sortType = slot.sortType;
    if (sortType === 'team_number') {
        // Phase 1: label = "Nr. {n}" — uses existing phaseTransition.sourceLabelPhase1
        return t('phaseTransition.sourceLabelPhase1', {
            values: { n: String(slot.teamNumber) },
        });
    }
    if (sortType === 'placement_group' || sortType === 'group_placement') {
        // Phase 2+: label = "Gruppe {g}, Platz {p}" — uses existing phaseTransition.sourceLabelPhase2plus
        const g = slot.sourceGroupNumber ?? '';
        const p = slot.sourceGroupPosition ?? '';
        return t('phaseTransition.sourceLabelPhase2plus', {
            values: { g: String(g), p: String(p) },
        });
    }
    // Defensive fallback for null, undefined, or unknown sortType values.
    // Returns empty string — never renders "undefined" (AC-ERROR-HANDLING-NULL-SORTTYPE,
    // AC-ERROR-HANDLING-UNKNOWN-SORTTYPE).
    return '';
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
