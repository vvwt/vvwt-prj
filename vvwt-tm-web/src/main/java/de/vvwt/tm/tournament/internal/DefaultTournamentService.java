package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.TournamentService;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link TournamentService} — Tournament CRUD operations (DEC-35
 * retrofit, E33S01).
 *
 * <p>Mirrors the logic of {@code de.vvwt.tm.domain.TournamentService} but wired to the new {@link
 * TournamentRepository} and new {@link Tournament} entity at the Modulith target package ({@code
 * de.vvwt.tm.tournament.*}).
 *
 * <p>Registered as {@code @Service("tmTournamentService")} — the qualifier is consumed by {@link
 * de.vvwt.tm.tournament.TournamentController} and MUST NOT change (C-12, AC-QUALIFIER-PRESERVED).
 *
 * <h2>Business rules enforced</h2>
 *
 * <ul>
 *   <li>AC1: listTournaments returns all tournaments for the current tenant, sorted createdAt desc
 *   <li>AC2: getTournament returns entity or throws {@link NoSuchElementException}
 *   <li>AC3: createTournament assigns DRAFT status; validates matchFormat and matchGeneratorId bean
 *       ID (E22S02: registry validation for scoringRuleId and setValidationRuleId removed — DEC-40
 *       Approach A boundary fix; validation shifts to first score submission via
 *       TournamentRuleResolver)
 *   <li>AC4: updateTournament rejects non-DRAFT with {@link ConflictException}
 *   <li>AC5: deleteTournament rejects ACTIVE or tournaments with phases
 * </ul>
 *
 * <p>Lives in {@code tournament.internal} per DEC-35 §Internal-package (implementation surface;
 * public interface is {@link TournamentService} in the module root).
 *
 * @see TournamentService
 * @see TournamentRepository
 * @see Tournament
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout: impl in .internal</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation; Approach A boundary fix (E22S02)</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
 * @see <a href="E33S01">E33S01 — Extract TournamentService interface (DEC-35 pioneer)</a>
 */
@Service("tmTournamentService")
public class DefaultTournamentService implements TournamentService {

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchGeneratorRegistry matchGeneratorRegistry;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param tournamentRepository tournament persistence (tenant-scoped, new Modulith repository)
     * @param phaseRepository phase persistence (legacy, tenant-scoped) — for delete check (AC5)
     * @param matchGeneratorRegistry validates matchGeneratorId bean references (AC3)
     */
    public DefaultTournamentService(
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchGeneratorRegistry matchGeneratorRegistry) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
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
    @Override
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
    @Override
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
     * <p>scoringRuleId and setValidationRuleId are persisted verbatim without registry lookup
     * (E22S02, DEC-40 Approach A boundary fix). Validation of these IDs occurs at first score
     * submission via {@code TournamentRuleResolver}.
     *
     * @param description human-readable label (required, not blank)
     * @param appointment optional tournament date
     * @param teamCount number of teams (≥ 2)
     * @param fieldCount number of courts (≥ 1)
     * @param matchFormat match format enum name
     * @param scoringRuleId Spring bean ID of the scoring rule (persisted verbatim)
     * @param setValidationRuleId Spring bean ID of the set validation rule (persisted verbatim)
     * @param matchGeneratorId Spring bean ID of the match generator
     * @return the persisted tournament (never {@code null})
     * @throws IllegalArgumentException if matchFormat is invalid or matchGeneratorId is not
     *     registered
     */
    @Override
    public Tournament createTournament(
            String description,
            LocalDateTime appointment,
            int teamCount,
            int fieldCount,
            String matchFormat,
            String scoringRuleId,
            String setValidationRuleId,
            String matchGeneratorId) {
        validateBeanIds(matchFormat, matchGeneratorId);

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
     * <p>scoringRuleId and setValidationRuleId are persisted verbatim without registry lookup
     * (E22S02, DEC-40 Approach A boundary fix). Validation of these IDs occurs at first score
     * submission via {@code TournamentRuleResolver}.
     *
     * @param id the tournament UUID
     * @param description new description (applied if not {@code null})
     * @param appointment new appointment (always applied; {@code null} means clear)
     * @param teamCount new team count (applied if &gt; 0)
     * @param fieldCount new field count (applied if &gt; 0)
     * @param matchFormat new match format (applied if not {@code null})
     * @param scoringRuleId new scoring rule ID (applied if not {@code null}; persisted verbatim)
     * @param setValidationRuleId new set validation rule ID (applied if not {@code null}; persisted
     *     verbatim)
     * @param matchGeneratorId new match generator ID (applied if not {@code null})
     * @param plannedStartTime new planned start time (always applied; {@code null} means clear)
     * @return the updated tournament (never {@code null})
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws ConflictException if the tournament is not in DRAFT status (AC4)
     * @throws IllegalArgumentException if matchFormat is invalid or matchGeneratorId is not
     *     registered
     */
    @Override
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

        validateBeanIds(tournament.getMatchFormat(), tournament.getMatchGeneratorId());

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
    @Override
    public void deleteTournament(UUID id) {
        Tournament tournament = getTournament(id);

        if ("ACTIVE".equals(tournament.getStatus())) {
            throw new ConflictException(
                    "Tournament '"
                            + id
                            + "' cannot be deleted because it is ACTIVE. "
                            + "Only DRAFT tournaments with no phases may be deleted.");
        }

        List<Phase> phases = phaseRepository.findByTournamentId(id);
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

    /**
     * Validates the match format enum and the match generator bean ID.
     *
     * <p>scoringRuleId and setValidationRuleId are intentionally NOT validated here (E22S02, DEC-40
     * Approach A boundary fix) — their validity is checked downstream at first score submission by
     * {@code TournamentRuleResolver}.
     *
     * @param matchFormat the match format persisted name
     * @param matchGeneratorId the Spring bean ID of the match generator
     * @throws IllegalArgumentException if matchFormat is invalid or matchGeneratorId is unknown
     */
    private void validateBeanIds(String matchFormat, String matchGeneratorId) {
        MatchFormat.fromPersistedName(matchFormat);
        matchGeneratorRegistry.get(matchGeneratorId);
    }
}
