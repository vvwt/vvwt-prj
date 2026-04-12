package de.vvwt.tm.domain;

import de.vvwt.tm.domain.generator.MatchGeneratorRegistry;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.domain.rules.ScoringRuleRegistry;
import de.vvwt.tm.domain.rules.SetValidationRuleRegistry;
import de.vvwt.tm.infrastructure.web.ConflictException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Domain service for Tournament CRUD operations (E05S04).
 *
 * <h2>Business rules enforced</h2>
 * <ul>
 *   <li>AC4: Only DRAFT tournaments may be updated</li>
 *   <li>AC5: Only DRAFT tournaments with no phases may be deleted</li>
 *   <li>AC6: DEC-5 single-active constraint is enforced at the schema level via the
 *       {@code active_sentinel} generated column (V2 migration). The constraint is triggered
 *       when a tournament transitions to ACTIVE status (E05S06/E05S07), not at creation time.
 *       New tournaments are always created in DRAFT status. Multiple DRAFTs may coexist.</li>
 * </ul>
 *
 * <h2>Tenant scoping</h2>
 * <p>All repository calls are tenant-scoped via the {@link de.vvwt.tm.domain.repo.TenantContext}
 * ThreadLocal, resolved by {@link de.vvwt.tm.domain.repo.DefaultTenantContextResolver} for
 * each HTTP request (DEC-5, DEC-17).
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E05S04.story.md">Story E05S04</a>
 */
