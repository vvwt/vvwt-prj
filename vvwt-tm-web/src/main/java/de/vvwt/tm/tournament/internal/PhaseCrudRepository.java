package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Phase;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC delegate for {@link Phase} persistence (implementation — inventory line 297).
 *
 * <p>Lives at {@code de.vvwt.tm.tournament.internal} per DEC-21 internal-package discipline.
 * Wired into {@link de.vvwt.tm.tournament.PhaseRepository} as the low-level CRUD provider.
 *
 * <p><b>Activation note:</b> This interface is intentionally NOT registered as an active Spring
 * Data JDBC repository during the E21 reconstruction-in-place phase. Spring Data JDBC would
 * attempt to scan the {@code Phase} entity at {@code de.vvwt.tm.tournament.Phase} and the legacy
 * {@code de.vvwt.tm.domain.Phase} simultaneously — same {@code @Table("phase")} — causing an
 * {@code IllegalStateException} in the MappingContext (same pattern as E21S02
 * TournamentCrudRepository §Deviation). {@link de.vvwt.tm.tournament.PhaseRepository} uses
 * JdbcTemplate + manual RowMapper until the E21S13 atomic cutover deletes the legacy entity.
 *
 * @see de.vvwt.tm.tournament.PhaseRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 297)</a>
 */
public interface PhaseCrudRepository extends CrudRepository<Phase, UUID> {

    /**
     * Returns all phases belonging to the given tournament (unscoped — tenant filtering applied by
     * {@link de.vvwt.tm.tournament.PhaseRepository#findByTournamentId(UUID)}).
     *
     * @param tournamentId the tournament to query
     * @return all phases for the given tournament, unscoped by tenant
     */
    @Query("SELECT * FROM phase WHERE tournament_id = :tournamentId")
    List<Phase> findByTournamentIdRaw(@Param("tournamentId") UUID tournamentId);
}
