package de.vvwt.tm.tournament;

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
     * Deletes the TeamAvatarRating for the given avatarId.
     *
     * @param avatarId the avatar UUID (PK)
     */
    void deleteByAvatarId(UUID avatarId);
}
