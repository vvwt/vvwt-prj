package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.TournamentService;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *       TournamentRuleResolver). Auto-seeds N=teamCount team rows within the same transaction
 *       (E05S12 AC-IMPL-AUTO-SEED-AT-CREATE, AC-ERR-ATOMIC-ROLLBACK).
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
 * @see <a href="DEC-52">DEC-52 — i18n column baseline (team.language entity-gap flagged C-13)</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
 * @see <a href="E33S01">E33S01 — Extract TournamentService interface (DEC-35 pioneer)</a>
 * @see <a href="E05S12">E05S12 — Auto-seed N empty team slots at tournament creation</a>
 */
@Service("tmTournamentService")
public class DefaultTournamentService implements TournamentService {

    /**
     * MessageSource key for the seeded team placeholder label (E05S12
     * AC-I18N-LABEL-FROM-MESSAGE-BUNDLE).
     *
     * <p>Resolved from {@code messages.properties} (DE default). Future-binding C-13: when EN is
     * activated, a dedicated multi-language story adds {@code messages_en.properties} with an EN
     * value and extends the locale chain to include {@code tournament.language}.
     */
    private static final String TEAM_DEFAULT_LABEL_KEY = "team.defaultLabel";

    /**
     * Hard-coded fallback label used if {@code team.defaultLabel} is not present in the bundle.
     * Should never be reached in practice given the key is shipped in {@code messages.properties}.
     */
    private static final String TEAM_DEFAULT_LABEL_FALLBACK = "Mannschaft";

    /**
     * Default language tag applied when {@code tenant.language} is null or blank (E05S12
     * AC-I18N-LOCALE-CHAIN § today-state).
     */
    private static final String DEFAULT_LANGUAGE = "de";

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchGeneratorRegistry matchGeneratorRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final MessageSource messageSource;
    private final TeamRepository teamRepository;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param tournamentRepository tournament persistence (tenant-scoped, new Modulith repository)
     * @param phaseRepository phase persistence (legacy, tenant-scoped) — for delete check (AC5)
     * @param matchGeneratorRegistry validates matchGeneratorId bean references (AC3)
     * @param jdbcTemplate JDBC template for auxiliary queries (location lookup, tenant language)
     * @param messageSource Spring MessageSource for i18n label resolution (E05S12
     *     AC-I18N-LABEL-FROM-MESSAGE-BUNDLE)
     * @param teamRepository team persistence (tenant-scoped) — for seed loop (E05S12
     *     AC-IMPL-AUTO-SEED-AT-CREATE)
     */
    public DefaultTournamentService(
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchGeneratorRegistry matchGeneratorRegistry,
            JdbcTemplate jdbcTemplate,
            MessageSource messageSource,
            TeamRepository teamRepository) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchGeneratorRegistry = matchGeneratorRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.messageSource = messageSource;
        this.teamRepository = teamRepository;
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
    // AC3 — Create tournament (E05S12: with auto-seed, @Transactional boundary)
    // -------------------------------------------------------------------------

