package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.TeamAvatar;

import java.util.List;
import java.util.UUID;

/**
 * REST response DTO for the mapping apply and re-do endpoints (E05S08 AC2, AC10).
 *
 * <p>Returns the list of created {@link TeamAvatar} UUIDs with their structural positions.
 *
 * @param phaseId          the phase for which TeamAvatars were created
 * @param avatarsCreated   list of created TeamAvatar entries
 */
public record MappingApplyResponse(
        UUID phaseId,
        List<AvatarEntry> avatarsCreated
) {

    /**
     * Constructs from a list of persisted {@link TeamAvatar} entities.
     *
     * @param phaseId the target phase
     * @param avatars the created entities
     * @return the DTO
     */
    public static MappingApplyResponse from(UUID phaseId, List<TeamAvatar> avatars) {
        List<AvatarEntry> entries = avatars.stream()
                .map(a -> new AvatarEntry(a.getId(), a.getTeamId(), a.getGroupNumber(), a.getGroupPosition()))
                .toList();
        return new MappingApplyResponse(phaseId, entries);
    }

    /**
     * One created TeamAvatar entry.
     *
     * @param avatarId      the new TeamAvatar UUID
     * @param teamId        the assigned team UUID
     * @param groupNumber   group number in the target phase
     * @param groupPosition position within the group
     */
    public record AvatarEntry(UUID avatarId, UUID teamId, int groupNumber, int groupPosition) {}
}
