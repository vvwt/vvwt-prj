package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.RoundSnapshot;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link RoundSnapshot} persistence.
 * Wired into {@link RoundSnapshotRepository} as the low-level CRUD provider.
 */
interface RoundSnapshotCrudRepository extends CrudRepository<RoundSnapshot, UUID> {

    /**
     * Finds the snapshot for a given (tournament, phase, lap) triple (unfiltered).
     *
     * <p>Used by {@link RoundSnapshotRepository#findByTournamentPhaseAndLap} and the
     * round-end snapshot service (E03S13, AC5) to detect existing snapshots before insert.
     *
     * @param tournamentId the tournament
     * @param phaseId      the phase
     * @param lapNumber    the lap number
     * @return the existing snapshot, or empty if none
     */
    @Query("SELECT * FROM round_snapshots WHERE tournament_id = :tournamentId "
         + "AND phase_id = :phaseId AND lap_number = :lapNumber")
    Optional<RoundSnapshot> findByTournamentPhaseAndLapRaw(
            @Param("tournamentId") UUID tournamentId,
            @Param("phaseId") UUID phaseId,
            @Param("lapNumber") int lapNumber);
}
