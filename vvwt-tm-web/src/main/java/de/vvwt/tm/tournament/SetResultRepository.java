package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link SetResult} entities.
 *
 * <p>Boundary-API per inventory line 301 — consumed by {@code CascadeRecomputeService} in the
 * {@code scoring} context.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see SetResult
 * @since E57S01
 */
public interface SetResultRepository {

    /**
     * Inserts a new {@link SetResult} row.
     *
     * @param setResult the set result to insert
     */
    void insert(SetResult setResult);

    /**
     * Updates an existing {@link SetResult} row by composite PK.
     *
     * @param setResult the updated set result
     */
    void update(SetResult setResult);

    /**
     * Returns all set results for a given match.
     *
     * @param matchId the match whose set results to retrieve
     * @return list of set results; never null
     */
    List<SetResult> findByMatchId(UUID matchId);

    /**
     * Finds a set result by composite PK {@code (match_id, set_index)}.
     *
     * @param matchId the match FK
     * @param setIndex the 0-based set index
     * @return the set result, or empty
     */
    Optional<SetResult> findByMatchIdAndSetIndex(UUID matchId, int setIndex);

    /**
     * Deletes a set result by composite PK.
     *
     * @param matchId the match FK
     * @param setIndex the 0-based set index
     */
    void deleteByMatchIdAndSetIndex(UUID matchId, int setIndex);
}
