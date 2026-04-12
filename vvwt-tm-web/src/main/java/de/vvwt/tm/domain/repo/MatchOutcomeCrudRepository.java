package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.MatchOutcome;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link MatchOutcome} persistence.
 * Wired into {@link MatchOutcomeRepository} as the low-level CRUD provider.
 */
interface MatchOutcomeCrudRepository extends CrudRepository<MatchOutcome, UUID> {
}
