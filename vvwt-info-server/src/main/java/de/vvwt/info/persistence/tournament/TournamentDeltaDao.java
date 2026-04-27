package de.vvwt.info.persistence.tournament;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link TournamentDeltaRecord}.
 *
 * <p>Provides access to the {@code tournament_delta} event log table. The composite PK {@code
 * (tournament_id, seq)} enforces uniqueness of sequence numbers within a tournament at the DB level
 * (AC13a). Gap-free monotonicity of {@code seq} is a service-layer invariant enforced by the
 * publisher service (E38S05 scope).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC13</a>
 */
public interface TournamentDeltaDao
        extends CrudRepository<TournamentDeltaRecord, TournamentDeltaRecord.Key> {

    /**
     * Returns all delta records for a tournament with {@code seq > sinceSeq}, ordered by {@code
     * seq} ascending. Used by the reader endpoint to stream incremental updates.
     *
     * @param tournamentId the tournament to query
     * @param sinceSeq lower bound (exclusive) — return deltas with seq strictly greater than this
     * @return ordered list of delta records since {@code sinceSeq}
     */
    @Query(
            "SELECT * FROM tournament_delta "
                    + "WHERE tournament_id = :tournamentId AND seq > :sinceSeq "
                    + "ORDER BY seq ASC")
    List<TournamentDeltaRecord> findDeltasSince(String tournamentId, long sinceSeq);
}