    /**
     * Creates a new tournament in DRAFT status and auto-seeds exactly {@code teamCount} empty team
     * rows within the same transactional boundary (E05S12).
     *
     * <p>scoringRuleId and setValidationRuleId are persisted verbatim without registry lookup
     * (E22S02, DEC-40 Approach A boundary fix). Validation of these IDs occurs at first score
     * submission via {@code TournamentRuleResolver}.
     *
     * <p><b>Auto-seed contract (E05S12):</b>
     *
     * <ul>
     *   <li>Exactly {@code N = teamCount} {@link Team} rows are persisted in the same transaction.
     *   <li>Each row carries {@code teamNumber} ∈ {1, 2, …, N} — placeholder values the (future)
     *       lottery may permute.
     *   <li>Each row's {@code description = String.format("%s %02d", label, n)} where {@code label}
     *       is resolved via Spring {@link MessageSource} key {@code team.defaultLabel} against the
     *       effective locale ({@code tenant.language} ?? {@code "de"} — today's state per
     *       AC-I18N-LOCALE-CHAIN).
     *   <li>Default flags: {@code participate=true}, {@code refereeAssignment=false}, {@code
     *       withoutAssessment=false}.
     *   <li>If any team insertion fails (e.g., {@link
     *       org.springframework.dao.DataAccessException}), the {@code @Transactional} boundary
     *       rolls back both the Tournament row and all previously-inserted Team rows
     *       (AC-ERR-ATOMIC-ROLLBACK).
     * </ul>
     *
     * <p><b>Future-binding (C-13):</b> The full locale chain {@code team.language ??
     * tournament.language ?? tenant.language ?? "de"} is deferred to the EN-activation Story. Today
     * only DE ships; the chain simplifies to {@code tenant.language ?? "de"}.
     *
     * @param description human-readable label (required, not blank)
     * @param appointment optional tournament date
     * @param teamCount number of teams (≥ 2); same value as the number of seeded Team rows
     * @param fieldCount number of courts (≥ 1)
     * @param matchFormat match format enum name
     * @param scoringRuleId Spring bean ID of the scoring rule (persisted verbatim)
     * @param setValidationRuleId Spring bean ID of the set validation rule (persisted verbatim)
     * @param matchGeneratorId Spring bean ID of the match generator
     * @param plannedStartTime optional planned start time for timeline calculation; {@code null}
     *     means no start time — mirrors UPDATE method at line ~341 (E08S05 AC4; E48S14 bug-fix)
     * @return the persisted tournament (never {@code null}); team rows are persisted as a side
     *     effect within the same transaction — they are NOT embedded in the returned object
     * @throws IllegalArgumentException if matchFormat is invalid or matchGeneratorId is not
     *     registered
     * @see <a href="E05S12">E05S12 — AC-IMPL-AUTO-SEED-AT-CREATE, AC-ERR-ATOMIC-ROLLBACK,
     *     AC-I18N-LOCALE-CHAIN</a>
     * @see <a href="E48S14">E48S14 — Bug-fix: plannedStartTime was not wired in CREATE path</a>
     */
    @Transactional
    @Override
    public Tournament createTournament(
            String description,
            LocalDateTime appointment,
            int teamCount,
            int fieldCount,
            String matchFormat,
            String scoringRuleId,
            String setValidationRuleId,
            String matchGeneratorId,
            LocalTime plannedStartTime) {
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
        // E48S14 bug-fix: wire plannedStartTime into the CREATE path (was previously omitted,
        // causing the field to be silently dropped on tournament creation).
        // Mirrors the UPDATE method pattern at DefaultTournamentService.java ~341 (E08S05 AC4).
        tournament.setPlannedStartTime(plannedStartTime);
        // DEC-39 D2: location_id NOT NULL — resolved from the first location row for this tenant.
        // In the Wave-1 single-location model, only one location exists per tenant DB.
        tournament.setLocationId(resolveDefaultLocationId());

        Tournament saved = tournamentRepository.save(tournament);

        // E05S12: auto-seed N=teamCount empty Team rows in the same transaction.
        // AC-I18N-LOCALE-CHAIN (today-state): locale = tenant.language ?? "de".
        // tournament.language is NOT consulted here (OUT per D-9 / C-13 future-binding).
        String label = resolveTeamLabel();
        for (int n = 1; n <= teamCount; n++) {
            Team team = new Team();
            team.setId(UUID.randomUUID());
            team.setTournamentId(saved.getId());
            team.setTeamNumber(n);
            team.setDescription(String.format("%s %02d", label, n));
            team.setParticipate(true);
            team.setRefereeAssignment(false);
            team.setWithoutAssessment(false);
            // created_at: left null — DB DEFAULT CURRENT_TIMESTAMP applies on INSERT.
            teamRepository.save(team);
        }

        return saved;
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
     * <p><b>E05S12 AC-IMPL-PUT-NO-RECONCILE:</b> Changing {@code teamCount} via this method updates
     * ONLY the {@code tournament} row's column. It does NOT add, remove, or modify any existing
     * Team rows.
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

        // E05S12: remove auto-seeded team rows before deleting the tournament row.
        // team.tournament_id is ON DELETE RESTRICT, so teams must be deleted first.
        teamRepository.deleteByTournamentId(id);
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

    /**
     * Resolves the default location ID for the current tenant (DEC-39 D2).
     *
     * <p>In the Wave-1 single-location model, each tenant database contains exactly one location
     * row (inserted by {@code DefaultTenantBootstrapRunner}). This method returns its UUID.
     *
     * @return the first location UUID found in the current tenant database
     * @throws java.util.NoSuchElementException if no location row exists
     */
    private UUID resolveDefaultLocationId() {
        List<UUID> ids =
                jdbcTemplate.query(
                        "SELECT id FROM locations LIMIT 1",
                        (rs, rowNum) -> UUID.fromString(rs.getString("id")));
        if (ids.isEmpty()) {
            throw new NoSuchElementException(
                    "No location found in the current tenant database. Ensure the default location"
                            + " was created by DefaultTenantBootstrapRunner.");
        }
        return ids.get(0);
    }

    /**
     * Resolves the i18n label for seeded team placeholders (E05S12 AC-I18N-LOCALE-CHAIN).
     *
     * <p><b>Today's locale chain (AC-I18N-LOCALE-CHAIN § today-state):</b>
     *
     * <ol>
     *   <li>Read {@code language} from the {@code tenants} table via intra-DB JDBC (same pattern as
     *       {@code certificate.DefaultLocaleResolver}).
     *   <li>If null or blank → fall back to {@code "de"}.
     *   <li>Build {@link Locale} from the resolved tag.
     *   <li>Resolve {@code team.defaultLabel} via {@link MessageSource}.
     * </ol>
     *
     * <p><b>Future-binding (C-13):</b> When EN is activated, the companion multi-language Story
     * extends this chain to {@code tournament.language ?? tenant.language ?? "de"} and ships {@code
     * messages_en.properties}.
     *
     * @return the resolved label string (e.g., {@code "Mannschaft"} for DE)
     */
    private String resolveTeamLabel() {
        List<String> tenantLanguageResult =
                jdbcTemplate.queryForList("SELECT language FROM tenants", String.class);
        String tenantLanguage =
                (tenantLanguageResult.isEmpty()
                                || tenantLanguageResult.get(0) == null
                                || tenantLanguageResult.get(0).isBlank())
                        ? DEFAULT_LANGUAGE
                        : tenantLanguageResult.get(0);
        Locale locale = Locale.forLanguageTag(tenantLanguage);
        return messageSource.getMessage(
                TEAM_DEFAULT_LABEL_KEY, null, TEAM_DEFAULT_LABEL_FALLBACK, locale);
    }
}
