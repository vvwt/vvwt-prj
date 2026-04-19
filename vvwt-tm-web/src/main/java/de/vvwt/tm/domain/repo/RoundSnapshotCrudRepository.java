package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.RoundSnapshot;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC delegate for {@link RoundSnapshot} persistence. Wired into {@link
 * RoundSnapshotRepository} as the low-level CRUD provider.
 */
interface RoundSnapshotCrudRepository extends CrudRepository<RoundSnapshot, UUID> {}
