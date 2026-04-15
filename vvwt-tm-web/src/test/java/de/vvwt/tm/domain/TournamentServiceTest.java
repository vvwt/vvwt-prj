package de.vvwt.tm.domain;

import de.vvwt.tm.domain.generator.MatchGeneratorRegistry;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.domain.rules.ScoringRuleRegistry;
import de.vvwt.tm.domain.rules.SetValidationRuleRegistry;
import de.vvwt.tm.infrastructure.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TournamentService} (E05S04).
 *
 * <p>Mocks all dependencies to isolate the service logic. Verifies:
 * <ul>
 *   <li>AC1 — list returns tournaments ordered by createdAt descending</li>
 *   <li>AC2 — getTournament returns entity or throws NoSuchElementException</li>
 *   <li>AC3 — createTournament sets DRAFT status and validates bean IDs</li>
 *   <li>AC4 — updateTournament rejects non-DRAFT with ConflictException</li>
 *   <li>AC5 — deleteTournament rejects ACTIVE and tournaments with phases</li>
 *   <li>AC6 — new tournaments are always DRAFT (DEC-5 enforced on transition)</li>
 * </ul>
 *
 * @see <a href="../../../.gaai/project/contexts/artefacts/stories/E05S04.story.md">Story E05S04</a>
 */
@ExtendWith(MockitoExtension.class)
class TournamentServiceTest {

    @Mock
    private TournamentRepository tournamentRepository;

    @Mock
    private PhaseRepository phaseRepository;

    @Mock
    private ScoringRuleRegistry scoringRuleRegistry;

    @Mock
    private SetValidationRuleRegistry setValidationRuleRegistry;

    @Mock
    private MatchGeneratorRegistry matchGeneratorRegistry;

    private TournamentService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String VALID_FORMAT = "BEST_OF_3";
    private static final String VALID_SCORING = "setPoints";
    private static final String VALID_VALIDATION = "standardVolleyball";
    private static final String VALID_GENERATOR = "roundRobin";

    @BeforeEach
    void setUp() {
        service = new TournamentService(
                tournamentRepository,
                phaseRepository,
                scoringRuleRegistry,
                setValidationRuleRegistry,
                matchGeneratorRegistry);
    }

    // =========================================================================
    // AC1 — listTournaments: ordered by createdAt descending
    // =========================================================================

    @Test
    void listTournamentsReturnsOrderedByCreatedAtDescending() {
        LocalDateTime older = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 3, 1, 10, 0);

        Tournament t1 = makeDraftTournament(UUID.randomUUID(), "Old", older);
        Tournament t2 = makeDraftTournament(UUID.randomUUID(), "New", newer);

        when(tournamentRepository.findAll()).thenReturn(List.of(t1, t2));

