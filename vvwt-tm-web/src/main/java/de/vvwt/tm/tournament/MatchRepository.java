package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public port for tenant-scoped {@link Match} persistence (DEC-35, E31S01).
 *
 * <p>Hand-authored interface port per DEC-35 §2. The canonical implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultMatchRepository}.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultMatchRepository
 * @see <a href="DEC-35">DEC-35 — package layout: interfaces in public package</a>
 * @see <a href="E31S01">E31S01 — interface extraction</a>
 */
public interface MatchRepository {

    /**
     * Persists a match (upsert). Tenant scoping is enforced.
     *
     * @param match the match to save (id must be set by caller)
     * @return the saved match
     * @throws IllegalStateException if no tenant context is active
     */
    Match save(Match match);

    /**
     * Returns the match with the given id, scoped to the current tenant.
     *
     * @param id the match UUID
     * @return Optional.of(match) if found, Optional.empty() otherwise
     * @throws IllegalStateException if no tenant context is active
     */
    Optional<Match> findById(UUID id);

    /**
     * Returns all matches for the current tenant.
     *
     * @return list of all matches for the active tenant; never null
     * @throws IllegalStateException if no tenant context is active
     */
    List<Match> findAll();

    /**
     * Returns all matches in the given phase, scoped to the current tenant.
     *
     * @param phaseId the phase to query
     * @return list of matches; never null
     * @throws IllegalStateException if no tenant context is active
     */
    List<Match> findByPhaseId(UUID phaseId);

    /**
     * Returns all matches on the given field in the given lap, scoped to the current tenant.
     *
     * @param fieldNumber the court field number
     * @param lapNumber the lap (round) number
     * @return list of matches; never null
     * @throws IllegalStateException if no tenant context is active
     */
    List<Match> findByFieldNumberAndLapNumber(int fieldNumber, int lapNumber);

    /**
     * Returns all terminal matches for the given avatar in the given phase, scoped to the current
     * tenant.
     *
     * @param phaseId the phase to query
     * @param avatarId the team avatar to find matches for
     * @return list of terminal matches; never null
     * @throws IllegalStateException if no tenant context is active
     */
    List<Match> findTerminalByPhaseIdAndAvatarId(UUID phaseId, UUID avatarId);

    /**
     * Deletes all matches for the given phase, scoped to the current tenant.
     *
     * @param phaseId the phase whose matches to delete
     * @throws IllegalStateException if no tenant context is active
     */
    void deleteByPhaseId(UUID phaseId);

    /**
     * Deletes the match with the given id, scoped to the current tenant.
     *
     * @param id the match UUID
     * @throws IllegalStateException if no tenant context is active
     */
    void deleteById(UUID id);

    /**
     * Bulk-cancels all unfinished matches for the given tournament by updating {@code state} to
     * {@code CANCELED(-10)} where {@code state IN (OPEN=0, ENABLED=10, INPROGRESS=30, ONCHECK=35)}.
     *
     * <p>Terminal matches ({@code FINISHED_*} and already-{@code CANCELED}) are NOT touched — their
     * audit records are preserved (AC-TEST-FINISHED-MATCH-AUDIT-PRESERVED-RED, E48S04).
     *
     * <p>This operation is pure DML — no DDL, no schema change (AC-GOVERNANCE-NO-SCHEMA-CHANGE).
     *
     * @param tournamentId the tournament whose unfinished matches to cancel
     * @return the number of match rows updated (0 if all were already in terminal states)
     * @throws IllegalStateException if no tenant context is active
     * @see MatchState#CANCELED
     * @see <a href="E48S04">E48S04 — Match-Cancel-Lockdown</a>
     */
    int bulkCancelByTournamentId(UUID tournamentId);
}
