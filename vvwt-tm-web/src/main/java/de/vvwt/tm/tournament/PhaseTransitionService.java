// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;
import java.util.UUID;

/**
 * Public port for Drag&amp;Drop Phase-Transition (DEC-35, E48S07).
 *
 * <p>Supports the generic Phase N → N+1 transition workflow: the admin receives a proposed
 * team-to-(group, position) mapping based on the target phase's {@code sortType}, optionally
 * corrects it via drag-and-drop, and commits the final assignment.
 *
 * <h2>Two operations</h2>
 *
 * <ul>
 *   <li>{@link #proposeTransition(UUID)} — read-only; no lock required.
 *   <li>{@link #commitTransition(UUID, List)} — writes TeamAvatars and (optionally) Matches;
 *       acquires per-tournament pessimistic DB row-lock (DEC-37 Clause B).
 * </ul>
 *
 * <p>The sole implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultPhaseTransitionService} in the {@code tournament.internal}
 * package per DEC-35.
 *
 * <h2>sortType semantics</h2>
 *
 * <ul>
 *   <li>{@code team_number} — Round-Robin distribution by team number ascending across N groups.
 *   <li>{@code placement_group} — Teams keep their Phase-N group; re-sorted by Phase-N placement
 *       within each group.
 *   <li>{@code group_placement} — Cross-group distribution: all 1st-place finishers from Phase-N go
 *       to Phase-N+1 Group 1, all 2nd-place go to Group 2, etc.
 * </ul>
 *
 * @see de.vvwt.tm.tournament.internal.DefaultPhaseTransitionService
 * @see TeamAvatarProposal
 * @see <a href="DEC-35">DEC-35 — interface in public package, impl in .internal</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock
 *     (commitTransition)</a>
 * @see <a href="E48S07">E48S07 — Drag&amp;Drop Phase-Transition Backend</a>
 */
public interface PhaseTransitionService {

    /**
     * Proposes an initial team-to-(group, position) distribution for the target phase.
     *
     * <p>Pure read-only operation — no lock, no persistence. The algorithm is determined by {@code
     * toPhase}'s {@code sortType} field from the tournament's {@code draft_json}.
     *
     * <p>The service resolves {@code fromPhase} internally via {@code
     * PhaseRepository.findByTournamentIdAndSequenceNumber(toPhase.tournamentId,
     * toPhase.sequenceNumber - 1)}.
     *
     * @param toPhaseId the UUID of the target (next) phase
     * @return list of proposed assignments, one per team from Phase N; never null
     * @throws IllegalArgumentException if no phase with the given id exists, or if {@code
     *     draft_json} is null or cannot be parsed (→ HTTP 400 per
     *     AC-ERROR-HANDLING-DRAFT-JSON-NULL)
     * @throws IllegalStateException if no fromPhase exists (toPhase is the first phase — no prior
     *     standings to base the proposal on)
     */
    List<TeamAvatarProposal> proposeTransition(UUID toPhaseId);

    /**
     * Commits a (possibly admin-corrected) team assignment for the target phase.
     *
     * <p>Acquires a per-tournament pessimistic DB row-lock (DEC-37 Clause B) as the first read.
     * Persists {@link TeamAvatar} entities for {@code toPhaseId}, then invokes match generation via
     * {@code PhasePreparationService.generateMatches(toPhaseId, gameMode)}. For {@code
     * gameMode=awardCeremony} (renamed from siegerehrung by E58S04), the No-op generator bean
     * (E48S02) handles match generation — no matches are created.
     *
     * @param toPhaseId the UUID of the target phase
     * @param assignments the final (admin-corrected) team-to-(group, position) assignments
     * @throws IllegalArgumentException if {@code assignments} contains duplicates, unknown team
     *     IDs, or out-of-range group/position values (→ HTTP 400 per
     *     AC-ERROR-HANDLING-INVALID-ASSIGNMENT)
     * @throws IllegalArgumentException if no phase with the given id exists, or if {@code
     *     draft_json} is null or cannot be parsed (→ HTTP 400 per
     *     AC-ERROR-HANDLING-DRAFT-JSON-NULL)
     */
    void commitTransition(UUID toPhaseId, List<TeamAvatarProposal> assignments);

    /**
     * Updates the {@code sortType} and {@code distributionMode} of the target PREPARED phase's
     * {@link de.vvwt.tm.tournament.draft.DraftSection} and returns the re-computed proposal (DEC-77
     * D-5, E66S02).
     *
     * <p>The operation is invalidation-neutral: it re-computes only the team-assignment proposal
     * for the target phase. It does NOT reset the phase, re-trigger match generation, or change any
     * other phase's data or status. The {@code teamId} write into avatar slots is NOT performed
     * here — that remains exclusively with {@link #commitTransition} per DEC-59 Clause C.
     *
     * <p>Membership validation: both {@code sortType} and {@code distributionMode} are validated
     * against their respective registries before any persistence. An unregistered key throws {@link
     * IllegalArgumentException} (DEC-73 D-6 — same membership rule applied at this new write site,
     * AC5 E66S02).
     *
     * <p>Write target: only the {@link de.vvwt.tm.tournament.draft.DraftSection} whose {@code
     * sectionNumber} matches the target phase's {@code sequenceNumber} is mutated in {@code
     * draft_json}. No other section is touched (AC4 E66S02).
     *
     * @param toPhaseId the UUID of the target (PREPARED) phase
     * @param sortType the new sort-type registry key (e.g. {@code "team_number"}, {@code
     *     "placement_group"}, {@code "group_placement"})
     * @param distributionMode the new distribution-mode registry key (e.g. {@code "sequential"},
     *     {@code "round_robin"})
     * @return the recomputed proposal list with the new sortType and distributionMode applied
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the phase is not {@code
     *     PREPARED}
     * @throws IllegalArgumentException if {@code sortType} or {@code distributionMode} is not
     *     registered, or if the phase or its tournament cannot be found
     * @see <a href="DEC-77">DEC-77 D-5 — operator-editable post-apply sortType/distributionMode
     *     surface</a>
     * @see <a href="DEC-73">DEC-73 D-6 — registry-membership check</a>
     * @see <a href="E66S02">E66S02 — AC4, AC5, AC6</a>
     */
    List<TeamAvatarProposal> updateSortAndDistribution(
            UUID toPhaseId, String sortType, String distributionMode);
}
