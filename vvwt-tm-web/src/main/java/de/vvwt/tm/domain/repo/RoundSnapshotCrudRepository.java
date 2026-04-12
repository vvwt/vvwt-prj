package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.RoundSnapshot;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link RoundSnapshot} persistence.
 * Wired into {@link RoundSnapshotRepository} as the low-level CRUD provider.
 */
interface RoundSnapshotCrudRepository extends CrudRepository<RoundSnapshot, UUID> {
}
