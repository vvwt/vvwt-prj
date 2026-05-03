package de.vvwt.tm.display.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.vvwt.tm.display.DisplayGroupStandingsResponse;
import de.vvwt.tm.display.DisplayMatchesResponse;
import de.vvwt.tm.display.DisplayPhaseOverviewResponse;
import de.vvwt.tm.display.NoActivePhaseException;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.SetResult;
import de.vvwt.tm.tournament.SetResultRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.UnauthorizedException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TDD white-box test for {@link DefaultDisplayOverviewService} (same-package access per DEC-36).
 *
 * <p>All 25 legacy display tests are Snapshot-Driven per E25-AUDIT-DEC41-TEST-CLASSIFICATION
 * (commit {@code 1aded97}); none are reused. All tests here are RED-first per DEC-22 Iron Law +
 * DEC-41 §3 hierarchy item (1).
 *
 * @see DEC-22
 * @see DEC-36
 * @see DEC-41
 */
@ExtendWith(MockitoExtension.class)
class DefaultDisplayOverviewServiceTest {

    @Mock private DeviceRepository deviceRepository;

    @Mock private TournamentRepository tournamentRepository;

    @Mock private PhaseRepository phaseRepository;

    @Mock private MatchRepository matchRepository;

    @Mock private TeamAvatarRepository teamAvatarRepository;

    @Mock private TeamAvatarRatingRepository teamAvatarRatingRepository;

    @Mock private TeamRepository teamRepository;

    @Mock private SetResultRepository setResultRepository;

    @Mock private TenantContext tenantContext;

    private DefaultDisplayOverviewService service;

    @BeforeEach
    void setUp() {
        service =
                new DefaultDisplayOverviewService(
                        deviceRepository,
                        tournamentRepository,
                        phaseRepository,
                        matchRepository,
                        teamAvatarRepository,
                        teamAvatarRatingRepository,
                        teamRepository,
                        setResultRepository,
                        tenantContext);
    }

    // =========================================================================
    // AC-VALIDATE-TOKEN-BRANCH-1-MISSING
    // =========================================================================

    /**
     * RED-first test: null deviceToken → UnauthorizedException("Missing or empty device token").
     * Reproduces legacy line 395 branch (AC-VALIDATE-TOKEN-BRANCH-1-MISSING).
     */
    @Test
    void getPhaseOverview_nullToken_throwsUnauthorized() {
        assertThatThrownBy(() -> service.getPhaseOverview(null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Missing or empty device token");
    }

    /**
     * RED-first test: blank deviceToken → UnauthorizedException
     * (AC-VALIDATE-TOKEN-BRANCH-1-MISSING).
     */
    @Test
    void getPhaseOverview_blankToken_throwsUnauthorized() {
        assertThatThrownBy(() -> service.getPhaseOverview("   "))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Missing or empty device token");
    }

    // =========================================================================
    // AC-VALIDATE-TOKEN-BRANCH-2-UNKNOWN-LAMBDA
    // =========================================================================

    /**
     * RED-first test: token not found in deviceRepository via lambda inside orElseThrow →
     * UnauthorizedException("Invalid or unknown device token"). Reproduces legacy line 402 branch
     * (AC-VALIDATE-TOKEN-BRANCH-2-UNKNOWN-LAMBDA).
     */
    @Test
    void getPhaseOverview_unknownToken_throwsUnauthorized() {
        when(deviceRepository.findByDeviceToken("unknown-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPhaseOverview("unknown-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or unknown device token");
    }

    // =========================================================================
    // AC-VALIDATE-TOKEN-BRANCH-3-WRONG-TYPE
    // =========================================================================

    /**
     * RED-first test: device has wrong type (SCORING_TABLET) → UnauthorizedException("Device token
     * is not for a display device (type=...)"). Reproduces legacy line 405 branch
     * (AC-VALIDATE-TOKEN-BRANCH-3-WRONG-TYPE).
     */
    @Test
    void getPhaseOverview_wrongDeviceType_throwsUnauthorized() {
        Device device = buildDevice("SCORING_TABLET", "REGISTERED");
        when(deviceRepository.findByDeviceToken("wrong-type-token"))
                .thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.getPhaseOverview("wrong-type-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining(
                        "Device token is not for a display device (type=SCORING_TABLET)");
    }

    // =========================================================================
    // AC-VALIDATE-TOKEN-BRANCH-4-INACTIVE-STATUS
    // =========================================================================

    /**
     * RED-first test: DISPLAY device with DISCONNECTED status → UnauthorizedException("Display
     * device is not active (status=...)"). Reproduces legacy line 413 branch
     * (AC-VALIDATE-TOKEN-BRANCH-4-INACTIVE-STATUS).
     */
    @Test
    void getPhaseOverview_inactiveDevice_throwsUnauthorized() {
        Device device = buildDevice("DISPLAY", "DISCONNECTED");
        when(deviceRepository.findByDeviceToken("inactive-token")).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.getPhaseOverview("inactive-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Display device is not active (status=DISCONNECTED)");
    }

    // =========================================================================
    // AC-PHASE-OVERVIEW-ACTIVE
    // =========================================================================

    /**
     * RED-first test: valid DISPLAY token + ACTIVE phase → returns DisplayPhaseOverviewResponse
     * with correct phaseId, tenantId (O-9), phaseName, phaseStatus="ACTIVE", lapCount, currentLap,
     * fieldCount, preparationPreview=false, groups list. (AC-PHASE-OVERVIEW-ACTIVE)
     */
    @Test
    void getPhaseOverview_activePhase_returnsCorrectDto() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 3);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Test Phase", "ACTIVE", 2, 1);
        TeamAvatar avatar1 = buildAvatar(UUID.randomUUID(), phaseId, tenantId, 1);
        TeamAvatar avatar2 = buildAvatar(UUID.randomUUID(), phaseId, tenantId, 1);
        Match match1 = buildMatch(phaseId, 1);
        Match match2 = buildMatch(phaseId, 2);

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match1, match2));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(avatar1, avatar2));
        when(tenantContext.current()).thenReturn(tenantId);

