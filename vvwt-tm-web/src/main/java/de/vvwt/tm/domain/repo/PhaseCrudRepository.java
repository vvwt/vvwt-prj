package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Phase;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link Phase} persistence.
 * Wired into {@link PhaseRepository} as the low-level CRUD provider.
 */
interface PhaseCrudRepository extends CrudRepository<Phase, UUID> {
}
