package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.generator.MatchGeneratorRegistry;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.rules.ScoringRuleRegistry;
import de.vvwt.tm.domain.rules.SetValidationRuleRegistry;
import de.vvwt.tm.infrastructure.web.ConflictException;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Domain service for Tournament CRUD operations — reconstruction-in-place target (DEC-21/DEC-22).
 *
 * <p>Mirrors the logic of {@code de.vvwt.tm.domain.TournamentService} but wired to the new {@link
 * TournamentRepository} and new {@link Tournament} entity at the Modulith target package ({@code
 * de.vvwt.tm.tournament.*}).
 *
 * <h2>Business rules enforced</h2>
 *
 * <ul>
 *   <li>AC1: listTournaments returns all tournaments for the current tenant, sorted createdAt desc
 *   <li>AC2: getTournament returns entity or throws {@link NoSuchElementException}
 *   <li>AC3: createTournament assigns DRAFT status; validates bean IDs
 *   <li>AC4: updateTournament rejects non-DRAFT with {@link ConflictException}
 *   <li>AC5: deleteTournament rejects ACTIVE or tournaments with phases
 * </ul>
 *
 * <p>Lives in {@code tournament.internal} per DEC-21 §Module layout (implementation surface, not
 * public API).
 *
 * @see TournamentRepository
 * @see Tournament
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
 */
@Service("tmTournamentService")
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final ScoringRuleRegistry scoringRuleRegistry;
    private final SetValidationRuleRegistry setValidationRuleRegistry;
    private final MatchGeneratorRegistry matchGeneratorRegistry;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param tournamentRepository tournament persistence (tenant-scoped, new Modulith repository)
     * @param phaseRepository phase persistence (legacy, tenant-scoped) — for delete check (AC5)
     * @param scoringRuleRegistry validates scoringRuleId bean references (AC3)
     * @param setValidationRuleRegistry validates setValidationRuleId bean references (AC3)
     * @param matchGeneratorRegistry validates matchGeneratorId bean references (AC3)
     */
    public TournamentService(
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            ScoringRuleRegistry scoringRuleRegistry,
            SetValidationRuleRegistry setValidationRuleRegistry,
            MatchGeneratorRegistry matchGeneratorRegistry) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.scoringRuleRegistry = scoringRuleRegistry;
        this.setValidationRuleRegistry = setValidationRuleRegistry;
        this.matchGeneratorRegistry = matchGeneratorRegistry;
    }

    // -------------------------------------------------------------------------
    // AC1 — List tournaments
    // -------------------------------------------------------------------------

    /**
     * Returns all tournaments for the current tenant, ordered by {@code createdAt} descending.
     *
     * @return immutable list, never {@code null}; may be empty if no tournaments exist
     */
    public List<Tournament> listTournaments() {
        return tournamentRepository.findAll().stream()
                .sorted(
                        Comparator.comparing(
                                t ->
                                        t.getCreatedAt() == null
                                                ? LocalDateTime.MIN
                                                : t.getCreatedAt(),
                                Comparator.reverseOrder()))
                .toList();
    }

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
    public Tournament getTournament(UUID id) {
        return tournamentRepository
                .findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tournament not found: " + id));
    }

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
     * @return the persisted tournament (never {@code null})
     * @throws IllegalArgumentException if any bean ID is not registered or matchFormat is invalid
     */
    public Tournament createTournament(
            String description,
            LocalDateTime appointment,
            int teamCount,
            int fieldCount,
            String matchFormat,
            String scoringRuleId,
            String setValidationRuleId,
            String matchGeneratorId) {
        validateBeanIds(matchFormat, scoringRuleId, setValidationRuleId, matchGeneratorId);

        Tournament tournament = new Tournament();
        tournament.setId(UUID.randomUUID());
        tournament.setDescription(description);
        tournament.setMatchFormat(matchFormat);
        tournament.setScoringRuleId(scoringRuleId);
        tournament.setSetValidationRuleId(setValidationRuleId);
        tournament.setMatchGeneratorId(matchGeneratorId);
        tournament.setStatus("DRAFT");
        tournament.setCreatedAt(LocalDateTime.now());
        tournament.setAppointment(appointment);
        tournament.setFieldCount(fieldCount);
        tournament.setTeamCount(teamCount);

        return tournamentRepository.save(tournament);
    }

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
    public Tournament updateTournament(
            UUID id,
            String description,
            LocalDateTime appointment,
            int teamCount,
            int fieldCount,
            String matchFormat,
            String scoringRuleId,
            String setValidationRuleId,
            String matchGeneratorId,
            LocalTime plannedStartTime) {
        Tournament tournament = getTournament(id);

        if (!"DRAFT".equals(tournament.getStatus())) {
            throw new ConflictException(
                    "Tournament '"
                            + id
                            + "' cannot be edited because its status is "
                            + tournament.getStatus()
                            + ". Only DRAFT tournaments may be updated.");
        }

        if (description != null) {
            tournament.setDescription(description);
        }
        tournament.setAppointment(appointment);
        if (teamCount > 0) {
            tournament.setTeamCount(teamCount);
        }
        if (fieldCount > 0) {
            tournament.setFieldCount(fieldCount);
        }
        if (matchFormat != null) {
            tournament.setMatchFormat(matchFormat);
        }
        if (scoringRuleId != null) {
            tournament.setScoringRuleId(scoringRuleId);
        }
        if (setValidationRuleId != null) {
            tournament.setSetValidationRuleId(setValidationRuleId);
        }
        if (matchGeneratorId != null) {
            tournament.setMatchGeneratorId(matchGeneratorId);
        }
        tournament.setPlannedStartTime(plannedStartTime);

        validateBeanIds(
                tournament.getMatchFormat(),
                tournament.getScoringRuleId(),
                tournament.getSetValidationRuleId(),
                tournament.getMatchGeneratorId());

        return tournamentRepository.save(tournament);
    }

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
    public void deleteTournament(UUID id) {
        Tournament tournament = getTournament(id);

        if ("ACTIVE".equals(tournament.getStatus())) {
            throw new ConflictException(
                    "Tournament '"
                            + id
                            + "' cannot be deleted because it is ACTIVE. "
                            + "Only DRAFT tournaments with no phases may be deleted.");
        }

        List<de.vvwt.tm.domain.Phase> phases = phaseRepository.findByTournamentId(id);
        if (!phases.isEmpty()) {
            throw new ConflictException(
                    "Tournament '"
                            + id
                            + "' cannot be deleted because it has "
                            + phases.size()
                            + " associated phase(s). Remove all phases first.");
        }

        tournamentRepository.deleteById(id);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void validateBeanIds(
            String matchFormat,
            String scoringRuleId,
            String setValidationRuleId,
            String matchGeneratorId) {
        MatchFormat.fromPersistedName(matchFormat);
        scoringRuleRegistry.get(scoringRuleId);
        setValidationRuleRegistry.get(setValidationRuleId);
        matchGeneratorRegistry.get(matchGeneratorId);
    }
}
