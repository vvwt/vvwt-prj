package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.TeamAvatarRating;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC delegate for {@link TeamAvatarRating} persistence. Wired into {@link
 * TeamAvatarRatingRepository} as the low-level CRUD provider.
 */
interface TeamAvatarRatingCrudRepository extends CrudRepository<TeamAvatarRating, UUID> {}
