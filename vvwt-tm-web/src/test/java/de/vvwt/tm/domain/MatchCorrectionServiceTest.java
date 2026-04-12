package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MatchCorrectionService} (AC1–AC4, AC6, AC10, AC11 — E05S11).
 *
 * <p>Focuses on:
 * <ul>
 *   <li>AC1 — getMatchDetail assembles correct MatchDetail with team/referee descriptions</li>
 *   <li>AC1 — cross-tenant/missing match throws NoSuchElementException (AC11)</li>
 *   <li>AC2 — correctSet delegates to CascadeRecomputeService with correct SetResultInput</li>
 *   <li>AC2 — correctSet propagates ValidationException from cascade (AC10)</li>
 *   <li>AC3 — enterNewSet auto-determines setIndex from existing set count</li>
 *   <li>AC4 — audit_log written by cascade (verified via cascade delegation)</li>
 *   <li>AC11 — cross-tenant match returns 404</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MatchCorrectionServiceTest {

    @Mock private MatchRepository matchRepository;
    @Mock private SetResultRepository setResultRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private CascadeRecomputeService cascadeRecomputeService;

    private MatchCorrectionService service;

    private final UUID tenantId      = UUID.randomUUID();
    private final UUID tournamentId  = UUID.randomUUID();
    private final UUID phaseId       = UUID.randomUUID();
    private final UUID matchId       = UUID.randomUUID();
    private final UUID avatar1Id     = UUID.randomUUID();
    private final UUID avatar2Id     = UUID.randomUUID();
    private final UUID teamId        = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MatchCorrectionService(
                matchRepository,
                setResultRepository,
                teamAvatarRepository,
                teamRepository,
                tournamentRepository,
                cascadeRecomputeService
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Match buildMatch() {
        return new Match(
                matchId, tenantId, tournamentId, phaseId,
                avatar1Id, avatar2Id,
                MatchState.ENABLED.getLegacyCode(), 3,
                1, 1,
                null, null, null,
                LocalDateTime.now()
        );
    }

    private Tournament buildTournament() {
        Tournament t = new Tournament();
        t.setId(tournamentId);
        t.setTenantId(tenantId);
        t.setMatchFormat("BEST_OF_3");
        t.setStatus("ACTIVE");
        return t;
    }

    private TeamAvatar buildAvatar(UUID avatarId, int groupNum, int groupPos) {
        TeamAvatar av = new TeamAvatar();
        av.setId(avatarId);
        av.setTenantId(tenantId);
        av.setPhaseId(phaseId);
        av.setGroupNumber(groupNum);
        av.setGroupPosition(groupPos);
        // No description — will use fallback "Group N Pos P"
        return av;
    }

    private TeamAvatar buildAvatarWithDescription(UUID avatarId, String description) {
        TeamAvatar av = buildAvatar(avatarId, 1, 1);
        av.setDescription(description);
        return av;
    }

    private SetResult buildSetResult(int setIndex, int t1, int t2) {
        SetResult sr = new SetResult();
        sr.setMatchId(matchId);
        sr.setSetIndex(setIndex);
        sr.setTenantId(tenantId);
        sr.setPhaseId(phaseId);
        sr.setTeam1Points(t1);
        sr.setTeam2Points(t2);
        sr.setSetStateCode(SetState.WINNER1.getLegacyCode());
        sr.setChangeTime(LocalDateTime.now());
        return sr;
    }

    // =========================================================================
    // AC1 — getMatchDetail
    // =========================================================================

    @Test
    void getMatchDetail_returnsAssembledDetail_withAvatarDescriptions() {
        // Given
        Match match = buildMatch();
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(buildTournament()));

        List<TeamAvatar> avatars = List.of(
                buildAvatarWithDescription(avatar1Id, "Team Alpha"),
                buildAvatarWithDescription(avatar2Id, "Team Beta")
        );
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(setResultRepository.findByMatchId(matchId)).thenReturn(Collections.emptyList());

        // When
        MatchCorrectionService.MatchDetail detail = service.getMatchDetail(matchId);

        // Then
        assertThat(detail.matchId()).isEqualTo(matchId);
        assertThat(detail.phaseId()).isEqualTo(phaseId);
        assertThat(detail.tournamentId()).isEqualTo(tournamentId);
        assertThat(detail.team1Description()).isEqualTo("Team Alpha");
        assertThat(detail.team2Description()).isEqualTo("Team Beta");
        assertThat(detail.matchFormat()).isEqualTo("BEST_OF_3");
        assertThat(detail.matchState()).isEqualTo(MatchState.ENABLED.name());
        assertThat(detail.setLimit()).isEqualTo(3);
        assertThat(detail.setResults()).isEmpty();
    }

    @Test
    void getMatchDetail_usesGroupFallback_whenAvatarHasNoDescription() {
        // Given
        Match match = buildMatch();
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(buildTournament()));

        List<TeamAvatar> avatars = List.of(
                buildAvatar(avatar1Id, 2, 3),
                buildAvatar(avatar2Id, 2, 4)
        );
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(setResultRepository.findByMatchId(matchId)).thenReturn(Collections.emptyList());

        // When
        MatchCorrectionService.MatchDetail detail = service.getMatchDetail(matchId);

        // Then
        assertThat(detail.team1Description()).isEqualTo("Group 2 Pos 3");
        assertThat(detail.team2Description()).isEqualTo("Group 2 Pos 4");
    }

    @Test
    void getMatchDetail_includesSetResults_orderedBySetIndex() {
        // Given
        Match match = buildMatch();
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(Collections.emptyList());

        // Return sets in reverse order — service should sort
        List<SetResult> sets = List.of(
                buildSetResult(1, 25, 18),
                buildSetResult(0, 25, 20)
        );
        when(setResultRepository.findByMatchId(matchId)).thenReturn(sets);

        // When
        MatchCorrectionService.MatchDetail detail = service.getMatchDetail(matchId);

        // Then — setResults sorted ascending by setIndex
        assertThat(detail.setResults()).hasSize(2);
        assertThat(detail.setResults().get(0).setIndex()).isEqualTo(0);
        assertThat(detail.setResults().get(0).team1Points()).isEqualTo(25);
        assertThat(detail.setResults().get(1).setIndex()).isEqualTo(1);
    }

    @Test
    void getMatchDetail_throwsNotFound_whenMatchMissing() {
        // Given
        when(matchRepository.findById(matchId)).thenReturn(Optional.empty());

        // When / Then (AC11)
        assertThatThrownBy(() -> service.getMatchDetail(matchId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(matchId.toString());
    }

    @Test
    void getMatchDetail_throwsNotFound_whenMatchBelongsToDifferentTenant() {
        // Given — match found but tournament not found for this tenant (cross-tenant)
        Match match = buildMatch();
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.empty());

        // When / Then (AC11)
        assertThatThrownBy(() -> service.getMatchDetail(matchId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(matchId.toString());
    }

    // =========================================================================
    // AC2 — correctSet
    // =========================================================================

    @Test
    void correctSet_delegatesToCascade_withCorrectInput() {
        // Given
        Match match = buildMatch();
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(Collections.emptyList());
        when(setResultRepository.findByMatchId(matchId)).thenReturn(Collections.emptyList());

        // When
        service.correctSet(matchId, 0, 25, 18, "admin");

        // Then — cascade was called with admin_correction reason (AC4)
        ArgumentCaptor<SetResultInput> captor = ArgumentCaptor.forClass(SetResultInput.class);
        verify(cascadeRecomputeService).registerMatchResult(captor.capture());

        SetResultInput captured = captor.getValue();
        assertThat(captured.matchId()).isEqualTo(matchId);
        assertThat(captured.setIndex()).isEqualTo(0);
        assertThat(captured.team1Points()).isEqualTo(25);
        assertThat(captured.team2Points()).isEqualTo(18);
        assertThat(captured.actorId()).isEqualTo("admin");
        assertThat(captured.reason()).isEqualTo(MatchCorrectionService.ADMIN_CORRECTION_REASON);
    }

    @Test
    void correctSet_propagatesValidationException_onInvalidScores() {
        // Given (AC10)
        Match match = buildMatch();
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(buildTournament()));

        doThrow(new ValidationException("Score below minimum"))
                .when(cascadeRecomputeService).registerMatchResult(any());

        // When / Then
        assertThatThrownBy(() -> service.correctSet(matchId, 0, 0, 0, "admin"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Score below minimum");
    }

    @Test
    void correctSet_throwsNotFound_whenMatchMissing() {
        // Given (AC11)
        when(matchRepository.findById(matchId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.correctSet(matchId, 0, 25, 18, "admin"))
                .isInstanceOf(NoSuchElementException.class);

        // Cascade must NOT have been called
        verify(cascadeRecomputeService, never()).registerMatchResult(any());
    }

    // =========================================================================
    // AC3 — enterNewSet
    // =========================================================================

    @Test
    void enterNewSet_autoDeterminesSetIndex_fromExistingSetCount() {
        // Given — 2 sets already exist → next setIndex = 2 (AC3)
        Match match = buildMatch();
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(Collections.emptyList());
        when(setResultRepository.findByMatchId(matchId))
                .thenReturn(List.of(buildSetResult(0, 25, 20), buildSetResult(1, 25, 18)));

        // When
        service.enterNewSet(matchId, 15, 25, "admin");

        // Then — cascade called with setIndex = 2
        ArgumentCaptor<SetResultInput> captor = ArgumentCaptor.forClass(SetResultInput.class);
        // findByMatchId called twice: once for setIndex, once inside assembleMatchDetail after cascade
        verify(cascadeRecomputeService).registerMatchResult(captor.capture());

        assertThat(captor.getValue().setIndex()).isEqualTo(2);
        assertThat(captor.getValue().team1Points()).isEqualTo(15);
        assertThat(captor.getValue().team2Points()).isEqualTo(25);
        assertThat(captor.getValue().reason()).isEqualTo(MatchCorrectionService.ADMIN_CORRECTION_REASON);
    }

    @Test
    void enterNewSet_setsIndex0_whenNoSetsYet() {
        // Given — no sets yet
        Match match = buildMatch();
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(Collections.emptyList());
        when(setResultRepository.findByMatchId(matchId)).thenReturn(Collections.emptyList());

        // When
        service.enterNewSet(matchId, 25, 10, null);

        // Then — setIndex = 0
        ArgumentCaptor<SetResultInput> captor = ArgumentCaptor.forClass(SetResultInput.class);
        verify(cascadeRecomputeService).registerMatchResult(captor.capture());
        assertThat(captor.getValue().setIndex()).isEqualTo(0);
    }
}
