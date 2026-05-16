package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link TeamAvatar} entities.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see TeamAvatar
 * @since E57S01
 */
public interface TeamAvatarRepository {

    /**
     * Persists a TeamAvatar. Inserts if new.
     *
     * @param avatar the avatar to save
     * @return the saved avatar
     */
    TeamAvatar save(TeamAvatar avatar);

    /**
     * Returns the TeamAvatar with the given id.
     *
     * @param id the avatar UUID
     * @return Optional.of(avatar) if found, Optional.empty() if not found
     */
    Optional<TeamAvatar> findById(UUID id);

    /**
     * Returns all TeamAvatars assigned to the given team.
     *
     * @param teamId the team UUID
     * @return list of avatars; never null
     */
    List<TeamAvatar> findByTeamId(UUID teamId);

    /**
     * Returns all TeamAvatars for a given tournament and phase.
     *
     * @param tournamentId the tournament UUID
     * @param phaseId the phase UUID
     * @return list of avatars; never null
     */
    List<TeamAvatar> findByTournamentIdAndPhaseId(UUID tournamentId, UUID phaseId);

    /**
     * Returns all TeamAvatars for a given phase.
     *
     * @param phaseId the phase UUID
     * @return list of avatars; never null
     */
    List<TeamAvatar> findByPhaseId(UUID phaseId);

    /**
     * Deletes the TeamAvatar with the given id.
     *
     * @param id the avatar UUID
     */
    void deleteById(UUID id);

    /**
     * Finds the TeamAvatar for a given phase slot by structural identity (DEC-9).
     *
     * @param phaseId the phase UUID
     * @param groupNumber the group number within the phase (1-based)
     * @param groupPosition the position within the group (1-based)
     * @return Optional.of(avatar) if found; Optional.empty() if not found
     */
    Optional<TeamAvatar> findByPhaseIdAndGroupNumberAndGroupPosition(
            UUID phaseId, int groupNumber, int groupPosition);

    /**
     * Updates the {@code teamId} of the TeamAvatar with the given id.
     *
     * @param avatar the avatar whose {@code teamId} and {@code id} are used
     */
    void updateTeamId(TeamAvatar avatar);

    /**
     * Persists all TeamAvatars in the given list. Inserts each avatar if new.
     *
     * <p>Added by E58S02 (AC7) to replace the N×{@code save(TeamAvatar)} loop in {@code
     * persistStructuralAvatars()} with a single batch operation. Production callsite: {@code
     * DefaultDraftService.persistStructuralAvatars()} (DEC-70 — must have a production callsite).
     *
     * @param avatars the list of avatars to persist; must not be {@code null}; may be empty
     * @return the list of saved avatars, in the same order; never {@code null}
     */
    List<TeamAvatar> saveAll(List<TeamAvatar> avatars);
}
