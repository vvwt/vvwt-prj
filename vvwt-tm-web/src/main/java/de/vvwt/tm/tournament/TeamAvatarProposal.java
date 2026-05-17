// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Bounded-context-owned query-shape record for a proposed team-to-(group, position) assignment
 * (E48S07 + E48S20 + E51S13, AC-IMPL-DTO-PLACEMENT, AC-IMPL-DTO-EXTENDED,
 * AC-IMPL-DTO-SORTTYPE-NULLABLE).
 *
 * <p>Returned by {@link PhaseTransitionService#proposeTransition(UUID)} as part of the
 * Drag&amp;Drop Phase-Transition backend. Represents one slot in the target phase's team
 * distribution grid.
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@link #teamId} — internal swap-key (UUID). NEVER rendered to the organizer per DEC-9; used
 *       exclusively for server-side commit-payload identification and slot-swap operations.
 *   <li>{@link #teamNumber} — human-readable registration number (legacy Mannschaftsnummer) for
 *       organizer-facing source-pane display. Populated by {@code DefaultPhaseTransitionService}.
 *   <li>{@link #teamDescription} — human-readable team name / club label for organizer-facing
 *       display. Populated by {@code DefaultPhaseTransitionService}. Never null for Phase-1 or
 *       Phase-2+ proposals.
 *   <li>{@link #groupNumber} — structural identity field (DEC-9). Target group within the next
 *       phase (1-based).
 *   <li>{@link #groupPosition} — structural identity field (DEC-9). Target position within the
 *       group (1-based).
 *   <li>{@link #sourceGroupNumber} — the team's group number in the PREVIOUS phase (Phase-N source
 *       slot). {@code null} for Phase-1 proposals (no previous phase). Non-null Integer for
 *       Phase-2+ proposals.
 *   <li>{@link #sourceGroupPosition} — the team's position in the PREVIOUS phase. {@code null} for
 *       Phase-1 proposals. Non-null Integer for Phase-2+ proposals.
 *   <li>{@link #sortType} — the domain sortType string from the target phase's DraftSection (e.g.
 *       {@code "team_number"}, {@code "placement_group"}, {@code "group_placement"}). Nullable for
 *       backward-compat with commit-path callers (see {@link #forCommit}). Used by the frontend to
 *       drive source-pane label rendering without data-presence heuristics (E51S13, DEC-9).
 * </ul>
 *
 * <h2>DEC-9 note</h2>
 *
 * <p>Per DEC-9, UUIDs must NOT appear in organizer-facing UI. The {@link #teamId} field is retained
 * in this record because the commit-endpoint ({@code POST transition-commit}) and slot-swap
 * operations require a stable swap-key. The frontend component MUST NOT render {@code teamId} in
 * the DOM; it renders {@code teamNumber} and {@code teamDescription} instead. The {@link #sortType}
 * field is a domain String (e.g., {@code "team_number"}), never a UUID — DEC-9 no-UUID-in-DOM
 * invariant preserved (AC-IMPL-DEC-9-NO-UUID-IN-DOM).
 *
 * <h2>Placement (DEC-40 Clause B — Pattern A)</h2>
 *
 * <p>Lives in the bounded-context public package {@code de.vvwt.tm.tournament.*}. The projection
 * shape equals the wire shape (no field omission, aliasing, or cross-context aggregation) → Pattern
 * A applies. The web controller serialises this record directly via Jackson.
 *
 * @param teamId internal swap-key (UUID) — NEVER rendered to organizer per DEC-9
 * @param teamNumber registration number (Mannschaftsnummer) for organizer-facing display
 * @param teamDescription team name/club label for organizer-facing display
 * @param groupNumber target group in next phase (structural identity, DEC-9)
 * @param groupPosition target position in group (structural identity, DEC-9)
 * @param sourceGroupNumber team's group in previous phase (null for Phase 1)
 * @param sourceGroupPosition team's position in previous phase (null for Phase 1)
 * @param sortType domain sortType string from the target DraftSection (null for commit-path); a
 *     domain String value, NOT a UUID (AC-IMPL-DEC-9-NO-UUID-IN-DOM)
 * @see PhaseTransitionService
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity; UUIDs must not surface in organizer
 *     UI</a>
 * @see <a href="DEC-40">DEC-40 Clause B 2026-04-27 clarification — bounded-context-owned
 *     query-shape DTOs</a>
 * @see <a href="E48S07">E48S07 — Drag&amp;Drop Phase-Transition Backend</a>
 * @see <a href="E48S20">E48S20 — DTO widening: display fields + source-slot fields</a>
 * @see <a href="E51S13">E51S13 — Bug 2a sortType-driven source-pane label (replaces hasSourceSlot
 *     heuristic)</a>
 */
public record TeamAvatarProposal(
        UUID teamId,
        int teamNumber,
        String teamDescription,
        int groupNumber,
        int groupPosition,
        Integer sourceGroupNumber,
        Integer sourceGroupPosition,
        String sortType) {

    /**
     * Compact factory for commit-path construction (controller → service).
     *
     * <p>The commit-path only needs structural identity fields ({@code teamId}, {@code
     * groupNumber}, {@code groupPosition}). Display fields ({@code teamNumber}, {@code
     * teamDescription}, {@code sourceGroupNumber}, {@code sourceGroupPosition}) and {@code
     * sortType} are not used by {@code commitTransition} — they are meaningful only for the
     * proposal/review phase (AC-IMPL-DTO-SORTTYPE-NULLABLE).
     *
     * <p>Callers: {@link de.vvwt.tm.web.PhaseTransitionController#commitTransition} (maps web DTO →
     * domain proposal) and integration tests that exercise the commit path.
     *
     * @param teamId internal swap-key UUID
     * @param groupNumber target group (1-based)
     * @param groupPosition target position (1-based)
     * @return a proposal with zero teamNumber, null teamDescription, null source fields, null
     *     sortType
     */
    public static TeamAvatarProposal forCommit(UUID teamId, int groupNumber, int groupPosition) {
        return new TeamAvatarProposal(
                teamId, 0, null, groupNumber, groupPosition, null, null, null);
    }
}
