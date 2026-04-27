package de.vvwt.info.persistence.tournament;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link TournamentRecord}.
 *
 * <p>Provides CRUD access to the {@code tournament} table. Key invariants (partial uniqueness of
 * active tournaments, 24h grace window) are enforced by:
 *
 * <ul>
 *   <li>PostgreSQL: partial unique index {@code uq_active_tournament_per_location} (AC12).
 *   <li>H2: service-layer guard at supersede time (H2 lacks partial unique indexes).
 * </ul>
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC12</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-42.md">DEC-42</a>
 */
public interface TournamentDao extends CrudRepository<TournamentRecord, String> {

    /**
     * Returns the currently active tournament for a given tenant and location.
     *
     * <p>C-B1c: at most one active tournament per {@code (tenant_id, location_id)} may exist
     * ({@code superseded_at IS NULL}).
     *
     * @param tenantId tenant identifier
     * @param locationId location identifier
     * @return the active tournament record, or empty if none
     */
    @Query(
            "SELECT * FROM tournament "
                    + "WHERE tenant_id = :tenantId "
                    + "  AND location_id = :locationId "
                    + "  AND superseded_at IS NULL "
                    + "LIMIT 1")
    Optional<TournamentRecord> findActiveTournament(String tenantId, String locationId);

    /**
     * Returns a tournament by its opaque bearer token.
     *
     * @param tournamentToken the QR-distributed bearer token
     * @return the tournament record, or empty if not found
     */
    @Query("SELECT * FROM tournament WHERE tournament_token = :tournamentToken LIMIT 1")
    Optional<TournamentRecord> findByTournamentToken(String tournamentToken);
}
