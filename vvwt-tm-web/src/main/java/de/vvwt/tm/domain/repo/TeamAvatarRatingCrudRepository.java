package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.TeamAvatarRating;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link TeamAvatarRating} persistence.
 * Wired into {@link TeamAvatarRatingRepository} as the low-level CRUD provider.
 */
interface TeamAvatarRatingCrudRepository extends CrudRepository<TeamAvatarRating, UUID> {
}
