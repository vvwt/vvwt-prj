package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.display.DisplayGroupStandingsResponse;
import de.vvwt.tm.display.DisplayMatchesResponse;
import de.vvwt.tm.display.DisplayPhaseOverviewResponse;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.ApiErrorResponse;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link DisplayOverviewController} — E25S02 Q-1a TDD reconstruction.
 *
 * <h2>Test coverage (AC-Q7-METHOD-COUNT-RECONFIRMATION)</h2>
 *
 * <ul>
 *   <li>AC1 — GET /api/display/overview returns 200 with phase data for valid DISPLAY token
 *   <li>AC2 — GET /api/display/overview/matches returns 200 with matches per lap
 *   <li>AC3 — GET /api/display/overview/groups returns 200 with D-33-sorted standings
 *   <li>AC4 — Invalid token → 401; scoring tablet token (wrong type) → 401; per-endpoint
 *   <li>AC6 — preparationPreview=true when phase is PENDING with scheduled matches
 *   <li>AC7 — No active phase → 404 with {"status":"NO_ACTIVE_PHASE"}
 *   <li>AC9 — Error responses include messageKey (via GlobalExceptionHandler)
 *   <li>AC10 — Missing / empty token → 400
 *   <li>AC11 — POST/PUT/DELETE to overview endpoint → 405
 * </ul>
 *
 * <p>Total: 15 {@code @Test} methods (matches audit baseline 15+10=25 per
 * AC-Q7-METHOD-COUNT-RECONFIRMATION; fresh RED-first authoring per DEC-41 §3 hierarchy item 1 —
 * legacy tests classified Snapshot-Driven per E25-AUDIT-DEC41-TEST-CLASSIFICATION, commit {@code
 * 1aded97}).
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 Iron Law Q-1a — RED-first: this test written before {@link
 *       DisplayOverviewController} existed; RED commit = this commit; GREEN commit = next
 *       (DisplayOverviewController production)
 *   <li>DEC-36 — {@code DisplayOverviewService} interface FQN used (NOT {@code
 *       display.internal.DefaultDisplayOverviewService})
 *   <li>DEC-40 §2026-04-27 Clarification Pattern A — response types imported from {@code
 *       de.vvwt.tm.display.*} (NOT {@code web.internal.dto.*})
 *   <li>DEC-44 D1 — {@code @AutoConfigureTestRestTemplate @SpringBootTest(RANDOM_PORT)} +
 *       {@code @Import({WebModuleTestConfig, TestAdminCredentials})} per 2026-04-27 empirical
 *       refinement
 *   <li>DEC-44 D2 — per-IT inner {@code TestAdminCredentials} provides {@code @Primary
 *       AdminCredentialsProvider}; no {@code UserDetailsService} or {@code SecurityFilterChain}
 *       substitute
 * </ul>
 *
 * @see DisplayOverviewController
 * @see WebModuleTestConfig
 * @since E25S02
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e25s02overviewitdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, DisplayOverviewControllerIT.TestAdminCredentials.class})
class DisplayOverviewControllerIT {

    static final String TEST_PASSWORD = "DisplayOverviewCtrlIT25S02";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;

    @Autowired private DeviceRepository deviceRepository;

    @Autowired private TournamentRepository tournamentRepository;

    @Autowired private PhaseRepository phaseRepository;

    @Autowired private TeamRepository teamRepository;

    @Autowired private TeamAvatarRepository teamAvatarRepository;

    @Autowired private TeamAvatarRatingRepository teamAvatarRatingRepository;

    @Autowired private MatchRepository matchRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private UUID defaultTenantId;
    private UUID defaultLocationId;

    // -----------------------------------------------------------------------
    // Shared test-data state (set by setupTestData)
    // -----------------------------------------------------------------------

