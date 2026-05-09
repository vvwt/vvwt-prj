package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.print.ActivityScheduleAssembler;
import de.vvwt.tm.print.ActivityScheduleModel;
import de.vvwt.tm.print.LaufzettelAssembler;
import de.vvwt.tm.tenant.LocationDisplayResolver;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.activity.ActivityType;
import de.vvwt.tm.tournament.activity.ActivityTypeRepository;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * RED-first tests for E53S01 — Mustache {@code {{!...}}} comment-leak fix.
 *
 * <p>Verifies that no print page rendered by {@link PrintController} leaks Mustache comment-block
 * text (e.g. "Required model attributes", "i18n label") or raw {@code {{...}}} tag fragments into
 * the rendered HTML response body.
 *
 * <p>All four tests are intentionally RED against the current (pre-fix) codebase: the multi-line
 * {@code {{!...}}} comment blocks in the print templates contain embedded {@code {{tag}}}
 * references, which jmustache interprets as closing the comment at the first interior {@code }}},
 * causing the remainder of the comment to render as HTML text.
 *
 * <p>After the E53S01 template fix (replacing multi-line {@code {{!...}}} comment blocks with
 * {@code <!-- ... -->} HTML comments), these tests turn GREEN — HTML comments are not rendered into
 * the visible body by browsers or included in MockMvc response bodies.
 *
 * <p><b>Note on test framework selection:</b> DEC-44 mandates {@code @SpringBootTest(RANDOM_PORT,
 * classes = TournamentManagerApplication.class)} for new web-module controller ITs. However, on the
 * current staging baseline, all {@code @SpringBootTest(RANDOM_PORT)} tests fail to load the
 * application context due to a pre-existing Flyway migration ordering issue (certificate/V1 runs
 * before tournament/V1 in the per-tenant H2 bootstrap, acknowledged in E51S11 qa-report
 * PASS_WITH_NOTES as 21 pre-existing errors). Using {@code @WebMvcTest} renders the Mustache
 * templates through MockMvc and provides a properly RED-then-GREEN assertion on template content,
 * satisfying the DEC-22 Iron Law Q-1a RED-first contract for this new code path.
 *
 * @see PrintController
 * @see PrintControllerSliceTest
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (Q-1a RED-first, NEW code path)</a>
 * @see <a href="DEC-36">DEC-36 — Cross-package test typing (interface mock)</a>
 * @since E53S01
 */
@WebMvcTest(PrintController.class)
@DisplayName("PrintControllerCommentLeakIT — E53S01 no-comment-leak assertions")
class PrintControllerCommentLeakIT {

