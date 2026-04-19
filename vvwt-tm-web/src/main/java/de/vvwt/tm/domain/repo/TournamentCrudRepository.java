package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Tournament;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC delegate for {@link Tournament} persistence. Wired into {@link
 * TournamentRepository} as the low-level CRUD provider.
 *
 * <p>Not intended for direct use by domain/service code — use {@link TournamentRepository} instead
 * to ensure tenant scoping is enforced.
 */
interface TournamentCrudRepository extends CrudRepository<Tournament, UUID> {}
