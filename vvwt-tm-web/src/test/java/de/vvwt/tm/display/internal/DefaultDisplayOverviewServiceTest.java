// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
    // AC-DISPLAY-PREPARED-PHASE-PREVIEW (E48S17 — Display-Branch Pfad α)
    // =========================================================================

    /**
     * RED-first test (E48S17): no ACTIVE phase, PREPARED phase with slot-optimized matches
     * (lapNumber != null) → service resolves PREPARED phase as preview; preparationPreview=true,
     * phaseStatus="PREPARED".
     *
     * <p>Before E48S17, only PENDING was checked in both the preparationPreview flag and the
     * resolveActiveOrPreviewPhase() fallback loop. After E48S17, PREPARED must also satisfy both
     * checks (Display-Branch-Erweiterung Pfad α).
     *
     * @see DefaultDisplayOverviewService#getPhaseOverview(String)
     * @see <a href="E48S17">E48S17 — AC-IMPL-DISPLAY-PREPARED-PHASE-FALLBACK</a>
     */
    @Test
    void getPhaseOverview_preparedPhaseWithScheduledMatches_returnsPreparationPreview() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Prep Phase", "PREPARED", 1, 0);
        Match scheduledMatch = buildMatch(phaseId, 1); // non-null lapNumber

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(scheduledMatch));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of());
        when(tenantContext.current()).thenReturn(tenantId);

        DisplayPhaseOverviewResponse response = service.getPhaseOverview("valid-token");

        assertThat(response.phaseStatus())
                .as("PREPARED phase must appear as phaseStatus=PREPARED (E48S17)")
                .isEqualTo("PREPARED");
        assertThat(response.preparationPreview())
                .as("PREPARED phase with scheduled matches → preparationPreview=true (E48S17)")
                .isTrue();
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
        assertThat(entry.lapNumber()).isEqualTo(1); // E50S04: lapNumber carried on MatchEntry
        assertThat(entry.teamAName()).isEqualTo("Team Alpha");
        assertThat(entry.teamBName()).isEqualTo("Team Beta");
        assertThat(entry.matchStatus()).isEqualTo("IN_PROGRESS");
        assertThat(entry.setResults()).hasSize(1);
        assertThat(entry.setResults().get(0).setIndex()).isEqualTo(0);
        assertThat(entry.setResults().get(0).scoreA()).isEqualTo(21);
        assertThat(entry.setResults().get(0).scoreB()).isEqualTo(15);
    }

    // =========================================================================
    // AC-MATCHES-BY-LAP-CURRENT-LAP-DEFAULT / E50S04 ALL-LAPS
    // =========================================================================

    /**
     * RED-first test: getMatchesByLap(token, null) → returns ALL laps (filter-removal per E50S04
     * AC-TEST-MULTI-ROUND-ALL-LAPS-VISIBLE-RED). lap field in response = currentLapNumber (marker
     * for active round in FE).
     *
     * <p>RED: current implementation filters to effectiveLap only → returns 1 match. GREEN: filter
     * removed → returns all 3 matches; lap = currentLapNumber.
     *
     * @see DEC-22
     */
    @Test
    void getMatchesByLap_nullLap_returnsAllLaps_multiRound() {
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
        Match matchLap1 = buildMatch(phaseId, 1);
        Match matchLap2 = buildMatch(phaseId, 2);
        Match matchLap3 = buildMatch(phaseId, 3);

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(matchRepository.findByPhaseId(phaseId))
                .thenReturn(List.of(matchLap1, matchLap2, matchLap3));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of());
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of());
        when(setResultRepository.findByMatchId(matchLap1.getId())).thenReturn(List.of());
        when(setResultRepository.findByMatchId(matchLap2.getId())).thenReturn(List.of());
        when(setResultRepository.findByMatchId(matchLap3.getId())).thenReturn(List.of());

        DisplayMatchesResponse response = service.getMatchesByLap("valid-token", null);

        // All 3 laps returned (filter removed)
        assertThat(response.matches()).hasSize(3);
        // lap field = currentLapNumber (active round marker for FE active-highlight)
        assertThat(response.lap()).isEqualTo(2);
    }

    /**
     * RED-first test: each MatchEntry returned by getMatchesByLap (all-laps mode) carries its
     * lapNumber (E50S04 AC-TEST-MULTI-ROUND-ALL-LAPS-VISIBLE-RED). Required for FE grouping.
     *
     * <p>RED: current MatchEntry has no lapNumber field → compile/runtime failure. GREEN: lapNumber
     * added to MatchEntry + populated in service.
     */
    @Test
    void getMatchesByLap_nullLap_matchEntriesCarryLapNumber() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Phase", "ACTIVE", 3, 2);
        Match matchLap1 = buildMatch(phaseId, 1);
        Match matchLap2 = buildMatch(phaseId, 2);

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(matchLap1, matchLap2));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of());
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of());
        when(setResultRepository.findByMatchId(matchLap1.getId())).thenReturn(List.of());
        when(setResultRepository.findByMatchId(matchLap2.getId())).thenReturn(List.of());

        DisplayMatchesResponse response = service.getMatchesByLap("valid-token", null);

        assertThat(response.matches()).hasSize(2);
        // Each MatchEntry must carry its lapNumber for FE grouping
        assertThat(response.matches())
                .extracting(DisplayMatchesResponse.MatchEntry::lapNumber)
                .containsExactlyInAnyOrder(1, 2);
    }

    /**
     * Regression: getMatchesByLap with explicit lap still filters to that lap (E50S04 regression
     * guard). MatchEntry still carries lapNumber.
     */
    @Test
    void getMatchesByLap_explicitLap_stillFiltersToThatLap() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Phase", "ACTIVE", 3, 2);
        Match matchLap1 = buildMatch(phaseId, 1);
        Match matchLap2 = buildMatch(phaseId, 2);
        Match matchLap3 = buildMatch(phaseId, 3);

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(matchRepository.findByPhaseId(phaseId))
                .thenReturn(List.of(matchLap1, matchLap2, matchLap3));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of());
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of());
        when(setResultRepository.findByMatchId(matchLap2.getId())).thenReturn(List.of());

        DisplayMatchesResponse response = service.getMatchesByLap("valid-token", 2);

        assertThat(response.matches()).hasSize(1);
        assertThat(response.lap()).isEqualTo(2);
        assertThat(response.matches().get(0).lapNumber()).isEqualTo(2);
    }

    // =========================================================================
    // AC-GROUP-STANDINGS-D33-SORT
    // =========================================================================

    /**
     * RED-first test: getGroupStandings with multiple teams → sorted per DEC-77 D-3 (points DESC,
     * setQuotient DESC, ballQuotient DESC, groupPosition ASC, withoutAssessment last), position
     * 1-indexed. (AC-GROUP-STANDINGS-D33-SORT)
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
        TeamAvatar ta1 = buildAvatarWithGroupPos(avatarId1, phaseId, 1, 1, teamId1);
        TeamAvatar ta2 = buildAvatarWithGroupPos(avatarId2, phaseId, 1, 2, teamId2);
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
        when(teamAvatarRatingRepository.findByPhaseId(phaseId))
                .thenReturn(List.of(rating1, rating2));
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
    // AC1 (E66S04): no-divergence — groupPosition tie-break matches PlacementComparator
    // =========================================================================

    /**
     * RED-first test (E66S04 AC1, DEC-77 D-3/D-6): when two teams are tied on all quotients, the
     * team with the lower groupPosition ranks first in Display standings — matching the DEC-77 D-3
     * placement comparator (no-divergence property).
     *
     * <p>This test is RED with the pre-E66S04 code: {@code TeamAvatarRating.compareTo()} does not
     * include the {@code groupPosition} tie-break (it stops at ballQuotient DESC and returns 0),
     * leaving the ordering undefined for equal-score teams. After E66S04, the Display standings use
     * {@link de.vvwt.tm.tournament.PlacementComparator#forRatings(java.util.Map)}, which always
     * breaks ties deterministically by {@code groupPosition} ASC.
     *
     * @see de.vvwt.tm.tournament.PlacementComparator
     * @see <a href="DEC-77">DEC-77 D-3 — placement comparator (groupPosition tie-break)</a>
     * @see <a href="DEC-77">DEC-77 D-6 — Display standings use the shared comparator</a>
     * @see <a href="E66S04">E66S04 AC1</a>
     */
    @Test
    void getGroupStandings_tiedOnAllQuotients_groupPositionDeterminesOrder_noDivergence() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID avatarId1 = UUID.randomUUID(); // groupPosition=1 (better prior seeding)
        UUID avatarId2 = UUID.randomUUID(); // groupPosition=2 (worse prior seeding)
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Phase", "ACTIVE", 1, 1);
        // avatarId1 has groupPosition=1; avatarId2 has groupPosition=2
        TeamAvatar ta1 = buildAvatarWithGroupPos(avatarId1, phaseId, 1, 1, teamId1);
        TeamAvatar ta2 = buildAvatarWithGroupPos(avatarId2, phaseId, 1, 2, teamId2);
        Team team1 = buildTeam(teamId1, tournamentId, "Team Pos1");
        Team team2 = buildTeam(teamId2, tournamentId, "Team Pos2");

        // Identical ratings — tied on all quotients; only groupPosition differs
        TeamAvatarRating rating1 =
                buildRating(avatarId1, tenantId, 6, 3, 1, 50, 30, false, 3.0, 1.667);
        TeamAvatarRating rating2 =
                buildRating(avatarId2, tenantId, 6, 3, 1, 50, 30, false, 3.0, 1.667);

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(ta1, ta2));
        when(teamAvatarRatingRepository.findByPhaseId(phaseId))
                .thenReturn(List.of(rating1, rating2));
        when(teamRepository.findById(teamId1)).thenReturn(Optional.of(team1));
        when(teamRepository.findById(teamId2)).thenReturn(Optional.of(team2));

        DisplayGroupStandingsResponse response = service.getGroupStandings("valid-token");

        assertThat(response.groups()).hasSize(1);
        List<DisplayGroupStandingsResponse.TeamRanking> rankings =
                response.groups().get(0).rankings();
        assertThat(rankings).hasSize(2);
        // groupPosition 1 (ta1 / "Team Pos1") must rank at position 1 per DEC-77 D-3
        assertThat(rankings.get(0).teamName())
                .as(
                        "AC1 no-divergence: tied-on-quotients teams must be ordered by groupPosition"
                                + " ASC (DEC-77 D-3 4th tie-break); lower groupPosition=1 ranks"
                                + " first")
                .isEqualTo("Team Pos1");
        assertThat(rankings.get(0).position()).isEqualTo(1);
        assertThat(rankings.get(1).teamName()).isEqualTo("Team Pos2");
        assertThat(rankings.get(1).position()).isEqualTo(2);
    }

    /**
     * RED-first test (E66S04 AC1/AC4, DEC-77 D-3): a team with {@code withoutAssessment=true} and
     * any score ranks last in Display standings — same as PlacementComparator behaviour
     * (no-divergence property). Tie between two withoutAssessment teams is broken by groupPosition
     * ASC (AC4 determinism).
     *
     * @see <a href="DEC-77">DEC-77 D-3 — withoutAssessment last</a>
     * @see <a href="E66S04">E66S04 AC1/AC4</a>
     */
    @Test
    void getGroupStandings_withoutAssessment_ranksLast_sameAsPlacementComparator() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID avatarIdAssessed = UUID.randomUUID();
        UUID avatarIdUnrated = UUID.randomUUID();
        UUID teamIdAssessed = UUID.randomUUID();
        UUID teamIdUnrated = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 2);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Phase", "ACTIVE", 1, 1);
        TeamAvatar taAssessed = buildAvatarWithGroupPos(avatarIdAssessed, phaseId, 1, 1, teamIdAssessed);
        TeamAvatar taUnrated = buildAvatarWithGroupPos(avatarIdUnrated, phaseId, 1, 2, teamIdUnrated);
        Team teamAssessed = buildTeam(teamIdAssessed, tournamentId, "Assessed Team");
        Team teamUnrated = buildTeam(teamIdUnrated, tournamentId, "Unrated Team");

        // assessed team has 0 points but withoutAssessment=false → ranks first
        TeamAvatarRating ratingAssessed =
                buildRating(avatarIdAssessed, tenantId, 0, 0, 0, 0, 0, false, 0.0, 0.0);
        // unrated team has 999 points but withoutAssessment=true → ranks last
        TeamAvatarRating ratingUnrated =
                buildRating(avatarIdUnrated, tenantId, 999, 9, 0, 99, 1, true, 9.9, 99.0);

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(teamAvatarRepository.findByPhaseId(phaseId))
                .thenReturn(List.of(taAssessed, taUnrated));
        when(teamAvatarRatingRepository.findByPhaseId(phaseId))
                .thenReturn(List.of(ratingAssessed, ratingUnrated));
        when(teamRepository.findById(teamIdAssessed)).thenReturn(Optional.of(teamAssessed));
        when(teamRepository.findById(teamIdUnrated)).thenReturn(Optional.of(teamUnrated));

        DisplayGroupStandingsResponse response = service.getGroupStandings("valid-token");

        assertThat(response.groups()).hasSize(1);
        List<DisplayGroupStandingsResponse.TeamRanking> rankings =
                response.groups().get(0).rankings();
        assertThat(rankings).hasSize(2);
        assertThat(rankings.get(0).teamName())
                .as("withoutAssessment=true team must rank last regardless of raw scores")
                .isEqualTo("Assessed Team");
        assertThat(rankings.get(0).position()).isEqualTo(1);
        assertThat(rankings.get(1).teamName()).isEqualTo("Unrated Team");
        assertThat(rankings.get(1).position()).isEqualTo(2);
    }

    // =========================================================================
    // AC-GROUP-STANDINGS-ZERO-RATING-FALLBACK (updated for E66S04 — no rating row = unrated/last)
    // =========================================================================

    /**
     * Test: no TeamAvatarRating row for avatar → team treated as unrated (withoutAssessment-
     * equivalent per PlacementComparator), DTO populates with zero values without NPE.
     * (AC-GROUP-STANDINGS-ZERO-RATING-FALLBACK adapted for E66S04)
     *
     * <p>With PlacementComparator, a team with no rating row is treated the same as
     * withoutAssessment=true (ranks last). The DTO output fields default to zero for missing
     * ratings.
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
        TeamAvatar ta = buildAvatarWithGroupPos(avatarId, phaseId, 1, 1, teamId);
        Team team = buildTeam(teamId, tournamentId, "Team Zero");

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(ta));
        when(teamAvatarRatingRepository.findByPhaseId(phaseId))
                .thenReturn(List.of()); // no rating for this avatar
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

    /**
     * Builds a {@link TeamAvatar} with an explicit {@code groupPosition} (E66S04 — needed for
     * AC1 no-divergence tests where groupPosition tie-break is verified).
     */
    private TeamAvatar buildAvatarWithGroupPos(
            UUID id, UUID phaseId, int groupNumber, int groupPosition, UUID teamId) {
        TeamAvatar ta = new TeamAvatar();
        ta.setId(id);
        ta.setPhaseId(phaseId);
        ta.setGroupNumber(groupNumber);
        ta.setGroupPosition(groupPosition);
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

    // =========================================================================
    // AC-TEST-DISPLAY-OVERVIEW-FIELDNUMBER-IS-1-BASED-RED (E53S09 / DEC-60 D-1)
    //
    // After E53S09: L2 emits 1-based fieldNumber; DefaultDisplayOverviewService passes through
    // m.getFieldNumber() unchanged (no arithmetic at line 251). This test verifies that the
    // returned MatchEntry.fieldNumber() is 1-based (>= 1) for a fixture with 1-based storage.
    //
    // Previously (pre-E53S09): L2 emitted 0-based fields; service passed 0 through → Bug 1.
    // Now (post-E53S09): L2 emits 1-based; service passes 1 through → correct venue signage.
    // =========================================================================

    /**
     * AC-TEST-DISPLAY-OVERVIEW-FIELDNUMBER-IS-1-BASED-RED (DEC-60 D-1 / E53S09):
     *
     * <p>For a tournament with matches stored with 1-based fieldNumber (as emitted by L2 after
     * E53S09), getMatchesByLap must return MatchEntries with fieldNumber >= 1 (no "Feld 0").
     *
     * <p>Regression guard: DefaultDisplayOverviewService.java:251 passes {@code m.getFieldNumber()}
     * through without arithmetic. With 1-based storage this is correct. With 0-based storage
     * (before E53S09 migration) this would produce "Feld 0" Bug 1.
     */
    @Test
    void getMatchesByLap_withOneBasedStoredFieldNumber_entryFieldNumberIsNotZero() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID avatarId1 = UUID.randomUUID();
        UUID avatarId2 = UUID.randomUUID();
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        Device device = buildDisplayDevice(tenantId);
        Tournament tournament = buildTournament(tournamentId, "ACTIVE", 3);
        Phase phase = buildPhase(phaseId, tenantId, tournamentId, "Vorrunde", "ACTIVE", 1, 1);

        // Match with 1-based fieldNumber=2 (as L2 emits post-E53S09)
        Match match = new Match();
        match.setId(matchId);
        match.setPhaseId(phaseId);
        match.setLapNumber(1);
        match.setMatchState(MatchState.OPEN);
        match.setMemberAvatar1Id(avatarId1);
        match.setMemberAvatar2Id(avatarId2);
        match.setFieldNumber(2); // 1-based: field 2 per DEC-60 D-1 / E53S09

        TeamAvatar ta1 = buildAvatarWithTeam(avatarId1, phaseId, tenantId, 1, teamId1);
        TeamAvatar ta2 = buildAvatarWithTeam(avatarId2, phaseId, tenantId, 1, teamId2);
        Team team1 = buildTeam(teamId1, tournamentId, "Rote Haie");
        Team team2 = buildTeam(teamId2, tournamentId, "Blaue Wölfe");

        when(deviceRepository.findByDeviceToken("valid-token")).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(ta1, ta2));
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of(team1, team2));
        when(setResultRepository.findByMatchId(matchId)).thenReturn(List.of());

        DisplayMatchesResponse response = service.getMatchesByLap("valid-token", 1);

        assertThat(response.matches()).hasSize(1);
        DisplayMatchesResponse.MatchEntry entry = response.matches().get(0);
        assertThat(entry.fieldNumber())
                .as(
                        "AC-TEST-DISPLAY-OVERVIEW-FIELDNUMBER-IS-1-BASED-RED:"
                            + " MatchEntry.fieldNumber must be 1-based (>=1) after E53S09; stored"
                            + " fieldNumber=2 must pass through as 2 (DEC-60 D-1 / E53S09"
                            + " regression guard)")
                .isGreaterThanOrEqualTo(1);
        assertThat(entry.fieldNumber())
                .as("fieldNumber must equal stored value (no arithmetic shift in service)")
                .isEqualTo(2);
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