    private static final UUID TOURNAMENT_ID =
            UUID.fromString("e5350100-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID = UUID.fromString("e5350100-0000-0000-0000-000000000002");
    private static final UUID TEAM_ID_2 = UUID.fromString("e5350100-0000-0000-0000-000000000003");
    private static final UUID TEAM_ID_3 = UUID.fromString("e5350100-0000-0000-0000-000000000004");
    private static final UUID ACTIVITY_TYPE_ID =
            UUID.fromString("e5350100-0000-0000-0000-000000000005");
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
    // AC-TEST-NO-COMMENT-LEAK-INDEX-RED
    // =========================================================================

    /**
     * Verifies that the print-index page does NOT contain Mustache comment-block sentinels in
     * the rendered HTML body.
     *
     * <p>RED before E53S01 fix: {@code index.mustache} has a multi-line {@code {{!...}}} block
     * with embedded {@code {{tag}}} references (e.g. {@code {{locale}}}); jmustache closes the
     * comment at the first interior {@code }}} and renders the rest as HTML — including
     * "Required model attributes", "i18n label", and bare {@code {{}} fragments.
     *
     * @see <a href="DEC-22">DEC-22 — Q-1a RED-first, NEW code path</a>
     */
    @Test
    @WithMockUser
    @DisplayName(
            "AC-TEST-NO-COMMENT-LEAK-INDEX-RED: printIndex rendered body contains no comment"
                    + " sentinels")
    void printIndex_noCommentLeak() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "E53S01 Test Tournament");
        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(phase(TOURNAMENT_ID)));
        stubMessageSource();

        MvcResult result =
                mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                        .andExpect(status().isOk())
                        .andReturn();

        String body = result.getResponse().getContentAsString();
        assertNoCommentLeak(
                body,
                "AC-TEST-NO-COMMENT-LEAK-INDEX-RED: printIndex rendered body must not contain"
                        + " comment sentinels");
    }

    // =========================================================================
    // AC-TEST-NO-COMMENT-LEAK-LAUFZETTEL-RED
    // =========================================================================

    /**
     * Verifies that the all-teams Laufzettel page does NOT contain {@code [isPhaseHeader,}
     * List.toString() opening, {@code =Mannschaft} raw map-entry fragments, or bare {@code {{}}
     * substrings outside {@code <script>} blocks.
     *
     * <p>RED before fix: {@code laufzettel-all.mustache} has a multi-line comment with embedded
     * {@code {{tag}}} references — causing stub model-attribute dumps to appear in the body.
     * The fixture uses 3 teams so {@code showPageBreak=true} for teams 2+3, exercising the
     * {@code {{> print-page-break}}} partial-include path per AC-TEST-NO-COMMENT-LEAK-ALL-PRINT-PAGES-RED.
     *
     * @see <a href="DEC-22">DEC-22 — Q-1a RED-first</a>
     */
    @Test
    @WithMockUser
    @DisplayName(
            "AC-TEST-NO-COMMENT-LEAK-LAUFZETTEL-RED: allTeamSchedules rendered body contains no"
                    + " List.toString() dump or comment sentinels")
    void allTeamSchedules_noCommentLeak() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "E53S01 Test Tournament");
        Phase phase = phase(TOURNAMENT_ID);
        // Three teams: showPageBreak=true for teams 2+3 (i>0 in PrintController loop),
        // which exercises the {{> print-page-break}} partial-include path.
        Team team1 = team(TEAM_ID, TOURNAMENT_ID, 1);
        Team team2 = team(TEAM_ID_2, TOURNAMENT_ID, 2);
        Team team3 = team(TEAM_ID_3, TOURNAMENT_ID, 3);

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase));
        Mockito.when(matchRepository.findByPhaseId(phase.getId()))
                .thenReturn(List.of(matchWithLap(phase.getId())));
        Mockito.when(teamRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(team1, team2, team3));
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
                .thenReturn(Map.of());
        Mockito.when(laufzettelAssembler.hasTime(Mockito.any())).thenReturn(false);
        stubMessageSource();

        MvcResult result =
                mockMvc.perform(get("/print/tournaments/{tid}/team-schedules", TOURNAMENT_ID))
                        .andExpect(status().isOk())
                        .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as(
                        "AC-TEST-NO-COMMENT-LEAK-LAUFZETTEL-RED: body must not contain"
                                + " [isPhaseHeader, (List.toString() opening)")
                .doesNotContain("[isPhaseHeader,");
        assertThat(body)
                .as(
                        "AC-TEST-NO-COMMENT-LEAK-LAUFZETTEL-RED: body must not contain"
                                + " =Mannschaft (raw map-entry fragment)")
                .doesNotContain("=Mannschaft");
        assertNoCommentLeak(
                body,
                "AC-TEST-NO-COMMENT-LEAK-LAUFZETTEL-RED: allTeamSchedules rendered body must not"
                        + " contain comment sentinels");
    }

    // =========================================================================
    // AC-TEST-NO-COMMENT-LEAK-ALL-PRINT-PAGES-RED
    // =========================================================================

    /**
     * Iterates all four PrintController endpoints and verifies that none of their rendered HTML
     * bodies contains bare {@code {{}} substrings outside {@code <script>} blocks.
     *
     * <p>Also covers AC-ERROR-LEAK-FIX-REGRESSION-EMPTY-PHASES: the print/error template is
     * exercised via the no-phases path (tournament exists, phases list empty → error view).
     * Covers AC-TEST-NO-COMMENT-LEAK-INDEX-RED + AC-TEST-NO-COMMENT-LEAK-LAUFZETTEL-RED at
     * a consolidated level.
     *
     * @see <a href="DEC-22">DEC-22 — Q-1a RED-first</a>
     */
    @Test
    @WithMockUser
    @DisplayName(
            "AC-TEST-NO-COMMENT-LEAK-ALL-PRINT-PAGES-RED: all PrintController endpoints render"
                    + " without {{...}} leak")
    void allPrintPages_noCommentLeak() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "E53S01 All-Pages Test Tournament");
        Phase phase = phase(TOURNAMENT_ID);
        Team team1 = team(TEAM_ID, TOURNAMENT_ID, 1);
        Team team2 = team(TEAM_ID_2, TOURNAMENT_ID, 2);
        Team team3 = team(TEAM_ID_3, TOURNAMENT_ID, 3);
        ActivityType actType = activityType(ACTIVITY_TYPE_ID, "E53S01 Activity");

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase));
        Mockito.when(matchRepository.findByPhaseId(phase.getId()))
                .thenReturn(List.of(matchWithLap(phase.getId())));
        Mockito.when(teamRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(team1, team2, team3));
        Mockito.when(teamAvatarRepository.findByPhaseId(phase.getId())).thenReturn(List.of());
        Mockito.when(phaseBreakRepository.findByPhaseId(phase.getId())).thenReturn(List.of());
        Mockito.when(activityTypeRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(actType));
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
                .thenReturn(Map.of());
        Mockito.when(laufzettelAssembler.hasTime(Mockito.any())).thenReturn(false);
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
                .thenReturn(new ActivityScheduleModel(List.of(), 0, 0, List.of(), false));
        stubMessageSource();

        // printIndex (renders index.mustache)
        String indexBody =
                mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertNoCommentLeak(
                indexBody,
                "AC-TEST-NO-COMMENT-LEAK-ALL-PRINT-PAGES-RED: printIndex (index.mustache)");

        // allTeamSchedules (renders laufzettel-all.mustache + print-page-break partial for
        // teams 2+3)
        String allSchedulesBody =
                mockMvc.perform(get("/print/tournaments/{tid}/team-schedules", TOURNAMENT_ID))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertNoCommentLeak(
                allSchedulesBody,
                "AC-TEST-NO-COMMENT-LEAK-ALL-PRINT-PAGES-RED: allTeamSchedules"
                        + " (laufzettel-all.mustache)");

        // singleTeamSchedule (renders laufzettel.mustache)
        String singleBody =
                mockMvc.perform(
                                get(
                                        "/print/tournaments/{tid}/team-schedules/{teamId}",
                                        TOURNAMENT_ID,
                                        TEAM_ID))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertNoCommentLeak(
                singleBody,
                "AC-TEST-NO-COMMENT-LEAK-ALL-PRINT-PAGES-RED: singleTeamSchedule"
                        + " (laufzettel.mustache)");

        // activitySchedule (renders activity-schedule.mustache)
        String activityBody =
                mockMvc.perform(
                                get(
                                        "/print/tournaments/{tid}/activity-schedule/{actId}",
                                        TOURNAMENT_ID,
                                        ACTIVITY_TYPE_ID))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertNoCommentLeak(
                activityBody,
                "AC-TEST-NO-COMMENT-LEAK-ALL-PRINT-PAGES-RED: activitySchedule"
                        + " (activity-schedule.mustache)");

        // AC-ERROR-LEAK-FIX-REGRESSION-EMPTY-PHASES: no-phases path → print/error
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        String errorBody =
                mockMvc.perform(get("/print/tournaments/{tid}", TOURNAMENT_ID))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertNoCommentLeak(
                errorBody,
                "AC-ERROR-LEAK-FIX-REGRESSION-EMPTY-PHASES: print/error (error.mustache)");
    }

    // =========================================================================
    // AC-TEST-DEAD-CODE-STUBS-REMOVED-RED
    // =========================================================================

    /**
     * Verifies that {@code allTeamSchedules} renders correctly without the stub model-attribute
     * additions at PrintController lines 375-384. GREEN after fix (stubs removed; the leak-free
     * template no longer references {@code {{tag}}} inside comments, so jmustache strict-mode does
     * not throw, and no stub attributes are needed).
     *
     * @see <a href="DEC-22">DEC-22 — Q-1a RED-first</a>
     */
    @Test
    @WithMockUser
    @DisplayName(
            "AC-TEST-DEAD-CODE-STUBS-REMOVED-RED: allTeamSchedules renders without stub"
                    + " model-attributes and no {{...}} leak")
    void allTeamSchedules_noStubAttributes_rendersCorrectly() throws Exception {
        Tournament tournament = tournament(TOURNAMENT_ID, "E53S01 Stub Removal Test");
        Phase phase = phase(TOURNAMENT_ID);
        Team team1 = team(TEAM_ID, TOURNAMENT_ID, 1);
        Team team2 = team(TEAM_ID_2, TOURNAMENT_ID, 2);

        Mockito.when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament));
        Mockito.when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase));
        Mockito.when(matchRepository.findByPhaseId(phase.getId()))
                .thenReturn(List.of(matchWithLap(phase.getId())));
        Mockito.when(teamRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(team1, team2));
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
                .thenReturn(Map.of());
        Mockito.when(laufzettelAssembler.hasTime(Mockito.any())).thenReturn(false);
        stubMessageSource();

        MvcResult result =
                mockMvc.perform(get("/print/tournaments/{tid}/team-schedules", TOURNAMENT_ID))
                        .andExpect(status().isOk())
                        .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as(
                        "AC-TEST-DEAD-CODE-STUBS-REMOVED-RED: allTeamSchedules body must not be"
                                + " null")
                .isNotNull();
        // After fix: body renders without comment leak (no {{...}} stubs needed)
        assertNoCommentLeak(
                body,
                "AC-TEST-DEAD-CODE-STUBS-REMOVED-RED: allTeamSchedules body must not contain"
                        + " comment sentinels after stub removal");
    }

    // =========================================================================
    // Helper: assertNoCommentLeak
    // =========================================================================

    /**
     * Asserts that the given rendered HTML body does not contain Mustache comment-block sentinels
     * or bare {@code {{}} substrings in the <em>visible</em> HTML content (i.e., outside HTML
     * comment nodes and outside {@code <script>} blocks).
     *
     * <p>The E53S01 fix replaces multi-line {@code {{!...}}} Mustache comment blocks with
     * {@code <!-- ... -->} HTML comments. HTML comment nodes ARE present in the raw HTTP response
     * body returned by MockMvc — they are not rendered visibly by browsers. This helper strips
     * HTML comment blocks before asserting, confirming that the sentinel text is confined to
     * HTML comment nodes (not visible) rather than leaking into plain HTML text (visible).
     *
     * <p>Before the fix: jmustache closes {@code {{!...}}} at the first interior {@code }}} and
     * renders the remainder as plain HTML text — sentinels appear outside any comment node.
     *
     * <p>After the fix: sentinels appear only inside {@code <!-- ... -->} HTML comment nodes —
     * stripped before the assertion, so no failure.
     *
     * <p>Sentinel checks (from the Discovery-identified comment block content in story E53S01):
     *
     * <ul>
     *   <li>"Required model attributes" — present in all 7 {@code templates/print/*.mustache}
     *       comment headers (index, print-layout, laufzettel, laufzettel-all, laufzettel-no-matches,
     *       error, activity-schedule)
     *   <li>"i18n label" — present in model-attribute documentation lines
     *   <li>"page heading" — present in index and error comment blocks
     *   <li>"URL to /print/" — present in index comment block
     * </ul>
     *
     * <p>{@code {{}} substring check: after stripping HTML comments and {@code <script>...</script>}
     * blocks, the body must contain no occurrence of the literal string {@code {{}.
     *
     * @param body the rendered HTML body (from MockMvc response)
     * @param assertionDescription a description prefix for assertion failure messages
     */
    private static void assertNoCommentLeak(String body, String assertionDescription) {
        // Strip HTML comment blocks (<!-- ... -->) — these are safe, not visible to users.
        // The fix converts Mustache comment blocks to HTML comments; content inside HTML
        // comment nodes is intentional and must NOT trigger sentinel failures.
        String bodyWithoutHtmlComments = body.replaceAll("(?s)<!--.*?-->", "");

        assertThat(bodyWithoutHtmlComments)
                .as(
                        assertionDescription
                                + ": visible body must not contain 'Required model attributes'")
                .doesNotContain("Required model attributes");
        assertThat(bodyWithoutHtmlComments)
                .as(assertionDescription + ": visible body must not contain 'i18n label'")
                .doesNotContain("i18n label");
        assertThat(bodyWithoutHtmlComments)
                .as(assertionDescription + ": visible body must not contain 'page heading'")
                .doesNotContain("page heading");
        assertThat(bodyWithoutHtmlComments)
                .as(assertionDescription + ": visible body must not contain 'URL to /print/'")
                .doesNotContain("URL to /print/");

        // Strip <script>...</script> blocks before checking for {{ fragments.
        // Script-block carve-out per AC-TEST-NO-COMMENT-LEAK-INDEX-RED.
        String visibleBody =
                bodyWithoutHtmlComments.replaceAll("(?s)<script[^>]*>.*?</script>", "");
        assertThat(visibleBody)
                .as(
                        assertionDescription
                                + ": visible body (outside HTML comments and <script> blocks) must"
                                + " not contain '{{'")
                .doesNotContain("{{");
    }

    // =========================================================================
    // Test helpers (reusing pattern from PrintControllerSliceTest)
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

    private Match matchWithLap(UUID phaseId) {
        Match m = new Match();
        m.setId(UUID.randomUUID());
        m.setPhaseId(phaseId);
        m.setLapNumber(1);
        return m;
    }

    private ActivityType activityType(UUID id, String name) {
        ActivityType at = new ActivityType();
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
