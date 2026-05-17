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
     * Returns all matches in the given phase, on the given field, in the given lap, scoped to the
     * current tenant.
     *
     * <p>Phases scope the query to prevent surfacing matches from non-active phases that share the
     * same ({@code fieldNumber}, {@code lapNumber}) coordinate. Lap and field numbers restart per
     * phase (DEC-56/DEC-60 — L2 emits 1-based lap and field numbers per phase), so a ({@code
     * fieldNumber}, {@code lapNumber}) pair exists once in every phase of the tournament. This
     * phase-scoped variant is the correct query for resolving the active match on a field for a
     * given active phase (E22S13, AC2 — phase-scoped match resolution).
     *
     * @param phaseId the active phase to restrict the query to (NOT NULL)
     * @param fieldNumber the court field number (1-based per DEC-60 D-1)
     * @param lapNumber the lap number (1-based per DEC-60 D-1)
     * @return list of matches in the given phase at the given field and lap; never null
     * @throws IllegalStateException if no tenant context is active
     * @see <a href="E22S13">E22S13 — phase-scoped match resolution fix</a>
     */
    List<Match> findByPhaseIdAndFieldNumberAndLapNumber(
            UUID phaseId, int fieldNumber, int lapNumber);

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

    /**
     * Counts unfinished matches in the given phase.
     *
     * <p>Unfinished = {@code state IN (OPEN=0, ENABLED=10, INPROGRESS=30, ONCHECK=35)}. Used by
     * {@link de.vvwt.tm.tournament.PhaseLifecycleService#complete(UUID)} to verify that all matches
     * are terminal before allowing the transition to {@code COMPLETED} (E48S06
     * AC-TEST-PHASE-COMPLETE-ALL-FINISHED-RED).
     *
     * @param phaseId the phase to query
     * @return count of matches with non-terminal state; 0 if all are finished or phase has no
     *     matches
     * @throws IllegalStateException if no tenant context is active
     * @see <a href="E48S06">E48S06 — AC-TEST-PHASE-COMPLETE-ALL-FINISHED-RED</a>
     */
    long countUnfinishedByPhaseId(UUID phaseId);
}
