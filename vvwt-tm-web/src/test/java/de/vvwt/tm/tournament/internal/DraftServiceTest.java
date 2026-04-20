package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.internal.draft.DraftBreak;
import de.vvwt.tm.tournament.internal.draft.DraftConfig;
import de.vvwt.tm.tournament.internal.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.internal.draft.DraftSection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RED — DraftService unit test (AC-TDD-DraftService).
 *
 * <p>Tests {@code preview(DraftConfig, int)} pure computation and {@code apply(UUID, DraftConfig)}
 * phase-creation delegation. Mocks Phase-aggregate collaborators from E21S03 — the collaborator's
 * persistence is already tested in E21S03 own DAO tests (E15S03 precedent).
 *
 * <p>Inventory: E21S01 line 171.
 *
 * @see DraftService
 * @see PhaseRepository
 * @see PhaseBreakRepository
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E21S03">E21S03 — Phase aggregate (mocked collaborator)</a>
 */
@ExtendWith(MockitoExtension.class)
class DraftServiceTest {

    @Mock private PhaseRepository phaseRepository;
    @Mock private PhaseBreakRepository phaseBreakRepository;

    @InjectMocks private DraftService draftService;

    private static DraftSection simpleSection(int sectionNumber) {
        return new DraftSection(
                sectionNumber, "team_number", 1, "roundrobin", 0, 0, 15, 1, List.of());
    }

    private static DraftSection sectionWithBreak(int sectionNumber) {
        DraftBreak breakItem = new DraftBreak(1, 10, "Pause");
        return new DraftSection(
                sectionNumber, "team_number", 1, "roundrobin", 0, 0, 15, 1, List.of(breakItem));
    }

    // -------------------------------------------------------------------------
    // preview() — pure computation
    // -------------------------------------------------------------------------

    /**
     * AC-TDD-DraftService: preview returns one DraftPreviewSection per section in config. No DB
     * side effect (no verify on repository interactions).
     */
    @Test
    void preview_withOneSection_returnsOnePreviewSection() {
        DraftConfig config = new DraftConfig(List.of(simpleSection(1)));
        int participatingTeamCount = 4;

        DraftPreviewResult result = draftService.preview(config, participatingTeamCount);

        assertThat(result.sections()).hasSize(1);
        // 4 teams / 1 group = 4 teams per group → round-robin: (4-1) = 3 laps, 6 matches
        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(3);
        assertThat(result.sections().get(0).getTotalMatches()).isEqualTo(6);
        assertThat(result.timeline()).isEmpty();
    }

    /** AC-TDD-DraftService: preview with empty config returns empty sections. */
    @Test
    void preview_withEmptyConfig_returnsEmptySections() {
        DraftConfig config = DraftConfig.empty();

        DraftPreviewResult result = draftService.preview(config, 4);

        assertThat(result.sections()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // apply() — phase creation
    // -------------------------------------------------------------------------

    /** AC-TDD-DraftService: apply with one section creates one Phase. */
    @Test
    void apply_withOneSection_createsOnePhase() {
        UUID tournamentId = UUID.randomUUID();
        DraftConfig config = new DraftConfig(List.of(simpleSection(1)));

        // PhaseRepository.findByTournamentId returns empty (no phases yet)
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of());

        Phase savedPhase =
                new Phase(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tournamentId,
                        1,
                        "Phase 1",
                        "PENDING",
                        0,
                        LocalDateTime.now());
        when(phaseRepository.save(any(Phase.class))).thenReturn(savedPhase);

        List<UUID> result = draftService.apply(tournamentId, config);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(savedPhase.getId());
        verify(phaseRepository, times(1)).save(any(Phase.class));
    }

    /** AC-TDD-DraftService: apply with two sections creates two Phases. */
    @Test
    void apply_withTwoSections_createsTwoPhases() {
        UUID tournamentId = UUID.randomUUID();
        DraftConfig config = new DraftConfig(List.of(simpleSection(1), simpleSection(2)));

        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of());

        Phase phase1 =
                new Phase(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tournamentId,
                        1,
                        "Phase 1",
                        "PENDING",
                        0,
                        LocalDateTime.now());
        Phase phase2 =
                new Phase(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tournamentId,
                        2,
                        "Phase 2",
                        "PENDING",
                        0,
                        LocalDateTime.now());
        when(phaseRepository.save(any(Phase.class))).thenReturn(phase1, phase2);

        List<UUID> result = draftService.apply(tournamentId, config);

        assertThat(result).hasSize(2);
        verify(phaseRepository, times(2)).save(any(Phase.class));
    }

    /** AC-TDD-DraftService: apply with a section containing a break persists PhaseBreak. */
    @Test
    void apply_withSectionContainingBreak_persistsPhaseBreak() {
        UUID tournamentId = UUID.randomUUID();
        DraftConfig config = new DraftConfig(List.of(sectionWithBreak(1)));

        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of());

        Phase savedPhase =
                new Phase(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tournamentId,
                        1,
                        "Phase 1",
                        "PENDING",
                        0,
                        LocalDateTime.now());
        when(phaseRepository.save(any(Phase.class))).thenReturn(savedPhase);

        draftService.apply(tournamentId, config);

        verify(phaseBreakRepository, times(1)).save(any(PhaseBreak.class));
    }

    /**
     * AC-DRAFT-APPLY-IDEMPOTENCY: re-apply throws DraftAlreadyAppliedException (fails-fast). Legacy
     * behaviour confirmed from domain.DraftService.applyDraft() line 297–305.
     */
    @Test
    void apply_whenPhasesAlreadyExist_throwsDraftAlreadyAppliedException() {
        UUID tournamentId = UUID.randomUUID();
        DraftConfig config = new DraftConfig(List.of(simpleSection(1)));

        Phase existingPhase =
                new Phase(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tournamentId,
                        1,
                        "Phase 1",
                        "PENDING",
                        0,
                        LocalDateTime.now());
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(existingPhase));

        assertThatThrownBy(() -> draftService.apply(tournamentId, config))
                .isInstanceOf(DraftAlreadyAppliedException.class);

        verify(phaseRepository, never()).save(any(Phase.class));
    }
}
