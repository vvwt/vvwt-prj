package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import de.vvwt.tm.tournament.exceptions.TournamentCascadeDeleteActiveException;
import de.vvwt.tm.tournament.exceptions.TournamentCascadeDeleteCompletedException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link DefaultTournamentService} (E21S02, AC-TDD-TournamentService; renamed E33S01
 * AC-UNIT-TEST-RENAME).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED (E21S02): the implementation class did not exist at commit time,
 * causing a compile error — satisfying the DEC-22 Iron Law. The class was originally named {@code
 * TournamentService} in the internal package; renamed to {@link DefaultTournamentService} by E33S01
 * (DEC-35 pioneer; white-box same-package access preserved per DEC-36).
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>AC1 — listTournaments returns tournaments ordered by createdAt descending
 *   <li>AC2 — getTournament returns entity or throws NoSuchElementException
 *   <li>AC3 — createTournament assigns DRAFT status and generates UUID
 *   <li>AC4 — updateTournament rejects non-DRAFT with ConflictException
 *   <li>AC5 — deleteTournament rejects ACTIVE and tournaments with phases
 * </ul>
 *
 * @see DefaultTournamentService
 * @see de.vvwt.tm.tournament.TournamentService
 * @see de.vvwt.tm.tournament.TournamentRepository
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout</a>
 * @see <a href="DEC-36">DEC-36 — Same-package white-box tests permitted</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
 * @see <a href="E33S01">E33S01 — Extract TournamentService interface (AC-UNIT-TEST-RENAME)</a>
 * @see <a href="E22S02">E22S02 — Remove DefaultTournamentService eager registry validation (DEC-40
 *     Approach A boundary fix) — ScoringRuleRegistry and SetValidationRuleRegistry mocks removed;
 *     scoringRuleId/setValidationRuleId validated at first score submission only</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultTournamentService unit tests — E21S02 AC-TDD-TournamentService")
class TournamentServiceTest {

    @Mock private TournamentRepository tournamentRepository;

    @Mock private MatchGeneratorRegistry matchGeneratorRegistry;

    @Mock private JdbcTemplate jdbcTemplate;

    @Mock private MessageSource messageSource;

    @Mock private TeamRepository teamRepository;

    private DefaultTournamentService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID DEFAULT_LOCATION_ID = UUID.randomUUID();
    private static final String VALID_FORMAT = "BEST_OF_3";
    private static final String VALID_SCORING = "setPoints";
    private static final String VALID_VALIDATION = "standardVolleyball";
    private static final String VALID_GENERATOR = "roundRobin";

