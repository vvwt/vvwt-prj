package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseBreakRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.infrastructure.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PhaseBreakService} (E08S01).
 *
 * <p>Mocks all dependencies to isolate service logic. Verifies:
 * <ul>
 *   <li>AC5 — lap range validation: afterLapNumber must be >= 1 and < totalLaps</li>
 *   <li>AC6 — duplicate detection: ConflictException when break at same position exists</li>
 *   <li>AC7 — tenant guard: delegated to PhaseRepository / PhaseBreakRepository</li>
 *   <li>AC8 — i18n: error messages sourced from MessageSource</li>
 * </ul>
 *
 * @see <a href="../../../.gaai/project/contexts/artefacts/stories/E08S01.story.md">Story E08S01</a>
 */
@ExtendWith(MockitoExtension.class)
class PhaseBreakServiceTest {

    @Mock
    private PhaseRepository phaseRepository;

    @Mock
    private MatchRepository matchRepository;

    @Mock
    private PhaseBreakRepository phaseBreakRepository;

    @Mock
    private MessageSource messageSource;

    private PhaseBreakService service;

    private static final UUID PHASE_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PhaseBreakService(phaseRepository, matchRepository,
                phaseBreakRepository, messageSource);
    }

    // =========================================================================
    // Helper factories
    // =========================================================================

    private Phase buildPhase() {
        Phase phase = new Phase();
        phase.setId(PHASE_ID);
        phase.setTenantId(TENANT_ID);
        return phase;
    }

    /** Builds a Match with a specific lapNumber for testing totalLaps calculation. */
    private Match buildMatch(int lapNumber) {
        Match match = new Match();
        match.setId(UUID.randomUUID());
        match.setTenantId(TENANT_ID);
        match.setPhaseId(PHASE_ID);
        match.setLapNumber(lapNumber);
        return match;
    }

    // =========================================================================
    // AC5 — lap range validation
    // =========================================================================

    /**
     * AC5: valid afterLapNumber (1 <= n < totalLaps) should succeed and persist the break.
     */
    @Test
    void createPhaseBreak_validInput_savesAndReturns() {
        // Arrange
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(buildPhase()));
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(
                List.of(buildMatch(1), buildMatch(2), buildMatch(3), buildMatch(4)));
        when(phaseBreakRepository.findByPhaseIdAndAfterLapNumber(PHASE_ID, 2))
                .thenReturn(Optional.empty());

        PhaseBreak saved = new PhaseBreak(UUID.randomUUID(), TENANT_ID, PHASE_ID, 2, 30, "Mittagspause");
        when(phaseBreakRepository.save(any())).thenReturn(saved);

        // Act
        PhaseBreak result = service.createPhaseBreak(PHASE_ID, 2, 30, "Mittagspause");

        // Assert
        assertThat(result).isSameAs(saved);
        verify(phaseBreakRepository).save(any());
    }

    /**
     * AC5: afterLapNumber = 0 is invalid (must be >= 1).
     */
    @Test
    void createPhaseBreak_lapNumberZero_throwsIllegalArgumentException() {
        // Arrange
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(buildPhase()));
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(
                List.of(buildMatch(1), buildMatch(2), buildMatch(3)));
        when(messageSource.getMessage(eq("phase_break.invalid_lap_range"), any(), any(Locale.class)))
                .thenReturn("Ungueltige Rundenposition");

        // Act + Assert
        assertThatThrownBy(() -> service.createPhaseBreak(PHASE_ID, 0, 30, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ungueltige Rundenposition");
    }

    /**
     * AC5: afterLapNumber equal to totalLaps is invalid (break after last lap has no following lap).
     */
    @Test
    void createPhaseBreak_lapAtTotalLaps_throwsIllegalArgumentException() {
        // Arrange: matches cover laps 1..3 (totalLaps = 3); afterLapNumber=3 is not valid (>= totalLaps)
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(buildPhase()));
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(
                List.of(buildMatch(1), buildMatch(2), buildMatch(3)));
        when(messageSource.getMessage(eq("phase_break.invalid_lap_range"), any(), any(Locale.class)))
                .thenReturn("Ungueltige Rundenposition");

        // Act + Assert
        assertThatThrownBy(() -> service.createPhaseBreak(PHASE_ID, 3, 30, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ungueltige Rundenposition");
    }

    /**
     * AC5: afterLapNumber beyond totalLaps is invalid.
     */
    @Test
    void createPhaseBreak_lapBeyondTotalLaps_throwsIllegalArgumentException() {
        // Arrange: matches cover laps 1..4 (totalLaps = 4); afterLapNumber=5 is invalid
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(buildPhase()));
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(
                List.of(buildMatch(1), buildMatch(2), buildMatch(3), buildMatch(4)));
        when(messageSource.getMessage(eq("phase_break.invalid_lap_range"), any(), any(Locale.class)))
                .thenReturn("Ungueltige Rundenposition");

        // Act + Assert
        assertThatThrownBy(() -> service.createPhaseBreak(PHASE_ID, 5, 30, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ungueltige Rundenposition");
    }

    /**
     * AC5: when no matches are scheduled (totalLaps = 0), any afterLapNumber is invalid.
     */
    @Test
    void createPhaseBreak_noMatchesScheduled_throwsIllegalArgumentException() {
        // Arrange
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(buildPhase()));
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of());
        when(messageSource.getMessage(eq("phase_break.invalid_lap_range"), any(), any(Locale.class)))
                .thenReturn("Ungueltige Rundenposition");

        // Act + Assert
        assertThatThrownBy(() -> service.createPhaseBreak(PHASE_ID, 1, 30, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // AC6 — duplicate detection
    // =========================================================================

    /**
     * AC6: when a break already exists at the given lap boundary, ConflictException is thrown.
     */
    @Test
    void createPhaseBreak_duplicateLapPosition_throwsConflictException() {
        // Arrange
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(buildPhase()));
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(
                List.of(buildMatch(1), buildMatch(2), buildMatch(3), buildMatch(4)));
        PhaseBreak existing = new PhaseBreak(UUID.randomUUID(), TENANT_ID, PHASE_ID, 2, 15, "Kurze Pause");
        when(phaseBreakRepository.findByPhaseIdAndAfterLapNumber(PHASE_ID, 2))
                .thenReturn(Optional.of(existing));
        when(messageSource.getMessage(eq("phase_break.duplicate_lap_position"), any(), any(Locale.class)))
                .thenReturn("Bereits eine Pause vorhanden");

        // Act + Assert
        assertThatThrownBy(() -> service.createPhaseBreak(PHASE_ID, 2, 40, "Mittagspause"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Bereits eine Pause vorhanden");
    }

    // =========================================================================
    // Phase not found
    // =========================================================================

    /**
     * Phase not found (out of scope or does not exist) — NoSuchElementException.
     */
    @Test
    void createPhaseBreak_phaseNotFound_throwsNoSuchElementException() {
        // Arrange
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.createPhaseBreak(PHASE_ID, 2, 30, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(PHASE_ID.toString());
    }

    // =========================================================================
    // findByPhaseId delegation
    // =========================================================================

    /**
     * findByPhaseId delegates to repository (tenant scope enforced by repository layer).
     */
    @Test
    void findByPhaseId_delegatesToRepository() {
        // Arrange
        PhaseBreak pb = new PhaseBreak(UUID.randomUUID(), TENANT_ID, PHASE_ID, 2, 30, null);
        when(phaseBreakRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(pb));

        // Act
        List<PhaseBreak> result = service.findByPhaseId(PHASE_ID);

        // Assert
        assertThat(result).containsExactly(pb);
    }
}