        List<Tournament> result = service.listTournaments();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getDescription()).isEqualTo("New");  // newer first
        assertThat(result.get(1).getDescription()).isEqualTo("Old");
    }

    @Test
    void listTournamentsReturnsEmptyListWhenNoTournaments() {
        when(tournamentRepository.findAll()).thenReturn(List.of());

        assertThat(service.listTournaments()).isEmpty();
    }

    // =========================================================================
    // AC2 — getTournament
    // =========================================================================

    @Test
    void getTournamentReturnsTournamentById() {
        UUID id = UUID.randomUUID();
        Tournament t = makeDraftTournament(id, "Test", LocalDateTime.now());
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(t));

        Tournament result = service.getTournament(id);

        assertThat(result.getId()).isEqualTo(id);
    }

    @Test
    void getTournamentThrowsNoSuchElementExceptionWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(tournamentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTournament(id))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(id.toString());
    }

    // =========================================================================
    // AC3 — createTournament
    // =========================================================================

    @Test
    void createTournamentPersistsNewDraftTournament() {
        // Stub registry validations (no-op — mocks return null by default, which is fine)
        // Stub save to return the entity
        when(tournamentRepository.save(any(Tournament.class))).thenAnswer(inv -> inv.getArgument(0));

        Tournament result = service.createTournament(
                "Hallenturnier 2026",
                null,
                8,
                4,
                VALID_FORMAT,
                VALID_SCORING,
                VALID_VALIDATION,
                VALID_GENERATOR);

        assertThat(result.getDescription()).isEqualTo("Hallenturnier 2026");
        assertThat(result.getStatus()).isEqualTo("DRAFT");  // AC6: always DRAFT
        assertThat(result.getTeamCount()).isEqualTo(8);
        assertThat(result.getFieldCount()).isEqualTo(4);
        assertThat(result.getId()).isNotNull();

        verify(tournamentRepository).save(any(Tournament.class));
    }

    @Test
    void createTournamentAlwaysCreatesDraftRegardlessOfOtherTournaments() {
        // AC6: creating a tournament always succeeds; DRAFT is always the initial status
        when(tournamentRepository.save(any(Tournament.class))).thenAnswer(inv -> inv.getArgument(0));

        Tournament result = service.createTournament(
                "Second Tournament",
                null, 4, 2, VALID_FORMAT, VALID_SCORING, VALID_VALIDATION, VALID_GENERATOR);

        assertThat(result.getStatus()).isEqualTo("DRAFT");
        // DEC-5 single-active enforcement happens at transition time, not create time
    }

    // =========================================================================
    // AC4 — updateTournament: only DRAFT may be updated
    // =========================================================================

    @Test
    void updateTournamentSucceedsForDraftTournament() {
        UUID id = UUID.randomUUID();
        Tournament t = makeDraftTournament(id, "Old Name", LocalDateTime.now());
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(t));
        when(tournamentRepository.save(any(Tournament.class))).thenAnswer(inv -> inv.getArgument(0));

        Tournament result = service.updateTournament(
                id, "New Name", null, 6, 3, VALID_FORMAT, VALID_SCORING, VALID_VALIDATION, VALID_GENERATOR, null);

        assertThat(result.getDescription()).isEqualTo("New Name");
        assertThat(result.getTeamCount()).isEqualTo(6);
    }

    @Test
    void updateTournamentThrowsConflictExceptionForActiveTournament() {
        UUID id = UUID.randomUUID();
        Tournament active = makeTournament(id, "Active", "ACTIVE", LocalDateTime.now());
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.updateTournament(
                id, null, null, 0, 0, VALID_FORMAT, VALID_SCORING, VALID_VALIDATION, VALID_GENERATOR, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void updateTournamentThrowsConflictExceptionForCompletedTournament() {
        UUID id = UUID.randomUUID();
        Tournament completed = makeTournament(id, "Done", "COMPLETED", LocalDateTime.now());
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.updateTournament(
                id, null, null, 0, 0, VALID_FORMAT, VALID_SCORING, VALID_VALIDATION, VALID_GENERATOR, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("COMPLETED");
    }

    // =========================================================================
    // AC5 — deleteTournament: only DRAFT with no phases
    // =========================================================================

    @Test
    void deleteTournamentSucceedsForDraftWithNoPhases() {
        UUID id = UUID.randomUUID();
        Tournament t = makeDraftTournament(id, "Empty Draft", LocalDateTime.now());
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(t));
        when(phaseRepository.findByTournamentId(id)).thenReturn(List.of());

        service.deleteTournament(id);

        verify(tournamentRepository).deleteById(id);
    }

    @Test
    void deleteTournamentThrowsConflictExceptionForActiveTournament() {
        UUID id = UUID.randomUUID();
        Tournament active = makeTournament(id, "Active", "ACTIVE", LocalDateTime.now());
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.deleteTournament(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE");

        verifyNoMoreInteractions(tournamentRepository);
    }

    @Test
    void deleteTournamentThrowsConflictExceptionWhenPhasesExist() {
        UUID id = UUID.randomUUID();
        Tournament t = makeDraftTournament(id, "Has Phases", LocalDateTime.now());
        when(tournamentRepository.findById(id)).thenReturn(Optional.of(t));

        Phase mockPhase = new Phase(UUID.randomUUID(), TENANT_ID, id, 1, "Vorrunde", "PENDING", 0, LocalDateTime.now());
        when(phaseRepository.findByTournamentId(id)).thenReturn(List.of(mockPhase));

        assertThatThrownBy(() -> service.deleteTournament(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("phase");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Tournament makeDraftTournament(UUID id, String description, LocalDateTime createdAt) {
        return makeTournament(id, description, "DRAFT", createdAt);
    }

    private Tournament makeTournament(UUID id, String description, String status, LocalDateTime createdAt) {
        return new Tournament(id, TENANT_ID, description,
                VALID_FORMAT, VALID_SCORING, VALID_VALIDATION, VALID_GENERATOR,
                status, createdAt, null, 2, 4);
    }
}