    @BeforeEach
    void setUp() {
        // Stub JdbcTemplate.query for resolveDefaultLocationId() used in createTournament
        org.mockito.Mockito.lenient()
                .when(
                        jdbcTemplate.query(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of(DEFAULT_LOCATION_ID));
        // Stub JdbcTemplate.queryForList for resolveTenantLanguage() — returns "de"
        org.mockito.Mockito.lenient()
                .when(
                        jdbcTemplate.queryForList(
                                ArgumentMatchers.anyString(), ArgumentMatchers.eq(String.class)))
                .thenReturn(List.of("de"));
        // Stub MessageSource for team.defaultLabel → "Mannschaft"
        org.mockito.Mockito.lenient()
                .when(
                        messageSource.getMessage(
                                ArgumentMatchers.eq("team.defaultLabel"),
                                ArgumentMatchers.isNull(),
                                ArgumentMatchers.eq("Mannschaft"),
                                ArgumentMatchers.any(Locale.class)))
                .thenReturn("Mannschaft");
        // Stub teamRepository.save to return the team passed in
        org.mockito.Mockito.lenient()
                .when(teamRepository.save(ArgumentMatchers.any(Team.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        service =
                new DefaultTournamentService(
                        tournamentRepository,
                        matchGeneratorRegistry,
                        jdbcTemplate,
                        messageSource,
                        teamRepository);
    }

    // =========================================================================
    // AC1 — listTournaments
    // =========================================================================

    @Test
    @DisplayName("listTournaments() returns tournaments sorted by createdAt descending")
    void listTournaments_sortedByCreatedAtDescending() {
        Tournament older = buildDraftTournament("Older");
        older.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        Tournament newer = buildDraftTournament("Newer");
        newer.setCreatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));

        when(tournamentRepository.findAll()).thenReturn(List.of(older, newer));

        List<Tournament> result = service.listTournaments();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getDescription()).isEqualTo("Newer");
        assertThat(result.get(1).getDescription()).isEqualTo("Older");
    }

    @Test
    @DisplayName("listTournaments() returns empty list when no tournaments exist")
    void listTournaments_empty_returnsEmptyList() {
        when(tournamentRepository.findAll()).thenReturn(List.of());

        List<Tournament> result = service.listTournaments();

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // AC2 — getTournament
    // =========================================================================

    @Test
    @DisplayName("getTournament() returns tournament when found")
    void getTournament_found_returnsTournament() {
        UUID id = UUID.randomUUID();
        Tournament t = buildDraftTournament("Found");
        t.setId(id);
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(t));

        Tournament result = service.getTournament(id);

        assertThat(result.getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("getTournament() throws NoSuchElementException for unknown ID")
    void getTournament_notFound_throwsNoSuchElement() {
        UUID unknownId = UUID.randomUUID();
        when(tournamentRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTournament(unknownId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(unknownId.toString());
    }

    // =========================================================================
    // AC3 — createTournament
    // =========================================================================

    @Test
    @DisplayName("createTournament() persists a DRAFT tournament with generated UUID")
    void createTournament_persistsDraftWithGeneratedId() {
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tournament result =
                service.createTournament(
                        "New Tournament",
                        null,
                        4,
                        2,
                        VALID_FORMAT,
                        VALID_SCORING,
                        VALID_VALIDATION,
                        VALID_GENERATOR,
                        null);

        assertThat(result.getId()).as("UUID must be generated").isNotNull();
        assertThat(result.getStatus()).isEqualTo("DRAFT");
        assertThat(result.getDescription()).isEqualTo("New Tournament");
        verify(tournamentRepository).save(any());
    }

    @Test
    @DisplayName("createTournament() throws IllegalArgumentException for invalid matchFormat")
    void createTournament_invalidMatchFormat_throwsIllegalArgument() {
        assertThatThrownBy(
                        () ->
                                service.createTournament(
                                        "Bad Format",
                                        null,
                                        4,
                                        2,
                                        "INVALID_FORMAT",
                                        VALID_SCORING,
                                        VALID_VALIDATION,
                                        VALID_GENERATOR,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // AC4 — updateTournament
    // =========================================================================

    @Test
    @DisplayName("updateTournament() throws ConflictException for non-DRAFT tournament")
    void updateTournament_nonDraft_throwsConflictException() {
        UUID id = UUID.randomUUID();
        Tournament active = buildTournamentWithStatus("Active Tournament", "ACTIVE");
        active.setId(id);
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(active));

        assertThatThrownBy(
                        () ->
                                service.updateTournament(
                                        id, "New Name", null, 0, 0, null, null, null, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("updateTournament() applies non-null fields on DRAFT tournament")
    void updateTournament_draftTournament_updatesFields() {
        UUID id = UUID.randomUUID();
        Tournament draft = buildDraftTournament("Original Name");
        draft.setId(id);
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(draft));
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tournament result =
                service.updateTournament(
                        id,
                        "Updated Name",
                        null,
                        0,
                        0,
                        VALID_FORMAT,
                        VALID_SCORING,
                        VALID_VALIDATION,
                        VALID_GENERATOR,
                        null);

        assertThat(result.getDescription()).isEqualTo("Updated Name");
    }

    // =========================================================================
    // AC5 — deleteTournament (E48S13 cascade-delete behavior)
    // Old DRAFT-only + phase-check semantics REPLACED per DEC-22 Iron Law §refactor-clause
    // =========================================================================

    @Test
    @DisplayName(
            "deleteTournament() throws TournamentCascadeDeleteActiveException for ACTIVE"
                    + " tournament (E48S13)")
    void deleteTournament_activeTournament_throwsTypedException() {
        UUID id = UUID.randomUUID();
        Tournament active = buildTournamentWithStatus("Active", "ACTIVE");
        active.setId(id);
        when(tournamentRepository.findByIdForUpdate(id)).thenReturn(active);

        assertThatThrownBy(() -> service.deleteTournament(id))
                .isInstanceOf(TournamentCascadeDeleteActiveException.class);
    }

    @Test
    @DisplayName(
            "deleteTournament() throws TournamentCascadeDeleteCompletedException for COMPLETED"
                    + " tournament (E48S13)")
    void deleteTournament_completedTournament_throwsTypedException() {
        UUID id = UUID.randomUUID();
        Tournament completed = buildTournamentWithStatus("Completed", "COMPLETED");
        completed.setId(id);
        when(tournamentRepository.findByIdForUpdate(id)).thenReturn(completed);

        assertThatThrownBy(() -> service.deleteTournament(id))
                .isInstanceOf(TournamentCascadeDeleteCompletedException.class);
    }

    @Test
    @DisplayName("deleteTournament() cascade-deletes DRAFT tournament (E48S13)")
    void deleteTournament_draftTournament_cascadeDeletesSuccessfully() {
        UUID id = UUID.randomUUID();
        Tournament draft = buildDraftTournament("Draft");
        draft.setId(id);
        when(tournamentRepository.findByIdForUpdate(id)).thenReturn(draft);
        // Stub JdbcTemplate.update for all cascade-delete SQL steps (no-op stubs)
        when(jdbcTemplate.update(ArgumentMatchers.anyString(), ArgumentMatchers.eq(id)))
                .thenReturn(0);

        service.deleteTournament(id);

        verify(tournamentRepository).deleteById(id);
    }

    @Test
    @DisplayName("deleteTournament() cascade-deletes PLANNED tournament (E48S13)")
    void deleteTournament_plannedTournament_cascadeDeletesSuccessfully() {
        UUID id = UUID.randomUUID();
        Tournament planned = buildTournamentWithStatus("Planned", "PLANNED");
        planned.setId(id);
        when(tournamentRepository.findByIdForUpdate(id)).thenReturn(planned);
        when(jdbcTemplate.update(ArgumentMatchers.anyString(), ArgumentMatchers.eq(id)))
                .thenReturn(0);

        service.deleteTournament(id);

        verify(tournamentRepository).deleteById(id);
    }

    @Test
    @DisplayName("deleteTournament() cascade-deletes CANCELLED tournament (E48S13)")
    void deleteTournament_cancelledTournament_cascadeDeletesSuccessfully() {
        UUID id = UUID.randomUUID();
        Tournament cancelled = buildTournamentWithStatus("Cancelled", "CANCELLED");
        cancelled.setId(id);
        when(tournamentRepository.findByIdForUpdate(id)).thenReturn(cancelled);
        when(jdbcTemplate.update(ArgumentMatchers.anyString(), ArgumentMatchers.eq(id)))
                .thenReturn(0);

        service.deleteTournament(id);

        verify(tournamentRepository).deleteById(id);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Tournament buildDraftTournament(String description) {
        return buildTournamentWithStatus(description, "DRAFT");
    }

    private Tournament buildTournamentWithStatus(String description, String status) {
        Tournament t = new Tournament();
        t.setId(UUID.randomUUID());
        t.setDescription(description);
        t.setMatchFormat(VALID_FORMAT);
        t.setScoringRuleId(VALID_SCORING);
        t.setSetValidationRuleId(VALID_VALIDATION);
        t.setMatchGeneratorId(VALID_GENERATOR);
        t.setStatus(status);
        t.setCreatedAt(LocalDateTime.now());
        t.setFieldCount(2);
        t.setTeamCount(4);
        return t;
    }
}
