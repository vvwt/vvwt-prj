package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Bounded-context-owned query-shape record for a proposed team-to-(group, position) assignment
 * (E48S07, AC-IMPL-DTO-PLACEMENT).
 *
 * <p>Returned by {@link PhaseTransitionService#proposeTransition(UUID)} as part of the
 * Drag&amp;Drop Phase-Transition backend. Represents one slot in the target phase's team
 * distribution grid.
 *
 * <h2>Placement (DEC-40 Clause B — Pattern A)</h2>
 *
 * <p>Lives in the bounded-context public package {@code de.vvwt.tm.tournament.*}. The projection
 * shape equals the wire shape (no field omission, aliasing, or cross-context aggregation) → Pattern
 * A applies. The web controller serialises this record directly via Jackson.
 *
 * @see PhaseTransitionService
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity (phaseId, groupNumber,
 *     groupPosition)</a>
 * @see <a href="DEC-40">DEC-40 Clause B 2026-04-27 clarification — bounded-context-owned
 *     query-shape DTOs</a>
 * @see <a href="E48S07">E48S07 — Drag&amp;Drop Phase-Transition Backend</a>
 */
public record TeamAvatarProposal(UUID teamId, int groupNumber, int groupPosition) {}
