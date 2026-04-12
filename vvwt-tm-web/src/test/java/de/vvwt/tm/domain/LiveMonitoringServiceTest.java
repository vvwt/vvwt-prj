package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRatingRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LiveMonitoringService} (AC1–AC4 — E05S10).
 *
 * <p>Focuses on: tenant scope guard (AC14), group table computation and D-33 sort order
 * (AC1, AC2), lap match retrieval (AC3), current lap summary (AC4), and edge cases.
 */
@ExtendWith(MockitoExtension.class)
class LiveMonitoringServiceTest {

    @Mock private PhaseRepository phaseRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private TeamAvatarRatingRepository ratingRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private SetResultRepository setResultRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TournamentRepository tournamentRepository;

    private LiveMonitoringService service;

    private final UUID tenantId      = UUID.randomUUID();
    private final UUID tournamentId  = UUID.randomUUID();
    private final UUID phaseId       = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LiveMonitoringService(
                phaseRepository,
                teamAvatarRepository,
                ratingRepository,
                matchRepository,
                setResultRepository,
                teamRepository,
                tournamentRepository
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Phase activePhase(int currentLap) {
        return new Phase(phaseId, tenantId, tournamentId, 1,
                "Phase 1", Phase.PhaseStatus.ACTIVE.name(), currentLap, LocalDateTime.now());
    }

    private Tournament activeTournament() {
        Tournament t = new Tournament();
        t.setId(tournamentId);
        t.setTenantId(tenantId);
        t.setStatus("ACTIVE");
        return t;
    }

    /** Returns an avatar in group {@code gn} at position {@code pos}. */
    private TeamAvatar avatar(UUID id, int gn, int pos) {
        return new TeamAvatar(id, tenantId, tournamentId, phaseId,
                gn, pos, UUID.randomUUID(), "Avatar " + gn + "/" + pos, null);
    }

    /** Returns a rating with the given avatar id and score fields. */
    private TeamAvatarRating rating(UUID avatarId, int points, double setQ, double ballQ,
                                    boolean withoutAssessment) {
        return new TeamAvatarRating(avatarId, tenantId,
                3, 6, points,
                3, 2,
                40, 30,
                setQ, ballQ,
                withoutAssessment, null);
    }

    private void stubTenantScope() {
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(activePhase(1)));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(activeTournament()));
    }

    // =========================================================================
    // AC14: Tenant scope guard
    // =========================================================================

    @Test
    void getGroupTable_throwsNotFound_whenPhaseNotFound() {
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getGroupTable(phaseId, 1))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getGroupTable_throwsNotFound_whenTournamentNotVisible() {
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(activePhase(1)));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getGroupTable(phaseId, 1))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getGroupTable_throwsIllegalArgument_whenGroupNumberLessThanOne() {
        // IllegalArgumentException is thrown before tenant scope check — no stubs needed
        assertThatThrownBy(() -> service.getGroupTable(phaseId, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // AC1: getGroupTable — D-33 sort order
    // =========================================================================

    @Test
    void getGroupTable_returnsEmptyList_whenNoAvatarsInGroup() {
        stubTenantScope();
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(Collections.emptyList());
        when(ratingRepository.findByPhaseId(phaseId)).thenReturn(Collections.emptyList());
        // teamRepository not called: loadTeamDescriptions returns early when avatars is empty

        List<LiveMonitoringService.GroupTableEntry> result = service.getGroupTable(phaseId, 1);
        assertThat(result).isEmpty();
    }

    @Test
    void getGroupTable_sortsEntriesByD33Order_pointsDescFirst() {
        stubTenantScope();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        TeamAvatar av1 = avatar(id1, 1, 1);
        TeamAvatar av2 = avatar(id2, 1, 2);
        TeamAvatar av3 = avatar(id3, 1, 3);

        // av2 has most points → rank 1; av3 → rank 2; av1 → rank 3
        TeamAvatarRating r1 = rating(id1, 1, 1.0, 1.0, false);
        TeamAvatarRating r2 = rating(id2, 5, 2.0, 2.0, false);
        TeamAvatarRating r3 = rating(id3, 3, 1.5, 1.5, false);

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(av1, av2, av3));
        when(ratingRepository.findByPhaseId(phaseId)).thenReturn(List.of(r1, r2, r3));
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(Collections.emptyList());

        List<LiveMonitoringService.GroupTableEntry> result = service.getGroupTable(phaseId, 1);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).avatarId()).isEqualTo(id2);  // 5 points
        assertThat(result.get(0).rank()).isEqualTo(1);
        assertThat(result.get(1).avatarId()).isEqualTo(id3);  // 3 points
        assertThat(result.get(1).rank()).isEqualTo(2);
        assertThat(result.get(2).avatarId()).isEqualTo(id1);  // 1 point
        assertThat(result.get(2).rank()).isEqualTo(3);
    }

    @Test
    void getGroupTable_withoutAssessmentRanksLast_evenWithHighPoints() {
        stubTenantScope();
        UUID idNormal = UUID.randomUUID();
        UUID idNoAssess = UUID.randomUUID();

        TeamAvatar avNormal = avatar(idNormal, 1, 1);
        TeamAvatar avNoAssess = avatar(idNoAssess, 1, 2);

        // noAssess has higher points but isWithoutAssessment=true → always last (D-26)
        TeamAvatarRating rNormal   = rating(idNormal,   3, 1.5, 1.5, false);
        TeamAvatarRating rNoAssess = rating(idNoAssess, 99, 9.9, 9.9, true);

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(avNormal, avNoAssess));
        when(ratingRepository.findByPhaseId(phaseId)).thenReturn(List.of(rNormal, rNoAssess));
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(Collections.emptyList());

        List<LiveMonitoringService.GroupTableEntry> result = service.getGroupTable(phaseId, 1);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).avatarId()).isEqualTo(idNormal);
        assertThat(result.get(1).avatarId()).isEqualTo(idNoAssess);
        assertThat(result.get(1).isWithoutAssessment()).isTrue();
    }

    @Test
    void getGroupTable_usesZeroRating_whenRatingMissing() {
        stubTenantScope();
        UUID id1 = UUID.randomUUID();
        TeamAvatar av1 = avatar(id1, 1, 1);

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(av1));
        when(ratingRepository.findByPhaseId(phaseId)).thenReturn(Collections.emptyList());
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(Collections.emptyList());

        List<LiveMonitoringService.GroupTableEntry> result = service.getGroupTable(phaseId, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).points()).isEqualTo(0);
        assertThat(result.get(0).matchCount()).isEqualTo(0);
    }

    // =========================================================================
    // AC2: getAllGroupTables — multiple groups
    // =========================================================================

    @Test
    void getAllGroupTables_returnsAllGroups_partitionedByGroupNumber() {
        stubTenantScope();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        TeamAvatar av1 = avatar(id1, 1, 1);
        TeamAvatar av2 = avatar(id2, 2, 1);

        TeamAvatarRating r1 = rating(id1, 3, 1.5, 1.5, false);
        TeamAvatarRating r2 = rating(id2, 4, 2.0, 2.0, false);

        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(av1, av2));
        when(ratingRepository.findByPhaseId(phaseId)).thenReturn(List.of(r1, r2));
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(Collections.emptyList());

        Map<Integer, List<LiveMonitoringService.GroupTableEntry>> result =
                service.getAllGroupTables(phaseId);

        assertThat(result).containsKeys(1, 2);
        assertThat(result.get(1)).hasSize(1);
        assertThat(result.get(1).get(0).avatarId()).isEqualTo(id1);
        assertThat(result.get(2)).hasSize(1);
        assertThat(result.get(2).get(0).avatarId()).isEqualTo(id2);
    }

    @Test
    void getAllGroupTables_returnsEmpty_whenNoAvatars() {
        stubTenantScope();
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(Collections.emptyList());

        Map<Integer, List<LiveMonitoringService.GroupTableEntry>> result =
                service.getAllGroupTables(phaseId);
        assertThat(result).isEmpty();
    }

    // =========================================================================
    // AC3: getLapMatches
    // =========================================================================

    @Test
    void getLapMatches_throwsIllegalArgument_whenLapNumberLessThanOne() {
        // IllegalArgumentException is thrown before tenant scope check — no stubs needed
        assertThatThrownBy(() -> service.getLapMatches(phaseId, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getLapMatches_returnsEmpty_whenNoMatchesInLap() {
        stubTenantScope();
        when(matchRepository.findByPhaseIdAndLapNumber(phaseId, 1))
                .thenReturn(Collections.emptyList());

        List<LiveMonitoringService.LapMatchDetail> result = service.getLapMatches(phaseId, 1);
        assertThat(result).isEmpty();
    }

    @Test
    void getLapMatches_returnsMatchesOrderedByFieldNumber() {
        stubTenantScope();
        UUID avatarId1 = UUID.randomUUID();
        UUID avatarId2 = UUID.randomUUID();
        UUID matchId1  = UUID.randomUUID();
        UUID matchId2  = UUID.randomUUID();

        TeamAvatar av1 = avatar(avatarId1, 1, 1);
        TeamAvatar av2 = avatar(avatarId2, 1, 2);

        Match m1 = buildMatch(matchId1, phaseId, 1, avatarId1, avatarId2, 2, MatchState.ENABLED);
        Match m2 = buildMatch(matchId2, phaseId, 1, avatarId2, avatarId1, 1, MatchState.INPROGRESS);

        when(matchRepository.findByPhaseIdAndLapNumber(phaseId, 1)).thenReturn(List.of(m1, m2));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(av1, av2));
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(Collections.emptyList());
        when(setResultRepository.findByMatchId(matchId1)).thenReturn(Collections.emptyList());
        when(setResultRepository.findByMatchId(matchId2)).thenReturn(Collections.emptyList());

        List<LiveMonitoringService.LapMatchDetail> result = service.getLapMatches(phaseId, 1);

        assertThat(result).hasSize(2);
        // ordered by fieldNumber: m2 (field 1) first, then m1 (field 2)
        assertThat(result.get(0).matchId()).isEqualTo(matchId2);
        assertThat(result.get(0).matchState()).isEqualTo("INPROGRESS");
        assertThat(result.get(1).matchId()).isEqualTo(matchId1);
    }

    // =========================================================================
    // AC4: getCurrentLapSummary
    // =========================================================================

    @Test
    void getCurrentLapSummary_returnsCorrectSummary() {
        Phase phase = activePhase(2);
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(activeTournament()));

        UUID m1Id = UUID.randomUUID();
        UUID m2Id = UUID.randomUUID();
        UUID m3Id = UUID.randomUUID();

        Match m1 = buildMatch(m1Id, phaseId, 1, UUID.randomUUID(), UUID.randomUUID(), 1, MatchState.FINISHED_WINNER1);
        Match m2 = buildMatch(m2Id, phaseId, 2, UUID.randomUUID(), UUID.randomUUID(), 2, MatchState.INPROGRESS);
        Match m3 = buildMatch(m3Id, phaseId, 2, UUID.randomUUID(), UUID.randomUUID(), 3, MatchState.ENABLED);

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(m1, m2, m3));

        LiveMonitoringService.CurrentLapSummary summary = service.getCurrentLapSummary(phaseId);

        assertThat(summary.currentLapNumber()).isEqualTo(2);
        assertThat(summary.totalLapCount()).isEqualTo(2);  // laps 1 and 2
        assertThat(summary.phaseStatus()).isEqualTo("ACTIVE");
        // current lap (lap 2) has m2 (INPROGRESS) + m3 (ENABLED)
        assertThat(summary.matchCountByState()).containsEntry("INPROGRESS", 1L);
        assertThat(summary.matchCountByState()).containsEntry("ENABLED", 1L);
        assertThat(summary.matchCountByState()).doesNotContainKey("FINISHED_WINNER1");
    }

    @Test
    void getCurrentLapSummary_returnsEmptyMatchCounts_whenCurrentLapIsZero() {
        Phase phase = new Phase(phaseId, tenantId, tournamentId, 1,
                "Phase 1", Phase.PhaseStatus.PENDING.name(), 0, LocalDateTime.now());
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(activeTournament()));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(Collections.emptyList());

        LiveMonitoringService.CurrentLapSummary summary = service.getCurrentLapSummary(phaseId);

        assertThat(summary.currentLapNumber()).isEqualTo(0);
        assertThat(summary.matchCountByState()).isEmpty();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Match buildMatch(UUID matchId, UUID phaseId, int lapNumber,
                              UUID avatar1Id, UUID avatar2Id, int fieldNumber,
                              MatchState state) {
        Match m = new Match();
        m.setId(matchId);
        m.setTenantId(tenantId);
        m.setPhaseId(phaseId);
        m.setLapNumber(lapNumber);
        m.setMemberAvatar1Id(avatar1Id);
        m.setMemberAvatar2Id(avatar2Id);
        m.setFieldNumber(fieldNumber);
        m.setState(state.getLegacyCode());
        return m;
    }
}
