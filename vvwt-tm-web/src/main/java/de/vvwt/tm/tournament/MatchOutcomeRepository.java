package de.vvwt.tm.tournament;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link MatchOutcome} entities.
 *
 * <p>Boundary-API per inventory line 293 — consumed by {@code CascadeRecomputeService} in the
 * {@code scoring} context.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see MatchOutcome
 * @since E57S01
 */
public interface MatchOutcomeRepository {

    /**
     * Persists a match outcome. Inserts if new, updates otherwise.
     *
     * @param matchOutcome the outcome to save
     * @return the saved match outcome
     */
    MatchOutcome save(MatchOutcome matchOutcome);

    /**
     * Returns the match outcome for the given match id.
     *
     * @param matchId the match UUID
     * @return Optional.of(outcome) if found, Optional.empty() if not found
     */
    Optional<MatchOutcome> findById(UUID matchId);

    /**
     * Deletes the match outcome for the given match id.
     *
     * @param matchId the match UUID
     */
    void deleteByMatchId(UUID matchId);
}
