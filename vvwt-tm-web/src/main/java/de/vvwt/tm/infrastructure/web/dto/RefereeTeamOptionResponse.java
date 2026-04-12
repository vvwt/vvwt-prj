package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.RefereeAssignmentService.RefereeTeamOption;

import java.util.UUID;

/**
 * REST representation of an eligible referee team option for the override dropdown (E05S09 AC6).
 *
 * @param avatarId  the avatar ID of this team in the phase — submitted as {@code refereeTeamAvatarId}
 *                  in the override request
 * @param teamId    the underlying team UUID
 * @param teamName  display name of the team
 */
public record RefereeTeamOptionResponse(
        UUID avatarId,
        UUID teamId,
        String teamName
) {
    /**
     * Converts a domain referee team option to a REST response.
     *
     * @param option the domain option (must not be {@code null})
     * @return the REST response
     */
    public static RefereeTeamOptionResponse from(RefereeTeamOption option) {
        return new RefereeTeamOptionResponse(
                option.avatarId(),
                option.teamId(),
                option.teamName()
        );
    }
}
