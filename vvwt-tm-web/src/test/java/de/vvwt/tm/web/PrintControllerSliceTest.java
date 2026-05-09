package de.vvwt.tm.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
import de.vvwt.tm.tournament.activity.ActivityTypeRepository;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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

    private static final UUID TOURNAMENT_ID =
            UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
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
        TenantContextSliceTestSupport.configureMock(tenantContext, TENANT_ID);
    }

    // =========================================================================
    // Security: unauthenticated → 401
    // =========================================================================

    @Test
    @DisplayName(
            "GET /print/tournaments/{tid} — unauthenticated → 401"
                    + " (AC-SECURITYCONFIG-PRINT-PATTERN)")
    void printIndex_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /print/tournaments/{tid}/team-schedules/{teamId} — unauthenticated → 401")
    void singleTeamSchedule_unauthenticated_returns401() throws Exception {
        mockMvc.perform(
                        get(
                                "/print/tournaments/{tid}/team-schedules/{teamId}",
                                TOURNAMENT_ID,
                                TEAM_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /print/tournaments/{tid}/team-schedules — unauthenticated → 401")
    void allTeamSchedules_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/print/tournaments/{tid}/team-schedules", TOURNAMENT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName(
            "GET /print/tournaments/{tid}/activity-schedule/{activityTypeId} — unauthenticated →"
                    + " 401")
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
    @DisplayName(
            "GET /print/tournaments/{tid} — authenticated + tournament + phases → print/index"
                    + " (AC-URL-INDEX)")
    void printIndex_authenticated_tournamentAndPhasesExist_returnsPrintIndex() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        de.vvwt.tm.tournament.activity.ActivityType photoType =
                photoActivityType(ACTIVITY_TYPE_ID, "Mannschaftsfoto", 1);
        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(phase(TOURNAMENT_ID)));
        // E53S03: printIndex now resolves photo activity-type for fotosUrl
        Mockito.when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(photoType));
        stubMessageSource();

        mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/index"))
                .andExpect(model().attributeExists("tournament"))
                .andExpect(model().attribute("linksAvailable", true))
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
    @DisplayName(
            "GET /print/tournaments/{tid}/team-schedules/{teamId} — happy path → print/laufzettel"
                    + " (AC-URL-LAUFZETTEL-SINGLE)")
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
    @DisplayName(
            "GET /print/tournaments/{tid}/team-schedules/{teamId} — tournament not found → 404")
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
    @DisplayName(
            "GET /print/tournaments/{tid}/team-schedules — happy path → print/laufzettel-all"
                    + " (AC-URL-LAUFZETTEL-ALL)")
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
    @DisplayName(
            "GET /print/tournaments/{tid}/activity-schedule/{actId} — happy path →"
                    + " print/activity-schedule (AC-URL-ACTIVITY-SCHEDULE)")
    void activitySchedule_happyPath_returnsActivitySchedule() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Phase phase = phase(TOURNAMENT_ID);
        de.vvwt.tm.tournament.activity.ActivityType actType =
                activityType(ACTIVITY_TYPE_ID, "Mannschaftsfoto");

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
                                List.of(), 0, 0, List.of(), false));
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
    @DisplayName(
            "GET /print/tournaments/{tid}/activity-schedule/{actId} — tournament not found → 404")
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
    // E53S03 — AC-TEST-INDEX-GATE-NO-ACTIVE-PHASE-RED (slice layer)
    // =========================================================================

    /**
     * AC-TEST-INDEX-GATE-NO-ACTIVE-PHASE-RED (slice): when all phases are PENDING (no ACTIVE),
     * printIndex must NOT render laufzettelUrl/fotosUrl links, and must populate
     * noActivePhaseMessage model attribute (operator-actionable empty-state).
     *
     * <p>RED before D-5(2) implementation (index-gate not yet present).
     *
     * @see PrintController#printIndex
     * @since E53S03
     */
    @Test
    @WithMockUser
    @DisplayName(
            "AC-TEST-INDEX-GATE-NO-ACTIVE-PHASE-RED (slice): no ACTIVE phase → index shows"
                    + " empty-state, links hidden — E53S03")
    void printIndex_noActivePhase_showsEmptyState_linksHidden() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Phase pendingPhase = pendingPhase(TOURNAMENT_ID);

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(pendingPhase));
        stubMessageSource();

        // AC: when no ACTIVE phase, model must expose linksAvailable=false +
        // noActivePhaseMessage present; laufzettelUrl / fotosUrl must NOT be set
        mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/index"))
                .andExpect(model().attribute("linksAvailable", false))
                .andExpect(model().attributeExists("noActivePhaseMessage"))
                .andExpect(model().attributeDoesNotExist("laufzettelUrl"))
                .andExpect(model().attributeDoesNotExist("fotosUrl"));
    }

    /**
     * AC-TEST-INDEX-GATE-WITH-ACTIVE-PHASE-RED (slice): when at least one ACTIVE phase exists,
     * printIndex must render both laufzettelUrl and fotosUrl, linksAvailable=true, no
     * noActivePhaseMessage.
     *
     * @since E53S03
     */
    @Test
    @WithMockUser
    @DisplayName(
            "AC-TEST-INDEX-GATE-WITH-ACTIVE-PHASE-RED (slice): ACTIVE phase present → links"
                    + " available — E53S03")
    void printIndex_withActivePhase_linksAvailable() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Phase activePhase = phase(TOURNAMENT_ID); // helper creates ACTIVE phase
        de.vvwt.tm.tournament.activity.ActivityType photoType =
                photoActivityType(ACTIVITY_TYPE_ID, "Mannschaftsfoto", 1);

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(activePhase));
        Mockito.when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(photoType));
        stubMessageSource();

        mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/index"))
                .andExpect(model().attribute("linksAvailable", true))
                .andExpect(model().attributeDoesNotExist("noActivePhaseMessage"))
                .andExpect(model().attributeExists("laufzettelUrl"))
                .andExpect(model().attributeExists("fotosUrl"));
    }

    /**
     * AC-TEST-PHOTO-RESOLVER-HELPER-SHARED-RED (slice): when printIndex resolves fotosUrl and
     * activitySchedule is invoked for a photo type, both invoke the same FIRST_FREE_ROUND resolver
     * logic — slice-layer verification: fotosUrl in printIndex contains the photo activity-type UUID.
     *
     * @since E53S03
     */
    @Test
    @WithMockUser
    @DisplayName(
            "AC-TEST-PHOTO-RESOLVER-HELPER-SHARED-RED (slice): printIndex fotosUrl contains"
                    + " photoActivityTypeId — E53S03")
    void printIndex_withPhotoActivityType_fotosUrlContainsPhotoTypeId() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Phase activePhase = phase(TOURNAMENT_ID);
        de.vvwt.tm.tournament.activity.ActivityType photoType =
                photoActivityType(ACTIVITY_TYPE_ID, "Mannschaftsfoto", 1);

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(activePhase));
        Mockito.when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(photoType));
        stubMessageSource();

        mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/index"))
                .andExpect(
                        model().attribute(
                                        "fotosUrl",
                                        "/print/tournaments/"
                                                + TOURNAMENT_ID
                                                + "/activity-schedule/"
                                                + ACTIVITY_TYPE_ID));
    }

    /**
     * AC-TEST-PHOTO-EDGE-ZERO-CANDIDATES-RED (slice): when no photo activity-type is configured,
     * printIndex must still render successfully with linksAvailable=false for fotosUrl (no link
     * to 404), or render a fallback with operator-actionable content.
     *
     * @since E53S03
     */
    @Test
    @WithMockUser
    @DisplayName(
            "AC-TEST-PHOTO-EDGE-ZERO-CANDIDATES-RED (slice): no photo activity-type → fotosUrl"
                    + " not rendered as live link — E53S03")
    void printIndex_noPhotoActivityType_fotosUrlAbsentOrEmpty() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Phase activePhase = phase(TOURNAMENT_ID);

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(activePhase));
        // No FIRST_FREE_ROUND activity types
        Mockito.when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of());
        stubMessageSource();

        // When no photo type: fotosUrl absent from model (link hidden) or model has null value
        mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/index"))
                .andExpect(model().attributeDoesNotExist("fotosUrl"));
    }

    // =========================================================================
    // E53S03 — AC-TEST-PHOTO-SCHEDULE-FIRST-PHASE-ONLY-RED (slice)
    // =========================================================================

    /**
     * AC-TEST-PHOTO-SCHEDULE-FIRST-PHASE-ONLY-RED (slice): when activitySchedule is invoked for a
     * photo activity-type (FIRST_FREE_ROUND rule), the controller filters phases to sequence_number=1
     * only. Verified by checking the assembler is called with a single-phase list.
     *
     * @since E53S03
     */
    @Test
    @WithMockUser
    @DisplayName(
            "AC-TEST-PHOTO-SCHEDULE-FIRST-PHASE-ONLY-RED (slice): photo activity-type → assembler"
                    + " called with first phase only — E53S03")
    void activitySchedule_photoType_assemblerCalledWithFirstPhaseOnly() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        // Two ACTIVE phases: seq=1 (photo phase) and seq=2
        Phase phase1 = new Phase();
        phase1.setId(UUID.fromString("dddddddd-0000-0000-0000-000000000001"));
        phase1.setTournamentId(TOURNAMENT_ID);
        phase1.setSequenceNumber(1);
        phase1.setStatus(Phase.PhaseStatus.ACTIVE.name());

        Phase phase2 = new Phase();
        phase2.setId(UUID.fromString("dddddddd-0000-0000-0000-000000000002"));
        phase2.setTournamentId(TOURNAMENT_ID);
        phase2.setSequenceNumber(2);
        phase2.setStatus(Phase.PhaseStatus.ACTIVE.name());

        de.vvwt.tm.tournament.activity.ActivityType photoType =
                photoActivityType(ACTIVITY_TYPE_ID, "Mannschaftsfoto", 1);

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(photoType));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(phase1, phase2));
        Mockito.when(matchRepository.findByPhaseId(phase1.getId()))
                .thenReturn(List.of(matchWithLap(phase1.getId())));
        Mockito.when(matchRepository.findByPhaseId(phase2.getId()))
                .thenReturn(List.of(matchWithLap(phase2.getId())));
        Mockito.when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        Mockito.when(teamAvatarRepository.findByPhaseId(phase1.getId())).thenReturn(List.of());
        Mockito.when(phaseBreakRepository.findByPhaseId(phase1.getId())).thenReturn(List.of());
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
                                List.of(), 0, 0, List.of(), false));
        stubMessageSource();

        mockMvc.perform(
                        get(
                                "/print/tournaments/{tid}/activity-schedule/{actId}",
                                TOURNAMENT_ID,
                                ACTIVITY_TYPE_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/activity-schedule"));

        // Verify assembler called with only phase1 (sequence_number=1), not both phases
        Mockito.verify(activityScheduleAssembler)
                .assemble(
                        Mockito.any(),
                        Mockito.argThat(phases -> phases.size() == 1
                                && phases.get(0).getSequenceNumber() == 1),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any());
    }

    /**
     * AC-TEST-PHOTO-SCHEDULE-NON-PHOTO-UNCHANGED-RED (slice): non-photo activity-type → assembler
     * called with ALL phases (no first-phase filter applied).
     *
     * @since E53S03
     */
    @Test
    @WithMockUser
    @DisplayName(
            "AC-TEST-PHOTO-SCHEDULE-NON-PHOTO-UNCHANGED-RED (slice): non-photo activity-type →"
                    + " assembler called with all phases — E53S03")
    void activitySchedule_nonPhotoType_assemblerCalledWithAllPhases() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "Test Tournament");
        Phase phase1 = new Phase();
        phase1.setId(UUID.fromString("eeeeeeee-0000-0000-0000-000000000001"));
        phase1.setTournamentId(TOURNAMENT_ID);
        phase1.setSequenceNumber(1);
        phase1.setStatus(Phase.PhaseStatus.ACTIVE.name());

        Phase phase2 = new Phase();
        phase2.setId(UUID.fromString("eeeeeeee-0000-0000-0000-000000000002"));
        phase2.setTournamentId(TOURNAMENT_ID);
        phase2.setSequenceNumber(2);
        phase2.setStatus(Phase.PhaseStatus.ACTIVE.name());

        // Non-photo: null assignmentRule or some other rule
        de.vvwt.tm.tournament.activity.ActivityType nonPhotoType =
                activityType(ACTIVITY_TYPE_ID, "Custom Activity");
        // No assignmentRule set = null = not FIRST_FREE_ROUND

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(nonPhotoType));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(phase1, phase2));
        Mockito.when(matchRepository.findByPhaseId(phase1.getId()))
                .thenReturn(List.of(matchWithLap(phase1.getId())));
        Mockito.when(matchRepository.findByPhaseId(phase2.getId()))
                .thenReturn(List.of(matchWithLap(phase2.getId())));
        Mockito.when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        Mockito.when(teamAvatarRepository.findByPhaseId(phase1.getId())).thenReturn(List.of());
        Mockito.when(teamAvatarRepository.findByPhaseId(phase2.getId())).thenReturn(List.of());
        Mockito.when(phaseBreakRepository.findByPhaseId(phase1.getId())).thenReturn(List.of());
        Mockito.when(phaseBreakRepository.findByPhaseId(phase2.getId())).thenReturn(List.of());
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
                                List.of(), 0, 0, List.of(), false));
        stubMessageSource();

        mockMvc.perform(
                        get(
                                "/print/tournaments/{tid}/activity-schedule/{actId}",
                                TOURNAMENT_ID,
                                ACTIVITY_TYPE_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("print/activity-schedule"));

        // Verify assembler called with BOTH phases (non-photo → no filter)
        Mockito.verify(activityScheduleAssembler)
                .assemble(
                        Mockito.any(),
                        Mockito.argThat(phases -> phases.size() == 2),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any());
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
        p.setStatus(Phase.PhaseStatus.ACTIVE.name()); // E53S02: filter requires ACTIVE status
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

    private de.vvwt.tm.tournament.activity.ActivityType activityType(UUID id, String name) {
        de.vvwt.tm.tournament.activity.ActivityType at =
                new de.vvwt.tm.tournament.activity.ActivityType();
        at.setId(id);
        at.setName(name);
        return at;
    }

    private de.vvwt.tm.tournament.activity.ActivityType photoActivityType(
            UUID id, String name, int sortOrder) {
        de.vvwt.tm.tournament.activity.ActivityType at =
                new de.vvwt.tm.tournament.activity.ActivityType();
        at.setId(id);
        at.setName(name);
        at.setAssignmentRule(
                de.vvwt.tm.tournament.activity.AssignmentRule.FIRST_FREE_ROUND.name());
        at.setSortOrder(sortOrder);
        return at;
    }

    private Phase pendingPhase(UUID tournamentId) {
        Phase p = new Phase();
        p.setId(UUID.randomUUID());
        p.setTournamentId(tournamentId);
        p.setSequenceNumber(1);
        p.setStatus(Phase.PhaseStatus.PENDING.name());
        return p;
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
