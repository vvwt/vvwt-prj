package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public port for tenant-scoped {@link Team} persistence (DEC-35, E31S01).
 *
 * <p>Hand-authored interface port per DEC-35 §2. The canonical implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultTeamRepository}.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultTeamRepository
 * @see <a href="DEC-35">DEC-35 — package layout: interfaces in public package</a>
 * @see <a href="E31S01">E31S01 — interface extraction</a>
 */
public interface TeamRepository {

    /**
     * Persists a team (upsert). Tenant scoping is enforced.
     *
     * @param team the team to save (id must be set by caller)
     * @return the saved team
     */
    Team save(Team team);

    /**
     * Returns the team with the given id, scoped to the current tenant.
     *
     * @param id the team UUID
     * @return Optional.of(team) if found, Optional.empty() otherwise
     */
    Optional<Team> findById(UUID id);

    /**
     * Returns all teams for the given tournament, scoped to the current tenant, ordered by
     * team_number ascending.
     *
     * @param tournamentId the tournament to query
     * @return list of teams; never null
     */
    List<Team> findByTournamentId(UUID tournamentId);

    /**
     * Deletes the team with the given id, scoped to the current tenant.
     *
     * @param id the team UUID
     */
    void deleteById(UUID id);

    /**
     * Returns the next available team number for the given tournament (max + 1, or 1 if empty).
     *
     * @param tournamentId the tournament to query
     * @return the next team number (≥ 1)
     */
    int nextTeamNumber(UUID tournamentId);

    /**
     * Returns whether a team with the given team_number already exists in the tournament, excluding
     * a specific team (for update uniqueness checks).
     *
     * @param tournamentId the tournament scope
     * @param teamNumber the team number to check
     * @param excludeId UUID of the team to exclude
     * @return true if another team in the tournament already has this number
     */
    boolean teamNumberExists(UUID tournamentId, int teamNumber, UUID excludeId);

    /**
     * Returns whether the given team has any {@code TeamAvatar} references (for delete guard).
     *
     * @param teamId the team UUID
     * @return true if at least one TeamAvatar references this team
     */
    boolean hasTeamAvatars(UUID teamId);
}
