package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RED — PhaseBreakService unit test (AC-TDD-PhaseBreakService).
 *
 * <p>Tests at least one CRUD command path with a mocked repository: successful creation and
 * duplicate detection.
 *
 * @see PhaseBreakService
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 179)</a>
 */
@ExtendWith(MockitoExtension.class)
class PhaseBreakServiceTest {

    @Mock private PhaseBreakRepository phaseBreakRepository;

    @InjectMocks private PhaseBreakService service;

    /** AC-TDD-PhaseBreakService: createPhaseBreak delegates to repository save. */
    @Test
    void createPhaseBreak_withValidInput_savesAndReturns() {
        UUID phaseId = UUID.randomUUID();
        PhaseBreak saved =
                new PhaseBreak(UUID.randomUUID(), UUID.randomUUID(), phaseId, 2, 30, "Pause");
        when(phaseBreakRepository.findByPhaseIdAndAfterLapNumber(phaseId, 2))
                .thenReturn(Optional.empty());
        when(phaseBreakRepository.save(any(PhaseBreak.class))).thenReturn(saved);

        PhaseBreak result = service.createPhaseBreak(phaseId, 2, 30, "Pause");

        assertThat(result).isEqualTo(saved);
        verify(phaseBreakRepository).save(any(PhaseBreak.class));
    }

    /** AC-TDD-PhaseBreakService: createPhaseBreak throws on duplicate lap boundary. */
    @Test
    void createPhaseBreak_whenDuplicateLapBoundary_throwsIllegalArgumentException() {
        UUID phaseId = UUID.randomUUID();
        PhaseBreak existing =
                new PhaseBreak(UUID.randomUUID(), UUID.randomUUID(), phaseId, 2, 30, null);
        when(phaseBreakRepository.findByPhaseIdAndAfterLapNumber(phaseId, 2))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createPhaseBreak(phaseId, 2, 15, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    /** AC-TDD-PhaseBreakService: findByPhaseId delegates to repository. */
    @Test
    void findByPhaseId_delegatesToRepository() {
        UUID phaseId = UUID.randomUUID();
        service.findByPhaseId(phaseId);
        verify(phaseBreakRepository).findByPhaseId(phaseId);
    }
}
