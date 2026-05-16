package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link TeamAvatarRating} entities.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see TeamAvatarRating
 * @since E57S01
 */
public interface TeamAvatarRatingRepository {

    /**
     * Persists a TeamAvatarRating. Inserts if new, updates if it exists.
     *
     * @param rating the rating to save
     * @return the saved rating
     */
    TeamAvatarRating save(TeamAvatarRating rating);

    /**
     * Returns the TeamAvatarRating for the given avatarId.
     *
     * @param avatarId the avatar UUID (PK)
     * @return Optional.of(rating) if found, Optional.empty() if not found
     */
    Optional<TeamAvatarRating> findByAvatarId(UUID avatarId);

    /**
     * Returns the TeamAvatarRating for the given id (alias for {@link #findByAvatarId(UUID)}).
     *
     * @param avatarId the avatar UUID (PK)
     * @return Optional.of(rating) if found, Optional.empty() if not found
     */
    Optional<TeamAvatarRating> findById(UUID avatarId);

    /**
     * Returns all {@link TeamAvatarRating} records for avatars belonging to the given phase.
     *
     * <p>Uses a JOIN on {@code team_avatar.phase_id} to collect all ratings in bulk. This method is
     * the production callsite for bulk-loading ratings before passing them to a {@link
     * de.vvwt.tm.tournament.TeamSortCalculator} (DEC-69 — every public read method must have a
     * production callsite; AC7 of E58S03).
     *
     * @param phaseId the phase UUID; must not be {@code null}
     * @return list of ratings for all avatars in the phase; never {@code null}; may be empty if no
     *     avatars have ratings
     * @since E58S03 — AC7
     */
    List<TeamAvatarRating> findByPhaseId(UUID phaseId);

    /**
     * Deletes the TeamAvatarRating for the given avatarId.
     *
     * @param avatarId the avatar UUID (PK)
     */
    void deleteByAvatarId(UUID avatarId);
}
