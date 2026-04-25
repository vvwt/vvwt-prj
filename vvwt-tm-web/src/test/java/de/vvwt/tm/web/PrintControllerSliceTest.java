package de.vvwt.tm.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.vvwt.tm.domain.repo.ActivityTypeRepository;
import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.print.ActivityScheduleAssembler;
import de.vvwt.tm.print.LaufzettelAssembler;
import de.vvwt.tm.tenant.LocationDisplayResolver;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code @WebMvcTest} slice test for {@link PrintController} (AC-REDFIRST-SLICE-TEST, E24S06).
 *
 * <p>This test was committed RED before the controller existed — satisfying the DEC-22 Iron Law
 * Q-1a RED-first requirement (controller absent at RED commit).
 *
 * <p>All 13 collaborators mocked via {@code @MockitoBean} per DEC-36 (cross-package test types
 * reference public interfaces only).
 *
 * <p>Endpoints covered per AC-REDFIRST-SLICE-TEST:
 *
 * <ul>
 *   <li>printIndex happy-path + 404 TNFE + no-phases error
 *   <li>singleTeamSchedule happy-path + 404 TNFE
 *   <li>allTeamSchedules happy-path + 404 TNFE
 *   <li>activitySchedule happy-path + 404 TNFE
 *   <li>Security: 401 unauthenticated on all endpoints
 * </ul>
 *
 * @see PrintController
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — Cross-package test typing (interface mock)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation (controller in web)</a>
 * @since E24S06
 */
@WebMvcTest(PrintController.class)
@DisplayName("PrintController @WebMvcTest slice — E24S06")
class PrintControllerSliceTest {

