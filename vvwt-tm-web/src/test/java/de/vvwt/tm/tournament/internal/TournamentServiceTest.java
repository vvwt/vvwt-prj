package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.generator.MatchGeneratorRegistry;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.rules.ScoringRuleRegistry;
import de.vvwt.tm.domain.rules.SetValidationRuleRegistry;
import de.vvwt.tm.infrastructure.web.ConflictException;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TournamentService} (E21S02, AC-TDD-TournamentService).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link TournamentService} at
 * {@code de.vvwt.tm.tournament.internal.TournamentService} did not exist at commit time, causing a
 * compile error — satisfying the DEC-22 Iron Law.
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
 * @see TournamentService
 * @see de.vvwt.tm.tournament.TournamentRepository
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentService unit tests — E21S02 AC-TDD-TournamentService")
class TournamentServiceTest {

    @Mock private TournamentRepository tournamentRepository;

    @Mock private PhaseRepository phaseRepository;

    @Mock private ScoringRuleRegistry scoringRuleRegistry;

    @Mock private SetValidationRuleRegistry setValidationRuleRegistry;

    @Mock private MatchGeneratorRegistry matchGeneratorRegistry;

    private TournamentService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String VALID_FORMAT = "BEST_OF_3";
    private static final String VALID_SCORING = "setPoints";
    private static final String VALID_VALIDATION = "standardVolleyball";
    private static final String VALID_GENERATOR = "roundRobin";

    @BeforeEach
    void setUp() {
        service =
                new TournamentService(
                        tournamentRepository,
                        phaseRepository,
                        scoringRuleRegistry,
                        setValidationRuleRegistry,
                        matchGeneratorRegistry);
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
                        VALID_GENERATOR);

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
                                        VALID_GENERATOR))
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
    // AC5 — deleteTournament
    // =========================================================================

    @Test
    @DisplayName("deleteTournament() throws ConflictException for ACTIVE tournament")
    void deleteTournament_activeTournament_throwsConflictException() {
        UUID id = UUID.randomUUID();
        Tournament active = buildTournamentWithStatus("Active", "ACTIVE");
        active.setId(id);
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.deleteTournament(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("deleteTournament() throws ConflictException when phases exist")
    void deleteTournament_withPhases_throwsConflictException() {
        UUID id = UUID.randomUUID();
        Tournament draft = buildDraftTournament("Draft With Phases");
        draft.setId(id);
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(draft));
        Phase phase = new Phase();
        when(phaseRepository.findByTournamentId(id)).thenReturn(List.of(phase));

        assertThatThrownBy(() -> service.deleteTournament(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("phase");
    }

    @Test
    @DisplayName("deleteTournament() deletes DRAFT tournament with no phases")
    void deleteTournament_draftWithNoPhases_deletesSuccessfully() {
        UUID id = UUID.randomUUID();
        Tournament draft = buildDraftTournament("Draft");
        draft.setId(id);
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(draft));
        when(phaseRepository.findByTournamentId(id)).thenReturn(List.of());

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
        t.setTenantId(TENANT_ID);
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
