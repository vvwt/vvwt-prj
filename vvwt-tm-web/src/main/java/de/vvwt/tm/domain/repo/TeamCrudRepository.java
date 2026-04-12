package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Team;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link Team} persistence.
 * Wired into {@link TeamRepository} as the low-level CRUD provider.
 */
interface TeamCrudRepository extends CrudRepository<Team, UUID> {
}