@Service
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final ScoringRuleRegistry scoringRuleRegistry;
    private final SetValidationRuleRegistry setValidationRuleRegistry;
    private final MatchGeneratorRegistry matchGeneratorRegistry;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param tournamentRepository      tournament persistence (tenant-scoped)
     * @param phaseRepository           phase persistence (tenant-scoped) — for delete check (AC5)
     * @param scoringRuleRegistry       validates scoringRuleId bean references (AC3)
     * @param setValidationRuleRegistry validates setValidationRuleId bean references (AC3)
     * @param matchGeneratorRegistry    validates matchGeneratorId bean references (AC3)
     */
    public TournamentService(TournamentRepository tournamentRepository,
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
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Tournament> listTournaments() {
        return tournamentRepository.findAll().stream()
                .sorted(Comparator.comparing(
                        t -> t.getCreatedAt() == null ? LocalDateTime.MIN : t.getCreatedAt(),
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
     * @throws IllegalStateException  if no tenant context is active
     */
    public Tournament getTournament(UUID id) {
        return tournamentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Tournament not found: " + id));
    }

    // -------------------------------------------------------------------------
    // AC3 — Create tournament
    // -------------------------------------------------------------------------

    /**
     * Creates a new tournament in DRAFT status for the current tenant.
     *
     * <p>The new tournament is assigned a freshly generated UUID. All strategy bean IDs
     * are validated against the live Spring context before persisting.
     *
     * @param description         human-readable label (required, not blank)
     * @param appointment         optional tournament date
     * @param teamCount           number of teams (≥ 2)
     * @param fieldCount          number of courts (≥ 1)
     * @param matchFormat         match format enum name (must be a valid {@link MatchFormat} value)
     * @param scoringRuleId       Spring bean ID of the scoring rule (must be registered)
     * @param setValidationRuleId Spring bean ID of the set validation rule (must be registered)
     * @param matchGeneratorId    Spring bean ID of the match generator (must be registered)
     * @return the persisted tournament (never {@code null})
     * @throws IllegalArgumentException if any bean ID is not registered or matchFormat is invalid
     * @throws IllegalStateException    if no tenant context is active
     */
    public Tournament createTournament(String description,
                                       LocalDateTime appointment,
                                       int teamCount,
                                       int fieldCount,
                                       String matchFormat,
                                       String scoringRuleId,
                                       String setValidationRuleId,
                                       String matchGeneratorId) {
        // Validate bean IDs against the live Spring context
        validateBeanIds(matchFormat, scoringRuleId, setValidationRuleId, matchGeneratorId);

        Tournament tournament = new Tournament(
                UUID.randomUUID(),
                null,   // tenantId set by TenantScopedRepository.save()
                description,
                matchFormat,
                scoringRuleId,
                setValidationRuleId,
                matchGeneratorId,
                "DRAFT",
                LocalDateTime.now(),
                appointment,
                fieldCount,
                teamCount);

        return tournamentRepository.save(tournament);
    }

    // -------------------------------------------------------------------------
    // AC4 — Update tournament
    // -------------------------------------------------------------------------

    /**
     * Updates an existing tournament. Only DRAFT tournaments may be edited.
     *
     * <p>All provided fields replace the current values. A {@code null} value means
     * "do not change this field"; only non-null values are applied.
     *
     * @param id                  the tournament UUID
     * @param description         new description (applied if not {@code null})
     * @param appointment         new appointment (applied if non-null sentinel absent — use
     *                            a dedicated clear mechanism if needed in future)
     * @param teamCount           new team count (applied if &gt; 0)
     * @param fieldCount          new field count (applied if &gt; 0)
     * @param matchFormat         new match format (applied if not {@code null})
     * @param scoringRuleId       new scoring rule ID (applied if not {@code null})
     * @param setValidationRuleId new set validation rule ID (applied if not {@code null})
     * @param matchGeneratorId    new match generator ID (applied if not {@code null})
     * @return the updated tournament (never {@code null})
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws ConflictException      if the tournament is not in DRAFT status (AC4)
     * @throws IllegalArgumentException if any bean ID is not registered or matchFormat is invalid
     * @throws IllegalStateException  if no tenant context is active
     */
    public Tournament updateTournament(UUID id,
                                       String description,
                                       LocalDateTime appointment,
                                       int teamCount,
                                       int fieldCount,
                                       String matchFormat,
                                       String scoringRuleId,
                                       String setValidationRuleId,
                                       String matchGeneratorId) {
        Tournament tournament = getTournament(id);

        if (!"DRAFT".equals(tournament.getStatus())) {
            throw new ConflictException(
                    "Tournament '" + id + "' cannot be edited because its status is "
                    + tournament.getStatus() + ". Only DRAFT tournaments may be updated.");
        }

        // Apply non-null / valid fields
        if (description != null) {
            tournament.setDescription(description);
        }
        // appointment: always apply (null means clear; explicit set to null is valid)
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

        // Validate bean IDs (all current values — may be original or updated)
        validateBeanIds(tournament.getMatchFormat(), tournament.getScoringRuleId(),
                tournament.getSetValidationRuleId(), tournament.getMatchGeneratorId());

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
     * @throws ConflictException      if the tournament is ACTIVE or has associated phases (AC5)
     * @throws IllegalStateException  if no tenant context is active
     */
    public void deleteTournament(UUID id) {
        Tournament tournament = getTournament(id);

        if ("ACTIVE".equals(tournament.getStatus())) {
            throw new ConflictException(
                    "Tournament '" + id + "' cannot be deleted because it is ACTIVE. "
                    + "Only DRAFT tournaments with no phases may be deleted.");
        }

        // Check for associated phases (AC5: no phases must exist)
        List<de.vvwt.tm.domain.Phase> phases = phaseRepository.findByTournamentId(id);
        if (!phases.isEmpty()) {
            throw new ConflictException(
                    "Tournament '" + id + "' cannot be deleted because it has "
                    + phases.size() + " associated phase(s). Remove all phases first.");
        }

        tournamentRepository.deleteById(id);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that all strategy bean IDs refer to registered beans and that the match
     * format is a valid {@link MatchFormat} value. Throws {@link IllegalArgumentException}
     * on the first invalid value found.
     *
     * @param matchFormat         match format enum name
     * @param scoringRuleId       Spring bean ID for the scoring rule
     * @param setValidationRuleId Spring bean ID for the set validation rule
     * @param matchGeneratorId    Spring bean ID for the match generator
     */
    private void validateBeanIds(String matchFormat, String scoringRuleId,
                                  String setValidationRuleId, String matchGeneratorId) {
        // Validate match format (throws IllegalArgumentException on unknown value)
        MatchFormat.fromPersistedName(matchFormat);

        // Validate bean IDs against registries (each throws IllegalArgumentException if unknown)
        scoringRuleRegistry.get(scoringRuleId);
        setValidationRuleRegistry.get(setValidationRuleId);
        matchGeneratorRegistry.get(matchGeneratorId);
    }
}
