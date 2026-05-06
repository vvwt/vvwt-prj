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
     * gameMode=siegerehrung}, the No-op generator bean (E48S02) handles match generation — no
     * matches are created.
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
}
