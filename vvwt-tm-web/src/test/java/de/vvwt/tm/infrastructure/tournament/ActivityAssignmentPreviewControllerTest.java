package de.vvwt.tm.infrastructure.tournament;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.activity.ActivityAssignmentResult;
import de.vvwt.tm.tournament.activity.ActivityAssignmentService;
import de.vvwt.tm.tournament.activity.ActivityType;
import de.vvwt.tm.tournament.activity.ActivityTypeService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice test for {@link ActivityAssignmentPreviewController} (E20S02, AC2 — Approach C Hybrid).
 *
 * <h2>RED-capture (AC1 anti-spoof)</h2>
 *
 * <p>This test class was committed RED before {@link ActivityAssignmentPreviewController} had any
 * handler methods (skeleton class only). The first {@code @Test} method (AC9: unauthenticated→401)
 * would pass against the security layer, but all handler-method tests fail against the skeleton —
 * satisfying AC1b behavioural-RED requirement.
 *
 * <h2>Coverage (AC2)</h2>
 *
 * <ul>
 *   <li>GET happy path: empty preview when no phase
 *   <li>GET happy path: structured assignments returned
 *   <li>GET security: unauthenticated→401
 *   <li>GET error: unknown tournament→404
 * </ul>
 *
 * @see ActivityAssignmentPreviewController
 */
@WebMvcTest(ActivityAssignmentPreviewController.class)
@DisplayName("ActivityAssignmentPreviewController slice tests — E20S02 AC2")
class ActivityAssignmentPreviewControllerTest {

    @Autowired private WebApplicationContext context;

    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private TournamentRepository tournamentRepository;
    @MockitoBean private PhaseRepository phaseRepository;
    @MockitoBean private MatchRepository matchRepository;
    @MockitoBean private TeamAvatarRepository teamAvatarRepository;
    @MockitoBean private TeamRepository teamRepository;
    @MockitoBean private ActivityTypeService activityTypeService;
    @MockitoBean private ActivityAssignmentService activityAssignmentService;

    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    private final UUID TOURNAMENT_ID = UUID.randomUUID();
    private final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextSliceTestSupport.configureMock(tenantContext, TENANT_ID);
        when(tenantRegistryPort.getDefault()).thenReturn(TENANT_ID);
        when(tenantContext.bind(any())).thenReturn(() -> {});
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();
    }

    // =========================================================================
    // AC9 — Security: unauthenticated requests return 401
    // =========================================================================

    @Test
    @DisplayName("AC9: unauthenticated GET returns 401")
    void unauthenticatedGetReturns401() throws Exception {
        mockMvc.perform(
                        get("/api/tournaments/{id}/activity-assignments", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // AC2 — GET /api/tournaments/{id}/activity-assignments → 200
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("AC2: GET returns empty preview when no phase available")
    void getReturnsEmptyPreviewWhenNoPhase() throws Exception {
        when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(
                        Optional.of(
                                new de.vvwt.tm.tournament.Tournament(
                                        TOURNAMENT_ID,
                                        TENANT_ID,
                                        "Test",
                                        "BEST_OF_3",
                                        "setPoints",
                                        "standard",
                                        "roundRobin",
                                        "DRAFT",
                                        LocalDateTime.now())));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/tournaments/{id}/activity-assignments", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phaseId").doesNotExist())
                .andExpect(jsonPath("$.assignments").isArray())
                .andExpect(jsonPath("$.assignments.length()").value(0))
                .andExpect(jsonPath("$.unassigned").isArray())
                .andExpect(jsonPath("$.unassigned.length()").value(0));
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: GET returns 404 when tournament is unknown")
    void getReturns404WhenTournamentUnknown() throws Exception {
        when(tournamentRepository.findById(any()))
                .thenThrow(new NoSuchElementException("Tournament not found"));

        mockMvc.perform(get("/api/tournaments/{id}/activity-assignments", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: GET returns structured preview with phase and assignments")
    void getReturnsStructuredPreviewWithAssignments() throws Exception {
        UUID phaseId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID avatarId = UUID.randomUUID();
        UUID activityTypeId = UUID.randomUUID();

        when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(
                        Optional.of(
                                new de.vvwt.tm.tournament.Tournament(
                                        TOURNAMENT_ID,
                                        TENANT_ID,
                                        "Test",
                                        "BEST_OF_3",
                                        "setPoints",
                                        "standard",
                                        "roundRobin",
                                        "DRAFT",
                                        LocalDateTime.now())));

        Phase phase =
                new Phase(
                        phaseId,
                        TENANT_ID,
                        TOURNAMENT_ID,
                        1,
                        "Phase 1",
                        Phase.PhaseStatus.ACTIVE.name(),
                        0,
                        LocalDateTime.now());
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase));

        // 1 match in lap 1 (avatarId plays, no referee)
        Match match =
                new Match(
                        UUID.randomUUID(),
                        TENANT_ID,
                        TOURNAMENT_ID,
                        phaseId,
                        avatarId,
                        UUID.randomUUID(),
                        0,
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match));

        TeamAvatar avatar =
                new TeamAvatar(
                        avatarId,
                        TENANT_ID,
                        TOURNAMENT_ID,
                        phaseId,
                        1,
                        1,
                        teamId,
                        null,
                        LocalDateTime.now());
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(avatar));

        ActivityType at =
                new ActivityType(
                        activityTypeId,
                        TOURNAMENT_ID,
                        "Photo",
                        "FIRST_FREE_ROUND",
                        null,
                        1,
                        TENANT_ID);
        when(activityTypeService.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(at));

        Team team =
                new Team(
                        teamId,
                        TENANT_ID,
                        TOURNAMENT_ID,
                        1,
                        "Team A",
                        true,
                        false,
                        false,
                        LocalDateTime.now());
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(team));

        ActivityAssignmentResult result = new ActivityAssignmentResult(Map.of(), Map.of());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(result);

        mockMvc.perform(get("/api/tournaments/{id}/activity-assignments", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phaseId").value(phaseId.toString()))
                .andExpect(jsonPath("$.assignments").isArray())
                .andExpect(jsonPath("$.unassigned").isArray());
    }
}
