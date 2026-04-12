package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for the referee override endpoint (E05S09 AC2).
 *
 * <p>The {@code refereeTeamAvatarId} identifies the team via its avatar in the phase,
 * consistent with the DEC-9 structural identity model: within the phase, teams are
 * identified by their avatar (phaseId, groupNumber, groupPosition), not by their
 * standalone team UUID.
 *
 * @param refereeTeamAvatarId  the avatar UUID of the team to assign as referee
 *                             (must not be {@code null})
 */
public record RefereeOverrideRequest(
        @NotNull(message = "refereeTeamAvatarId must not be null")
        UUID refereeTeamAvatarId
) {}
