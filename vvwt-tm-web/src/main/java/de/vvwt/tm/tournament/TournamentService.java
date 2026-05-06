package de.vvwt.tm.tournament;

import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Public service interface for Tournament CRUD operations (DEC-35 retrofit pioneer, E33S01).
 *
 * <p>This interface is the public contract for the {@code de.vvwt.tm.tournament} Modulith module.
 * The canonical implementation is {@link de.vvwt.tm.tournament.internal.DefaultTournamentService},
 * registered as {@code @Service("tmTournamentService")}.
 *
 * <h2>Business rules enforced by the implementation</h2>
 *
 * <ul>
 *   <li>AC1: listTournaments returns all tournaments for the current tenant, sorted createdAt desc
 *   <li>AC2: getTournament returns entity or throws {@link NoSuchElementException}
 *   <li>AC3: createTournament assigns DRAFT status; validates bean IDs
 *   <li>AC4: updateTournament rejects non-DRAFT with {@link ConflictException}
 *   <li>AC5: deleteTournament rejects ACTIVE or tournaments with phases
 * </ul>
 *
 * <p>Introduced by E33S01 as the DEC-35 pioneer story for the {@code tournament} module. All
 * callers outside {@code tournament.internal} MUST reference this interface (DEC-36 cross-package
 * test typing rule).
 *
 * @see de.vvwt.tm.tournament.internal.DefaultTournamentService
 * @see TournamentRepository
 * @see Tournament
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout: interfaces in public package</a>
 * @see <a href="DEC-36">DEC-36 — Cross-package test typing rule</a>
 * @see <a href="E33S01">E33S01 — Extract TournamentService interface (pioneer story)</a>
 */
public interface TournamentService {

    // -------------------------------------------------------------------------
    // AC1 — List tournaments
    // -------------------------------------------------------------------------

    /**
     * Returns all tournaments for the current tenant, ordered by {@code createdAt} descending.
     *
     * @return immutable list, never {@code null}; may be empty if no tournaments exist
     */
    List<Tournament> listTournaments();

    // -------------------------------------------------------------------------
    // AC2 — Get single tournament
    // -------------------------------------------------------------------------

    /**
     * Returns the tournament with the given ID, scoped to the current tenant.
     *
     * @param id the tournament UUID
     * @return the tournament (never {@code null})
     * @throws NoSuchElementException if no tournament with this ID exists for the current tenant
     */
    Tournament getTournament(UUID id);

    // -------------------------------------------------------------------------
    // AC3 — Create tournament
    // -------------------------------------------------------------------------

    /**
     * Creates a new tournament in DRAFT status for the current tenant.
     *
     * @param description human-readable label (required, not blank)
     * @param appointment optional tournament date
     * @param teamCount number of teams (≥ 2)
     * @param fieldCount number of courts (≥ 1)
     * @param matchFormat match format enum name
     * @param scoringRuleId Spring bean ID of the scoring rule
     * @param setValidationRuleId Spring bean ID of the set validation rule
     * @param matchGeneratorId Spring bean ID of the match generator
     * @param plannedStartTime optional planned start time for timeline calculation; {@code null}
     *     means no start time set (E08S05 AC4; E48S14 bug-fix: was missing from CREATE path)
     * @return the persisted tournament (never {@code null})
     * @throws IllegalArgumentException if any bean ID is not registered or matchFormat is invalid
     */
    Tournament createTournament(
            String description,
            LocalDateTime appointment,
            int teamCount,
            int fieldCount,
            String matchFormat,
            String scoringRuleId,
            String setValidationRuleId,
            String matchGeneratorId,
            LocalTime plannedStartTime);

    // -------------------------------------------------------------------------
    // AC4 — Update tournament
    // -------------------------------------------------------------------------

    /**
     * Updates an existing tournament. Only DRAFT tournaments may be edited.
     *
     * @param id the tournament UUID
     * @param description new description (applied if not {@code null})
     * @param appointment new appointment (always applied; {@code null} means clear)
     * @param teamCount new team count (applied if &gt; 0)
     * @param fieldCount new field count (applied if &gt; 0)
     * @param matchFormat new match format (applied if not {@code null})
     * @param scoringRuleId new scoring rule ID (applied if not {@code null})
     * @param setValidationRuleId new set validation rule ID (applied if not {@code null})
     * @param matchGeneratorId new match generator ID (applied if not {@code null})
     * @param plannedStartTime new planned start time (always applied; {@code null} means clear)
     * @return the updated tournament (never {@code null})
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws ConflictException if the tournament is not in DRAFT status (AC4)
     * @throws IllegalArgumentException if any bean ID is not registered or matchFormat is invalid
     */
    Tournament updateTournament(
            UUID id,
            String description,
            LocalDateTime appointment,
            int teamCount,
            int fieldCount,
            String matchFormat,
            String scoringRuleId,
            String setValidationRuleId,
            String matchGeneratorId,
            LocalTime plannedStartTime);

    // -------------------------------------------------------------------------
    // AC5 — Delete tournament
    // -------------------------------------------------------------------------

    /**
     * Deletes a tournament. Only DRAFT tournaments with no associated phases may be deleted.
     *
     * @param id the tournament UUID
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws ConflictException if the tournament is ACTIVE or has associated phases (AC5)
     */
    void deleteTournament(UUID id);
}