    private static final UUID TOURNAMENT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID ACTIVITY_TYPE_ID =
            UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID TENANT_ID = UUID.randomUUID();

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    // DEC-36: all mocks typed to public interfaces
    @MockitoBean private TournamentRepository tournamentRepository;
    @MockitoBean private PhaseRepository phaseRepository;
    @MockitoBean private MatchRepository matchRepository;
    @MockitoBean private TeamRepository teamRepository;
    @MockitoBean private TeamAvatarRepository teamAvatarRepository;
    @MockitoBean private PhaseBreakRepository phaseBreakRepository;
    @MockitoBean private ActivityTypeRepository activityTypeRepository;
    @MockitoBean private LaufzettelAssembler laufzettelAssembler;
    @MockitoBean private ActivityScheduleAssembler activityScheduleAssembler;
    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private LocationDisplayResolver locationDisplayResolver;
    @MockitoBean private MessageSource messageSource;
    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();
        TenantContextSliceTestSupport.stubCurrentTenant(tenantContext, TENANT_ID);
    }

    // =========================================================================
    // Security: unauthenticated → 401
    // =========================================================================

    @Test
    @DisplayName("GET /print/tournaments/{tid} — unauthenticated → 401 (AC-SECURITYCONFIG-PRINT-PATTERN)")
    void printIndex_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /print/tournaments/{tid}/team-schedules/{teamId} — unauthenticated → 401")
    void singleTeamSchedule_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/print/tournaments/{tid}/team-schedules/{teamId}", TOURNAMENT_ID, TEAM_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /print/tournaments/{tid}/team-schedules — unauthenticated → 401")
    void allTeamSchedules_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/print/tournaments/{tid}/team-schedules", TOURNAMENT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /print/tournaments/{tid}/activity-schedule/{activityTypeId} — unauthenticated → 401")
    void activitySchedule_unauthenticated_returns401() throws Exception {
        mockMvc.perform(
                        get(
                                "/print/tournaments/{tid}/activity-schedule/{actId}",
                                TOURNAMENT_ID,
                                ACTIVITY_TYPE_ID))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // printIndex — happy path
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("GET /print/tournaments/{tid} — authenticated + tournament + phases → print/index (AC-URL-INDEX)")
    void printIndex_authenticated_tournamentAndPhasesExist_returnsPrintIndex() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(phase(TOURNAMENT_ID)));
        stubMessageSource();

        mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/index"))
                .andExpect(model().attributeExists("tournament"))
                .andExpect(model().attributeExists("laufzettelUrl"))
                .andExpect(model().attributeExists("fotosUrl"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /print/tournaments/{tid} — tournament not found → 404 (AC-THROW-TNFE)")
    void printIndex_tournamentNotFound_throws404() throws Exception {
        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenThrow(new TournamentNotFoundException(TOURNAMENT_ID));

        mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /print/tournaments/{tid} — no phases → print/error (AC-URL-INDEX)")
    void printIndex_noPhases_returnsPrintError() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        stubMessageSource();

        mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/error"));
    }

    // =========================================================================
    // singleTeamSchedule
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("GET /print/tournaments/{tid}/team-schedules/{teamId} — happy path → print/laufzettel (AC-URL-LAUFZETTEL-SINGLE)")
    void singleTeamSchedule_happyPath_returnsLaufzettel() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Team team = team(TEAM_ID, TOURNAMENT_ID, 1);
        Phase phase = phase(TOURNAMENT_ID);

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(team));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase));
        Mockito.when(matchRepository.findByPhaseId(phase.getId()))
                .thenReturn(
                        List.of(
                                matchWithLap(
                                        phase.getId()))); // AC-URL-LAUFZETTEL-SINGLE: has matches
        Mockito.when(teamAvatarRepository.findByPhaseId(phase.getId())).thenReturn(List.of());
        Mockito.when(phaseBreakRepository.findByPhaseId(phase.getId())).thenReturn(List.of());
        Mockito.when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of());
        Mockito.when(
                        laufzettelAssembler.assemble(
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.anyInt()))
                .thenReturn(java.util.Map.of());
        Mockito.when(laufzettelAssembler.hasTime(Mockito.any())).thenReturn(false);
        stubMessageSource();

        mockMvc.perform(
                        get(
                                "/print/tournaments/{tid}/team-schedules/{teamId}",
                                TOURNAMENT_ID,
                                TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/laufzettel"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /print/tournaments/{tid}/team-schedules/{teamId} — tournament not found → 404")
    void singleTeamSchedule_tournamentNotFound_throws404() throws Exception {
        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenThrow(new TournamentNotFoundException(TOURNAMENT_ID));

        mockMvc.perform(
                        get(
                                "/print/tournaments/{tid}/team-schedules/{teamId}",
                                TOURNAMENT_ID,
                                TEAM_ID))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // allTeamSchedules
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("GET /print/tournaments/{tid}/team-schedules — happy path → print/laufzettel-all (AC-URL-LAUFZETTEL-ALL)")
    void allTeamSchedules_happyPath_returnsLaufzettelAll() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Team team = team(TEAM_ID, TOURNAMENT_ID, 1);
        Phase phase = phase(TOURNAMENT_ID);

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase));
        Mockito.when(matchRepository.findByPhaseId(phase.getId()))
                .thenReturn(List.of(matchWithLap(phase.getId())));
        Mockito.when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(team));
        Mockito.when(teamAvatarRepository.findByPhaseId(phase.getId())).thenReturn(List.of());
        Mockito.when(phaseBreakRepository.findByPhaseId(phase.getId())).thenReturn(List.of());
        Mockito.when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of());
        Mockito.when(
                        laufzettelAssembler.assemble(
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.anyInt()))
                .thenReturn(java.util.Map.of());
        Mockito.when(laufzettelAssembler.hasTime(Mockito.any())).thenReturn(false);
        stubMessageSource();

        mockMvc.perform(get("/print/tournaments/{tid}/team-schedules", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/laufzettel-all"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /print/tournaments/{tid}/team-schedules — tournament not found → 404")
    void allTeamSchedules_tournamentNotFound_throws404() throws Exception {
        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenThrow(new TournamentNotFoundException(TOURNAMENT_ID));

        mockMvc.perform(get("/print/tournaments/{tid}/team-schedules", TOURNAMENT_ID))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // activitySchedule
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("GET /print/tournaments/{tid}/activity-schedule/{actId} — happy path → print/activity-schedule (AC-URL-ACTIVITY-SCHEDULE)")
    void activitySchedule_happyPath_returnsActivitySchedule() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Phase phase = phase(TOURNAMENT_ID);
        de.vvwt.tm.domain.ActivityType actType = activityType(ACTIVITY_TYPE_ID, "Mannschaftsfoto");

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(actType));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase));
        Mockito.when(matchRepository.findByPhaseId(phase.getId()))
                .thenReturn(List.of(matchWithLap(phase.getId())));
        Mockito.when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        Mockito.when(teamAvatarRepository.findByPhaseId(phase.getId())).thenReturn(List.of());
        Mockito.when(phaseBreakRepository.findByPhaseId(phase.getId())).thenReturn(List.of());
        Mockito.when(
                        activityScheduleAssembler.assemble(
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any()))
                .thenReturn(
                        new de.vvwt.tm.print.ActivityScheduleModel(
                                List.of(), 0, 0, false, List.of()));
        stubMessageSource();

        mockMvc.perform(
                        get(
                                "/print/tournaments/{tid}/activity-schedule/{actId}",
                                TOURNAMENT_ID,
                                ACTIVITY_TYPE_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/activity-schedule"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /print/tournaments/{tid}/activity-schedule/{actId} — tournament not found → 404")
    void activitySchedule_tournamentNotFound_throws404() throws Exception {
        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenThrow(new TournamentNotFoundException(TOURNAMENT_ID));

        mockMvc.perform(
                        get(
                                "/print/tournaments/{tid}/activity-schedule/{actId}",
                                TOURNAMENT_ID,
                                ACTIVITY_TYPE_ID))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Tournament tournament(UUID id, String description) {
        Tournament t = new Tournament();
        t.setId(id);
        t.setDescription(description);
        return t;
    }

    private Phase phase(UUID tournamentId) {
        Phase p = new Phase();
        p.setId(UUID.randomUUID());
        p.setTournamentId(tournamentId);
        p.setSequenceNumber(1);
        return p;
    }

    private Team team(UUID id, UUID tournamentId, int teamNumber) {
        Team t = new Team();
        t.setId(id);
        t.setTournamentId(tournamentId);
        t.setTeamNumber(teamNumber);
        return t;
    }

    private de.vvwt.tm.tournament.Match matchWithLap(UUID phaseId) {
        de.vvwt.tm.tournament.Match m = new de.vvwt.tm.tournament.Match();
        m.setId(UUID.randomUUID());
        m.setPhaseId(phaseId);
        m.setLapNumber(1);
        return m;
    }

    private de.vvwt.tm.domain.ActivityType activityType(UUID id, String name) {
        de.vvwt.tm.domain.ActivityType at = new de.vvwt.tm.domain.ActivityType();
        at.setId(id);
        at.setName(name);
        return at;
    }

    private void stubMessageSource() {
        Mockito.when(
                        messageSource.getMessage(
                                Mockito.anyString(),
                                Mockito.any(),
                                Mockito.anyString(),
                                Mockito.any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }
}
