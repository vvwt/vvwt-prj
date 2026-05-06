package de.vvwt.tm.web.internal.dto;

import java.util.UUID;

/**
 * Web-tier request body DTO for committing a team-to-(group, position) assignment (E48S07,
 * AC-IMPL-DTO-PLACEMENT).
 *
 * <p>Jackson-deserialised from the POST {@code /api/phases/:phaseId/transition-commit} request
 * body. Represents one slot assignment supplied by the admin after drag-and-drop correction.
 *
 * <h2>Placement (DEC-40 Clause B)</h2>
 *
 * <p>Placed in {@code de.vvwt.tm.web.internal.dto.*} as a web-tier-owned DTO — request bodies are
 * always web-tier DTOs per DEC-40 Clause B (the request body is the HTTP contract, not a domain
 * type). The controller maps this to {@link de.vvwt.tm.tournament.TeamAvatarProposal} before
 * invoking {@link de.vvwt.tm.tournament.PhaseTransitionService#commitTransition}.
 *
 * @see de.vvwt.tm.web.PhaseTransitionController
 * @see de.vvwt.tm.tournament.TeamAvatarProposal
 * @see <a href="DEC-40">DEC-40 Clause B — web-tier DTO placement for request bodies</a>
 * @see <a href="E48S07">E48S07 — AC-IMPL-DTO-PLACEMENT</a>
 */
public record TeamAvatarAssignment(UUID teamId, int groupNumber, int groupPosition) {}