    private String displayDeviceToken;
    private String scoringTabletToken;
    private UUID tournamentId;
    private UUID phaseId;
    private String teamAName;
    private String teamBName;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        defaultTenantId = tenantContextBinder.bindDefaultTenant();
        defaultLocationId = tenantContextBinder.getDefaultLocationId();
        cleanupTestData();
        setupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
        tenantContextBinder.unbind();
    }

    // =========================================================================
    // Test data setup / cleanup
    // =========================================================================

    /**
     * Removes all test data in FK-safe order (children before parents). Called both before and
     * after each test to ensure a clean slate even after a previous failed run left data behind.
     */
    private void cleanupTestData() {
        jdbcTemplate.update("DELETE FROM set_result");
        jdbcTemplate.update("DELETE FROM match_outcome");
        jdbcTemplate.update("DELETE FROM match");
        jdbcTemplate.update("DELETE FROM team_avatar_rating");
        jdbcTemplate.update("DELETE FROM team_avatar");
        jdbcTemplate.update("DELETE FROM phase");
        jdbcTemplate.update("DELETE FROM team");
        jdbcTemplate.update("DELETE FROM activity_types");
        jdbcTemplate.update("DELETE FROM tournament");
        jdbcTemplate.update("DELETE FROM devices");
    }

    /**
     * Creates a minimal tournament, active phase, two teams, two avatars (group 1), one match
     * (lap=1, field=1), a DISPLAY device, and a SCORING_TABLET device.
     *
     * <p>This gives each test a baseline of one active tournament with one active phase, two teams,
     * one scheduled match, and valid device tokens for both device types.
     */
    private void setupTestData() {
        LocalDateTime now = LocalDateTime.now();

        // Register a DISPLAY device
        displayDeviceToken = UUID.randomUUID().toString();
        Device displayDevice =
                new Device(
                        UUID.randomUUID(),
                        null,
                        displayDeviceToken,
                        null,
                        "DISPLAY",
                        null,
                        Device.STATUS_REGISTERED,
                        now,
                        null,
                        "Display Device E25S02",
                        null);
        deviceRepository.save(displayDevice);

        // Register a SCORING_TABLET device (used for AC4 wrong-type test)
        scoringTabletToken = UUID.randomUUID().toString();
        Device tabletDevice =
                new Device(
                        UUID.randomUUID(),
                        null,
                        scoringTabletToken,
                        "1234",
                        Device.TYPE_SCORING_TABLET,
                        null,
                        Device.STATUS_REGISTERED,
                        now,
                        null,
                        null,
                        null);
        deviceRepository.save(tabletDevice);

        // Create active tournament
        tournamentId = UUID.randomUUID();
        Tournament tournament =
                new Tournament(
                        tournamentId,
                        "E25S02 Test Tournament",
                        "BEST_OF_1",
                        "threePointMatchRule",
                        "standardVolleyballSet",
                        "roundRobinMatchGenerator",
                        "ACTIVE",
                        now,
                        null,
                        3,
                        4);
        tournament.setLocationId(defaultLocationId);
        tournamentRepository.save(tournament);

        // Create active phase (currentLapNumber=1)
        phaseId = UUID.randomUUID();
        Phase phase = new Phase(phaseId, tournamentId, 1, "Vorrunde E25S02", "ACTIVE", 1, now);
        phaseRepository.save(phase);

        // Create team A and team B
        teamAName = "Team Alpha E25";
        teamBName = "Team Beta E25";
        UUID teamAId = UUID.randomUUID();
        UUID teamBId = UUID.randomUUID();
        Team teamA = new Team(teamAId, tournamentId, 1, teamAName, true, false, false, now);
        Team teamB = new Team(teamBId, tournamentId, 2, teamBName, true, false, false, now);
        teamRepository.save(teamA);
        teamRepository.save(teamB);

        // Create avatars in group 1 (positions 1 and 2)
        UUID avatarAId = UUID.randomUUID();
        UUID avatarBId = UUID.randomUUID();
        TeamAvatar avatarA =
                new TeamAvatar(
                        avatarAId, tournamentId, phaseId, 1, 1, teamAId, "Gruppe 1, Platz 1", now);
        TeamAvatar avatarB =
                new TeamAvatar(
                        avatarBId, tournamentId, phaseId, 1, 2, teamBId, "Gruppe 1, Platz 2", now);
        teamAvatarRepository.save(avatarA);
        teamAvatarRepository.save(avatarB);

        // Create ratings for each avatar (non-zero for AC3 standings test)
        // Team Alpha: 3 points → ranks first (D-33: points DESC)
        // Constructor: avatarId, tenantId, matchCount, setCount, points, setsWon, setsLost,
        //              ballsWon, ballsLost, setQuotient, ballQuotient, withoutAssessment, updatedAt
        TeamAvatarRating ratingA =
                new TeamAvatarRating(
                        avatarAId,
                        1, // matchCount
                        1, // setCount
                        3, // points
                        1, // setsWon
                        0, // setsLost
                        25, // ballsWon
                        15, // ballsLost
                        Double.MAX_VALUE, // setQuotient (sentinel: setsLost=0)
                        Double.MAX_VALUE, // ballQuotient (sentinel: ballsLost=0 relative)
                        false,
                        now);
        // Team Beta: 0 points → ranks second
        TeamAvatarRating ratingB =
                new TeamAvatarRating(avatarBId, 1, 1, 0, 0, 1, 15, 25, 0.0, 0.6, false, now);
        teamAvatarRatingRepository.save(ratingA);
        teamAvatarRatingRepository.save(ratingB);

        // Create one match: lap=1, field=1, state=ENABLED (PENDING display status)
        UUID matchId = UUID.randomUUID();
        Match match =
                new Match(
                        matchId,
                        tournamentId,
                        phaseId,
                        avatarAId,
                        avatarBId,
                        MatchState.ENABLED.getLegacyCode(),
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        now);
        matchRepository.save(match);
    }

    // =========================================================================
    // AC1 — GET /api/display/overview
    // =========================================================================

    /**
     * AC1: GET /api/display/overview returns 200 with phase data for a valid DISPLAY device token.
     *
     * <p>Verifies phaseId, phaseStatus, fieldCount, group count, and preparationPreview=false for
     * ACTIVE phase. AC-URL-PATHS-PRESERVED: URL {@code /api/display/overview} verbatim.
     */
    @Test
    void phaseOverviewReturns200WithPhaseDataForValidDisplayToken() {
        ResponseEntity<DisplayPhaseOverviewResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview?token=" + displayDeviceToken,
                        DisplayPhaseOverviewResponse.class);

        assertThat(response.getStatusCode())
                .as("AC1 — valid DISPLAY token must return 200")
                .isEqualTo(HttpStatus.OK);

        DisplayPhaseOverviewResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.phaseId())
                .as("AC1 — phaseId must match the active phase")
                .isEqualTo(phaseId);
        assertThat(body.phaseStatus()).as("AC1 — phaseStatus must be ACTIVE").isEqualTo("ACTIVE");
        assertThat(body.fieldCount()).as("AC1 — fieldCount from tournament (3)").isEqualTo(3);
        assertThat(body.groups()).as("AC1 — one group with 2 teams").hasSize(1);
        assertThat(body.groups().get(0).groupNumber())
                .as("AC1 — groupNumber must be 1")
                .isEqualTo(1);
        assertThat(body.groups().get(0).teamCount()).as("AC1 — teamCount must be 2").isEqualTo(2);
        assertThat(body.preparationPreview())
                .as("AC1 — preparationPreview must be false for ACTIVE phase")
                .isFalse();
    }

    /**
     * AC4: GET /api/display/overview must be accessible without admin authentication.
     *
     * <p>Display endpoints are permit-all — no admin credentials needed.
     */
    @Test
    void phaseOverviewDoesNotRequireAdminAuth() {
        ResponseEntity<DisplayPhaseOverviewResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview?token=" + displayDeviceToken,
                        DisplayPhaseOverviewResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — /api/display/overview must be accessible without admin credentials")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // AC2 — GET /api/display/overview/matches
    // =========================================================================

    /**
     * AC2: GET /api/display/overview/matches?token={token}&lap={n} returns 200 with match data.
     *
     * <p>AC-URL-PATHS-PRESERVED: URL {@code /api/display/overview/matches} + {@code lap} param
     * verbatim.
     */
    @Test
    void matchesByLapReturns200WithMatchDataForValidToken() {
        ResponseEntity<DisplayMatchesResponse> response =
                restTemplate.getForEntity(
                        baseUrl
                                + "/api/display/overview/matches?token="
                                + displayDeviceToken
                                + "&lap=1",
                        DisplayMatchesResponse.class);

        assertThat(response.getStatusCode())
                .as("AC2 — matches endpoint must return 200")
                .isEqualTo(HttpStatus.OK);

        DisplayMatchesResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.phaseId()).isEqualTo(phaseId);
        assertThat(body.lap()).isEqualTo(1);
        assertThat(body.matches()).as("AC2 — must return one match for lap 1").hasSize(1);

        DisplayMatchesResponse.MatchEntry entry = body.matches().get(0);
        assertThat(entry.teamAName()).as("AC2 — teamA name must match").isEqualTo(teamAName);
        assertThat(entry.teamBName()).as("AC2 — teamB name must match").isEqualTo(teamBName);
        assertThat(entry.matchStatus())
                .as("AC2 — ENABLED state maps to PENDING")
                .isEqualTo("PENDING");
        assertThat(entry.fieldNumber()).as("AC2 — fieldNumber must be 1").isEqualTo(1);
    }

    /**
     * AC2: When lap is omitted, use current lap (phase.currentLapNumber=1).
     *
     * <p>AC-URL-PATHS-PRESERVED: {@code lap} parameter is optional per C-8.
     */
    @Test
    void matchesByLapWithoutLapParamUsesCurrentLap() {
        ResponseEntity<DisplayMatchesResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview/matches?token=" + displayDeviceToken,
                        DisplayMatchesResponse.class);

        assertThat(response.getStatusCode())
                .as("AC2 — matches without lap param must use current lap")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().lap())
                .as("AC2 — effective lap must be current phase lap (1)")
                .isEqualTo(1);
    }

    // =========================================================================
    // AC3 — GET /api/display/overview/groups
    // =========================================================================

    /**
     * AC3: GET /api/display/overview/groups returns 200 with D-33-sorted group standings.
     *
     * <p>Team Alpha (3 pts) must rank before Team Beta (0 pts). AC-URL-PATHS-PRESERVED: URL
     * verbatim.
     */
    @Test
    void groupStandingsReturns200WithRankingsInCorrectOrder() {
        ResponseEntity<DisplayGroupStandingsResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview/groups?token=" + displayDeviceToken,
                        DisplayGroupStandingsResponse.class);

        assertThat(response.getStatusCode())
                .as("AC3 — groups endpoint must return 200")
                .isEqualTo(HttpStatus.OK);

        DisplayGroupStandingsResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.phaseId()).isEqualTo(phaseId);
        assertThat(body.groups()).as("AC3 — one group").hasSize(1);

        DisplayGroupStandingsResponse.GroupStandings group1 = body.groups().get(0);
        assertThat(group1.groupNumber()).as("AC3 — groupNumber must be 1").isEqualTo(1);
        assertThat(group1.rankings()).as("AC3 — two teams in group 1").hasSize(2);

        // Team Alpha has 3 points → ranks first (D-33: points DESC)
        DisplayGroupStandingsResponse.TeamRanking rank1 = group1.rankings().get(0);
        assertThat(rank1.position())
                .as("AC3 — D-33: Team Alpha (3 pts) must be position 1")
                .isEqualTo(1);
        assertThat(rank1.teamName()).as("AC3 — position 1 must be Team Alpha").isEqualTo(teamAName);
        assertThat(rank1.points()).as("AC3 — Team Alpha has 3 points").isEqualTo(3);

        // Team Beta has 0 points → ranks second
        DisplayGroupStandingsResponse.TeamRanking rank2 = group1.rankings().get(1);
        assertThat(rank2.position())
                .as("AC3 — D-33: Team Beta (0 pts) must be position 2")
                .isEqualTo(2);
        assertThat(rank2.teamName()).as("AC3 — position 2 must be Team Beta").isEqualTo(teamBName);
    }

    // =========================================================================
    // AC4 — Device token authentication
    // =========================================================================

    /** AC4: Invalid device token → 401 with messageKey in response body (AC9). */
    @Test
    void phaseOverviewReturns401ForInvalidToken() {
        ResponseEntity<ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview?token=invalid-token-xyz",
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — invalid token must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getMessageKey())
                .as("AC4 — 401 response must include messageKey (AC9)")
                .isNotNull()
                .isNotBlank();
    }

    /** AC4: SCORING_TABLET device token → 401 on display endpoint (wrong device type). */
    @Test
    void phaseOverviewReturns401ForScoringTabletToken() {
        ResponseEntity<ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview?token=" + scoringTabletToken,
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — scoring tablet token must return 401 on display endpoint")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** AC4: Invalid token on /api/display/overview/matches → 401. */
    @Test
    void matchesEndpointReturns401ForInvalidToken() {
        ResponseEntity<ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview/matches?token=bad-token",
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — invalid token on matches endpoint must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** AC4: Invalid token on /api/display/overview/groups → 401. */
    @Test
    void groupsEndpointReturns401ForInvalidToken() {
        ResponseEntity<ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview/groups?token=bad-token",
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — invalid token on groups endpoint must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC7 — No active phase → 404 with {"status":"NO_ACTIVE_PHASE"}
    // =========================================================================

    /**
     * AC7: When no active tournament exists, GET /api/display/overview returns 404 with body {@code
     * {"status":"NO_ACTIVE_PHASE"}}.
     *
     * <p>The {@link de.vvwt.tm.display.NoActivePhaseException} thrown by {@link
     * de.vvwt.tm.display.DisplayOverviewService#getPhaseOverview} is caught by {@link
     * GlobalExceptionHandler#handleNoActivePhase} and translated to HTTP 404.
     * AC-GLOBAL-EXCEPTION-HANDLER-IMPORT-UPDATE verified: handler uses {@code
     * display.NoActivePhaseException}.
     */
    @Test
    void phaseOverviewReturns404WhenNoActiveTournament() {
        // Mark the tournament as COMPLETED (no more ACTIVE tournaments)
        Tournament t = tournamentRepository.findById(tournamentId).orElseThrow();
        t.setStatus("COMPLETED");
        tournamentRepository.save(t);

        ResponseEntity<GlobalExceptionHandler.NoActivePhaseResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview?token=" + displayDeviceToken,
                        GlobalExceptionHandler.NoActivePhaseResponse.class);

        assertThat(response.getStatusCode())
                .as("AC7 — no active tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status())
                .as("AC7 — body must contain status=NO_ACTIVE_PHASE")
                .isEqualTo("NO_ACTIVE_PHASE");

        // Restore tournament to ACTIVE for subsequent tests
        t.setStatus("ACTIVE");
        tournamentRepository.save(t);
    }

    // =========================================================================
    // AC6 — Preparation preview
    // =========================================================================

    /** AC6: preparationPreview=true when phase is PENDING with scheduled matches. */
    @Test
    void phaseOverviewReturnsPreparationPreviewTrueForPendingPhaseWithMatches() {
        // Set phase status to PENDING (preparation) — matches already exist with lapNumber=1
        Phase phase = phaseRepository.findById(phaseId).orElseThrow();
        phase.setStatus("PENDING");
        phaseRepository.save(phase);

        ResponseEntity<DisplayPhaseOverviewResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview?token=" + displayDeviceToken,
                        DisplayPhaseOverviewResponse.class);

        assertThat(response.getStatusCode())
                .as("AC6 — PENDING phase with matches must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().preparationPreview())
                .as("AC6 — preparationPreview must be true when phase is PENDING with matches")
                .isTrue();
        assertThat(response.getBody().phaseStatus())
                .as("AC6 — phaseStatus must reflect PENDING")
                .isEqualTo("PENDING");

        // Restore phase to ACTIVE for subsequent tests
        phase.setStatus("ACTIVE");
        phaseRepository.save(phase);
    }

    // =========================================================================
    // AC10 — Missing / empty token → 400
    // =========================================================================

    /**
     * AC10: Missing required {@code token} parameter → 400
     * (MissingServletRequestParameterException).
     */
    @Test
    void missingTokenParameterReturns400() {
        ResponseEntity<ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview", ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC10 — missing token parameter must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC11 — POST/PUT/DELETE → 405 (Method Not Allowed)
    // =========================================================================

    /**
     * AC11: POST to /api/display/overview → 405.
     *
     * <p>Only GET mappings are declared — Spring MVC returns 405 for other methods by default.
     */
    @Test
    void postToOverviewEndpointReturns405() {
        ResponseEntity<Void> response =
                restTemplate.exchange(
                        baseUrl + "/api/display/overview?token=" + displayDeviceToken,
                        HttpMethod.POST,
                        null,
                        Void.class);

        assertThat(response.getStatusCode())
                .as("AC11 — POST to display overview must return 405")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /** AC11: PUT to /api/display/overview → 405. */
    @Test
    void putToOverviewEndpointReturns405() {
        ResponseEntity<Void> response =
                restTemplate.exchange(
                        baseUrl + "/api/display/overview?token=" + displayDeviceToken,
                        HttpMethod.PUT,
                        null,
                        Void.class);

        assertThat(response.getStatusCode())
                .as("AC11 — PUT to display overview must return 405")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /** AC11: DELETE to /api/display/overview → 405. */
    @Test
    void deleteToOverviewEndpointReturns405() {
        ResponseEntity<Void> response =
                restTemplate.exchange(
                        baseUrl + "/api/display/overview?token=" + displayDeviceToken,
                        HttpMethod.DELETE,
                        null,
                        Void.class);

        assertThat(response.getStatusCode())
                .as("AC11 — DELETE to display overview must return 405")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // =========================================================================
    // Test configuration — fixed admin credentials (DEC-44 D2 empirical-refinement pattern)
    // =========================================================================

    /**
     * Per-IT {@link AdminCredentialsProvider} providing a fixed BCrypt-hashed test password.
     *
     * <p>Per DEC-44 §"2026-04-27 Empirical Refinement": this inner class provides {@code @Primary
     * AdminCredentialsProvider} which overrides the non-primary placeholder in {@link
     * WebModuleTestConfig} via {@code spring.main.allow-bean-definition-overriding=true}.
     * Production {@code SecurityFilterChain} + {@code UserDetailsService} remain sole instances. No
     * {@code UserDetailsService} or {@code SecurityFilterChain} substitute bean added
     * (AC-DEC44-D2-EMPIRICAL-REFINEMENT-RESPECT).
     */
    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hashed = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hashed;
        }
    }
}