        DisplayPhaseOverviewResponse response = service.getPhaseOverview("valid-token");

        assertThat(response.phaseId()).isEqualTo(phaseId);
        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.phaseName()).isEqualTo("Test Phase");
        assertThat(response.phaseStatus()).isEqualTo("ACTIVE");
        assertThat(response.lapCount()).isEqualTo(2); // 2 distinct lap numbers
        assertThat(response.currentLap()).isEqualTo(1);
        assertThat(response.fieldCount()).isEqualTo(3);
        assertThat(response.preparationPreview()).isFalse();
        assertThat(response.groups()).hasSize(1); // both avatars in group 1
        assertThat(response.groups().get(0).groupNumber()).isEqualTo(1);
        assertThat(response.groups().get(0).teamCount()).isEqualTo(2);
    }

    // =========================================================================
    // AC-PHASE-OVERVIEW-PREPARATION-PREVIEW
    // =========================================================================

    /**
     * RED-first test: no ACTIVE phase, PENDING phase with slot-optimized matches (lapNumber !=
     * null) → preparationPreview=true, phaseStatus="PENDING".
     * (AC-PHASE-OVERVIEW-PREPARATION-PREVIEW)
     */
    @Test
    void getPhaseOverview_pendingPhaseWithScheduledMatches_returnsPreparationPreview() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Prep Phase", "PENDING", 1, 0);
        Match scheduledMatch = buildMatch(phaseId, 1); // non-null lapNumber

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(scheduledMatch));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of());

        DisplayPhaseOverviewResponse response = service.getPhaseOverview("valid-token");

        assertThat(response.phaseStatus()).isEqualTo("PENDING");
        assertThat(response.preparationPreview()).isTrue();
    }

    // =========================================================================
    // AC-PHASE-OVERVIEW-NO-ACTIVE-PHASE
    // =========================================================================

    /**
     * RED-first test: no ACTIVE phase, no PENDING phase with scheduled matches →
     * NoActivePhaseException. (AC-PHASE-OVERVIEW-NO-ACTIVE-PHASE)
     */
    @Test
    void getPhaseOverview_noActiveOrPreparationPhase_throwsNoActivePhase() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase pendingPhase =
                buildPhase(phaseId, tenantId, tournamentId, "Empty Pending", "PENDING", 0, 0);

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(pendingPhase));
        when(matchRepository.findByPhaseId(phaseId))
                .thenReturn(List.of(buildMatchNoLap(phaseId))); // match with null lapNumber

        assertThatThrownBy(() -> service.getPhaseOverview("valid-token"))
                .isInstanceOf(NoActivePhaseException.class);
    }

    // =========================================================================
    // AC-MATCHES-BY-LAP-EXPLICIT-LAP
    // =========================================================================

    /**
     * RED-first test: getMatchesByLap(token, lap=1) → returns matches filtered by lap=1, team names
     * resolved, set results in play order, MatchState mapped to display-status.
     * (AC-MATCHES-BY-LAP-EXPLICIT-LAP)
     */
    @Test
    void getMatchesByLap_explicitLap_returnsFilteredMatches() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID avatarId1 = UUID.randomUUID();
        UUID avatarId2 = UUID.randomUUID();
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Phase", "ACTIVE", 2, 1);
        Match matchLap1 =
                buildMatchWithAvatars(
                        matchId, phaseId, 1, avatarId1, avatarId2, MatchState.INPROGRESS);
        Match matchLap2 = buildMatch(phaseId, 2); // different lap, filtered out
        TeamAvatar ta1 = buildAvatarWithTeam(avatarId1, phaseId, tenantId, 1, teamId1);
        TeamAvatar ta2 = buildAvatarWithTeam(avatarId2, phaseId, tenantId, 1, teamId2);
        Team team1 = buildTeam(teamId1, tournamentId, "Team Alpha");
        Team team2 = buildTeam(teamId2, tournamentId, "Team Beta");
        SetResult sr1 = buildSetResult(matchId, 0, 21, 15);

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(matchLap1, matchLap2));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(ta1, ta2));
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of(team1, team2));
        when(setResultRepository.findByMatchId(matchId)).thenReturn(List.of(sr1));

        DisplayMatchesResponse response = service.getMatchesByLap("valid-token", 1);

        assertThat(response.phaseId()).isEqualTo(phaseId);
        assertThat(response.lap()).isEqualTo(1);
        assertThat(response.matches()).hasSize(1);
        DisplayMatchesResponse.MatchEntry entry = response.matches().get(0);
        assertThat(entry.matchId()).isEqualTo(matchId);
        assertThat(entry.teamAName()).isEqualTo("Team Alpha");
        assertThat(entry.teamBName()).isEqualTo("Team Beta");
        assertThat(entry.matchStatus()).isEqualTo("IN_PROGRESS");
        assertThat(entry.setResults()).hasSize(1);
        assertThat(entry.setResults().get(0).setIndex()).isEqualTo(0);
        assertThat(entry.setResults().get(0).scoreA()).isEqualTo(21);
        assertThat(entry.setResults().get(0).scoreB()).isEqualTo(15);
    }

    // =========================================================================
    // AC-MATCHES-BY-LAP-CURRENT-LAP-DEFAULT
    // =========================================================================

    /**
     * RED-first test: getMatchesByLap(token, null) → uses phase.currentLapNumber as effective lap.
     * (AC-MATCHES-BY-LAP-CURRENT-LAP-DEFAULT)
     */
    @Test
    void getMatchesByLap_nullLap_usesCurrentLapNumber() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase phase =
                buildPhase(
                        phaseId,
                        tenantId,
                        tournamentId,
                        "Phase",
                        "ACTIVE",
                        3,
                        2); // currentLapNumber=2
        Match matchLap2 = buildMatch(phaseId, 2);
        Match matchLap3 = buildMatch(phaseId, 3);

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(matchLap2, matchLap3));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of());
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of());
        when(setResultRepository.findByMatchId(matchLap2.getId())).thenReturn(List.of());

        DisplayMatchesResponse response = service.getMatchesByLap("valid-token", null);

        assertThat(response.lap()).isEqualTo(2); // phase.currentLapNumber
        assertThat(response.matches()).hasSize(1); // only lap=2 match
    }

    // =========================================================================
    // AC-GROUP-STANDINGS-D33-SORT
    // =========================================================================

    /**
     * RED-first test: getGroupStandings with multiple teams → sorted per D-33 (points DESC,
     * setQuotient DESC, ballQuotient DESC, withoutAssessment last), position 1-indexed.
     * (AC-GROUP-STANDINGS-D33-SORT)
     */
    @Test
    void getGroupStandings_multipleTeams_sortedByD33Criteria() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID avatarId1 = UUID.randomUUID();
        UUID avatarId2 = UUID.randomUUID();
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Phase", "ACTIVE", 1, 1);
        TeamAvatar ta1 = buildAvatarWithTeam(avatarId1, phaseId, tenantId, 1, teamId1);
        TeamAvatar ta2 = buildAvatarWithTeam(avatarId2, phaseId, tenantId, 1, teamId2);
        Team team1 = buildTeam(teamId1, tournamentId, "Team Low");
        Team team2 = buildTeam(teamId2, tournamentId, "Team High");

        // team2 has more points → should rank first (position=1)
        TeamAvatarRating rating1 =
                buildRating(avatarId1, tenantId, 2, 1, 2, 30, 40, false, 0.5, 0.75);
        TeamAvatarRating rating2 =
                buildRating(avatarId2, tenantId, 4, 2, 1, 50, 20, false, 2.0, 2.5);

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(ta1, ta2));
        when(teamAvatarRatingRepository.findById(avatarId1)).thenReturn(Optional.of(rating1));
        when(teamAvatarRatingRepository.findById(avatarId2)).thenReturn(Optional.of(rating2));
        when(teamRepository.findById(teamId1)).thenReturn(Optional.of(team1));
        when(teamRepository.findById(teamId2)).thenReturn(Optional.of(team2));

        DisplayGroupStandingsResponse response = service.getGroupStandings("valid-token");

        assertThat(response.phaseId()).isEqualTo(phaseId);
        assertThat(response.groups()).hasSize(1);
        List<DisplayGroupStandingsResponse.TeamRanking> rankings =
                response.groups().get(0).rankings();
        assertThat(rankings).hasSize(2);
        // team2 (higher points) should be position 1
        assertThat(rankings.get(0).teamName()).isEqualTo("Team High");
        assertThat(rankings.get(0).position()).isEqualTo(1);
        assertThat(rankings.get(1).teamName()).isEqualTo("Team Low");
        assertThat(rankings.get(1).position()).isEqualTo(2);
    }

    // =========================================================================
    // AC-GROUP-STANDINGS-ZERO-RATING-FALLBACK
    // =========================================================================

    /**
     * RED-first test: no TeamAvatarRating row for avatar → service uses zero-score default, DTO
     * populates without NPE. (AC-GROUP-STANDINGS-ZERO-RATING-FALLBACK)
     */
    @Test
    void getGroupStandings_noRatingRow_usesZeroDefault() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID avatarId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Phase", "ACTIVE", 1, 1);
        TeamAvatar ta = buildAvatarWithTeam(avatarId, phaseId, tenantId, 1, teamId);
        Team team = buildTeam(teamId, tournamentId, "Team Zero");

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(ta));
        when(teamAvatarRatingRepository.findById(avatarId))
                .thenReturn(Optional.empty()); // no rating
        when(teamRepository.findById(teamId)).thenReturn(Optional.of(team));

        DisplayGroupStandingsResponse response = service.getGroupStandings("valid-token");

        assertThat(response.groups()).hasSize(1);
        DisplayGroupStandingsResponse.TeamRanking ranking =
                response.groups().get(0).rankings().get(0);
        assertThat(ranking.teamName()).isEqualTo("Team Zero");
        assertThat(ranking.points()).isEqualTo(0);
        assertThat(ranking.setsWon()).isEqualTo(0);
        assertThat(ranking.setsLost()).isEqualTo(0);
        assertThat(ranking.ballsWon()).isEqualTo(0);
        assertThat(ranking.ballsLost()).isEqualTo(0);
        assertThat(ranking.position()).isEqualTo(1);
    }

    // =========================================================================
    // Test fixture helpers
    // =========================================================================

    private Device buildDevice(String deviceType, String status) {
        Device d = new Device();
        d.setDeviceType(deviceType);
        d.setStatus(status);
        return d;
    }

    private Device buildDisplayDevice(UUID tenantId) {
        Device d = new Device();
        d.setDeviceType("DISPLAY");
        d.setStatus("REGISTERED");
        return d;
    }

    private Tournament buildTournament(UUID id, String status, int fieldCount) {
        Tournament t = new Tournament();
        t.setId(id);
        t.setStatus(status);
        t.setFieldCount(fieldCount);
        return t;
    }

    private Phase buildPhase(
            UUID id,
            UUID tenantId,
            UUID tournamentId,
            String description,
            String status,
            int lapCount,
            int currentLap) {
        Phase p = new Phase();
        p.setId(id);
        p.setTournamentId(tournamentId);
        p.setDescription(description);
        p.setStatus(status);
        p.setCurrentLapNumber(currentLap);
        return p;
    }

    private TeamAvatar buildAvatar(UUID id, UUID phaseId, UUID tenantId, int groupNumber) {
        TeamAvatar ta = new TeamAvatar();
        ta.setId(id);
        ta.setPhaseId(phaseId);
        ta.setGroupNumber(groupNumber);
        ta.setTeamId(UUID.randomUUID());
        return ta;
    }

    private TeamAvatar buildAvatarWithTeam(
            UUID id, UUID phaseId, UUID tenantId, int groupNumber, UUID teamId) {
        TeamAvatar ta = new TeamAvatar();
        ta.setId(id);
        ta.setPhaseId(phaseId);
        ta.setGroupNumber(groupNumber);
        ta.setTeamId(teamId);
        return ta;
    }

    private Match buildMatch(UUID phaseId, int lapNumber) {
        Match m = new Match();
        m.setId(UUID.randomUUID());
        m.setPhaseId(phaseId);
        m.setLapNumber(lapNumber);
        m.setMatchState(MatchState.OPEN);
        m.setMemberAvatar1Id(UUID.randomUUID());
        m.setMemberAvatar2Id(UUID.randomUUID());
        return m;
    }

    private Match buildMatchNoLap(UUID phaseId) {
        Match m = new Match();
        m.setId(UUID.randomUUID());
        m.setPhaseId(phaseId);
        m.setLapNumber(null);
        m.setMatchState(MatchState.OPEN);
        m.setMemberAvatar1Id(UUID.randomUUID());
        m.setMemberAvatar2Id(UUID.randomUUID());
        return m;
    }

    private Match buildMatchWithAvatars(
            UUID id,
            UUID phaseId,
            int lapNumber,
            UUID avatarId1,
            UUID avatarId2,
            MatchState state) {
        Match m = new Match();
        m.setId(id);
        m.setPhaseId(phaseId);
        m.setLapNumber(lapNumber);
        m.setMatchState(state);
        m.setMemberAvatar1Id(avatarId1);
        m.setMemberAvatar2Id(avatarId2);
        m.setFieldNumber(1);
        return m;
    }

    private Team buildTeam(UUID id, UUID tournamentId, String description) {
        Team t = new Team();
        t.setId(id);
        t.setTournamentId(tournamentId);
        t.setDescription(description);
        return t;
    }

    private SetResult buildSetResult(UUID matchId, int setIndex, int team1Points, int team2Points) {
        SetResult sr = new SetResult();
        sr.setMatchId(matchId);
        sr.setSetIndex(setIndex);
        sr.setTeam1Points(team1Points);
        sr.setTeam2Points(team2Points);
        return sr;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private TeamAvatarRating buildRating(
            UUID avatarId,
            UUID tenantId,
            int points,
            int setsWon,
            int setsLost,
            int ballsWon,
            int ballsLost,
            boolean withoutAssessment,
            double setQuotient,
            double ballQuotient) {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setAvatarId(avatarId);
        r.setPoints(points);
        r.setSetsWon(setsWon);
        r.setSetsLost(setsLost);
        r.setBallsWon(ballsWon);
        r.setBallsLost(ballsLost);
        r.setWithoutAssessment(withoutAssessment);
        r.setSetQuotient(setQuotient);
        r.setBallQuotient(ballQuotient);
        return r;
    }
}
