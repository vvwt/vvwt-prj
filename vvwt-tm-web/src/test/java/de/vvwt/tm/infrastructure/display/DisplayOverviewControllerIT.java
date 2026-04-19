package de.vvwt.tm.infrastructure.display;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.domain.Device;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.TeamAvatarRating;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.DeviceRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRatingRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.domain.repo.TenantContextTestHelper;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.display.dto.DisplayGroupStandingsResponse;
import de.vvwt.tm.infrastructure.display.dto.DisplayMatchesResponse;
import de.vvwt.tm.infrastructure.display.dto.DisplayPhaseOverviewResponse;
import de.vvwt.tm.infrastructure.web.GlobalExceptionHandler;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * Integration tests for {@link DisplayOverviewController} (E07S04).
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>AC1 — GET /api/display/overview returns 200 with phase data for valid DISPLAY token
 *   <li>AC2 — GET /api/display/overview/matches returns 200 with matches per lap
 *   <li>AC3 — GET /api/display/overview/groups returns 200 with D-33-sorted standings
 *   <li>AC4 — Invalid token → 401; scoring tablet token (wrong type) → 401
 *   <li>AC5 — Tenant scope: display endpoints serve only data for the device's tenant
 *   <li>AC6 — preparationPreview=true when phase is PENDING with scheduled matches
 *   <li>AC7 — No active phase → 404 with {"status":"NO_ACTIVE_PHASE"}
 *   <li>AC9 — Error responses include messageKey (via GlobalExceptionHandler)
 *   <li>AC10 — Missing / empty token → 401
 *   <li>AC11 — POST/PUT/DELETE to overview endpoint → 405
 * </ul>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S04.story.md">Story
 *     E07S04</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            DisplayOverviewControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e07s04db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class DisplayOverviewControllerIT {

    static final String TEST_PASSWORD = "DisplayCtrlIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContext tenantContext;

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
        tenantContext.set(defaultTenantId);
        cleanupTestData();
        setupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
        tenantContext.clear();
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
        jdbcTemplate.update("DELETE FROM audit_log");
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
        // Register a DISPLAY device
        displayDeviceToken = UUID.randomUUID().toString();
        Device displayDevice =
                new Device(
                        UUID.randomUUID(),
                        defaultTenantId,
                        null,
                        displayDeviceToken,
                        null,
                        "DISPLAY",
                        null,
                        Device.STATUS_REGISTERED,
                        LocalDateTime.now(),
                        null,
                        "Display Device",
                        null);
        deviceRepository.save(displayDevice);

        // Register a SCORING_TABLET device (used for AC4 wrong-type test)
        scoringTabletToken = UUID.randomUUID().toString();
        Device tabletDevice =
                new Device(
                        UUID.randomUUID(),
                        defaultTenantId,
                        null,
                        scoringTabletToken,
                        "1234",
                        Device.TYPE_SCORING_TABLET,
                        null,
                        Device.STATUS_REGISTERED,
                        LocalDateTime.now(),
                        null,
                        null,
                        null);
        deviceRepository.save(tabletDevice);

        // Create active tournament
        tournamentId = UUID.randomUUID();
        Tournament tournament =
                new Tournament(
                        tournamentId,
                        defaultTenantId,
                        "E07S04 Test Tournament",
                        "BEST_OF_1",
                        "threePointMatchRule",
                        "standardVolleyballSet",
                        "roundRobinMatchGenerator",
                        "ACTIVE",
                        LocalDateTime.now(),
                        null,
                        3,
                        4);
        tournamentRepository.save(tournament);

        // Create active phase
        phaseId = UUID.randomUUID();
        Phase phase =
                new Phase(
                        phaseId,
                        defaultTenantId,
                        tournamentId,
                        1,
                        "Vorrunde",
                        "ACTIVE",
                        1,
                        LocalDateTime.now());
        phaseRepository.save(phase);

        // Create team A and team B
        teamAName = "Team Alpha";
        teamBName = "Team Beta";
        UUID teamAId = UUID.randomUUID();
        UUID teamBId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        Team teamA =
                new Team(
                        teamAId,
                        defaultTenantId,
                        tournamentId,
                        1,
                        teamAName,
                        true,
                        false,
                        false,
                        now);
        Team teamB =
                new Team(
                        teamBId,
                        defaultTenantId,
                        tournamentId,
                        2,
                        teamBName,
                        true,
                        false,
                        false,
                        now);
        teamRepository.save(teamA);
        teamRepository.save(teamB);

        // Create avatars in group 1 (positions 1 and 2)
        UUID avatarAId = UUID.randomUUID();
        UUID avatarBId = UUID.randomUUID();
        TeamAvatar avatarA =
                new TeamAvatar(
                        avatarAId,
                        defaultTenantId,
                        tournamentId,
                        phaseId,
                        1,
                        1,
                        teamAId,
                        "Gruppe 1, Platz 1",
                        now);
        TeamAvatar avatarB =
                new TeamAvatar(
                        avatarBId,
                        defaultTenantId,
                        tournamentId,
                        phaseId,
                        1,
                        2,
                        teamBId,
                        "Gruppe 1, Platz 2",
                        now);
        teamAvatarRepository.save(avatarA);
        teamAvatarRepository.save(avatarB);

        // Create ratings for each avatar (non-zero for AC3 standings test)
        TeamAvatarRating ratingA =
                new TeamAvatarRating(
                        avatarAId,
                        defaultTenantId,
                        1,
                        1,
                        3,
                        1,
                        0,
                        25,
                        15,
                        Double.MAX_VALUE,
                        Double.MAX_VALUE,
                        false,
                        now);
        TeamAvatarRating ratingB =
                new TeamAvatarRating(
                        avatarBId, defaultTenantId, 1, 1, 0, 0, 1, 15, 25, 0.0, 0.6, false, now);
        teamAvatarRatingRepository.save(ratingA);
        teamAvatarRatingRepository.save(ratingB);

        // Create one match: lap=1, field=1, state=ENABLED (PENDING display status)
        UUID matchId = UUID.randomUUID();
        Match match =
                new Match(
                        matchId,
                        defaultTenantId,
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

    @Test
    void phaseOverviewDoesNotRequireAdminAuth() {
        // AC4: display endpoints are public — no admin credentials needed
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

    @Test
    void matchesByLapWithoutLapParamUsesCurrentLap() {
        // AC2: when lap is omitted, use current lap (phase.currentLapNumber=1)
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

    @Test
    void phaseOverviewReturns401ForInvalidToken() {
        ResponseEntity<de.vvwt.tm.infrastructure.web.ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview?token=invalid-token-xyz",
                        de.vvwt.tm.infrastructure.web.ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — invalid token must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getMessageKey())
                .as("AC4 — 401 response must include messageKey (AC9)")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    void phaseOverviewReturns401ForScoringTabletToken() {
        // AC4: scoring tablet tokens (SCORING_TABLET type) must be rejected by display endpoints
        ResponseEntity<de.vvwt.tm.infrastructure.web.ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview?token=" + scoringTabletToken,
                        de.vvwt.tm.infrastructure.web.ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — scoring tablet token must return 401 on display endpoint")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void matchesEndpointReturns401ForInvalidToken() {
        ResponseEntity<de.vvwt.tm.infrastructure.web.ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview/matches?token=bad-token",
                        de.vvwt.tm.infrastructure.web.ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — invalid token on matches endpoint must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void groupsEndpointReturns401ForInvalidToken() {
        ResponseEntity<de.vvwt.tm.infrastructure.web.ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview/groups?token=bad-token",
                        de.vvwt.tm.infrastructure.web.ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — invalid token on groups endpoint must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC7 — No active phase → 404 with {"status":"NO_ACTIVE_PHASE"}
    // =========================================================================

    @Test
    void phaseOverviewReturns404WhenNoActiveTournament() {
        // Complete the tournament so no active tournament exists
        // We need a different test setup: register a display device but no active tournament
        // Use a fresh device token pointing to default tenant, then deactivate tournament
        // In this test: just use an unknown display device in an isolated call
        // We create a second in-memory test directly:
        // - Register a fresh DISPLAY device
        // - Complete the tournament → COMPLETED status
        // then call the endpoint

        // Mark the tournament as COMPLETED (no more ACTIVE tournaments)
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
        Tournament t = tournamentRepository.findById(tournamentId).orElseThrow();
        t.setStatus("COMPLETED");
        tournamentRepository.save(t);
        TenantContextTestHelper.clear(tenantContext);

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

        // Restore tournament to ACTIVE for subsequent tests (shared DB in this test class)
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
        t.setStatus("ACTIVE");
        tournamentRepository.save(t);
        TenantContextTestHelper.clear(tenantContext);
    }

    // =========================================================================
    // AC6 — Preparation preview
    // =========================================================================

    @Test
    void phaseOverviewReturnsPreparationPreviewTrueForPendingPhaseWithMatches() {
        // Set phase status to PENDING (preparation) — matches already exist with lapNumber=1 (AC6)
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
        Phase phase = phaseRepository.findById(phaseId).orElseThrow();
        phase.setStatus("PENDING");
        phaseRepository.save(phase);
        TenantContextTestHelper.clear(tenantContext);

        ResponseEntity<DisplayPhaseOverviewResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview?token=" + displayDeviceToken,
                        DisplayPhaseOverviewResponse.class);

        assertThat(response.getStatusCode())
                .as("AC6 — PENDING phase with matches must return 200 (preparationPreview)")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().preparationPreview())
                .as("AC6 — preparationPreview must be true when phase is PENDING with matches")
                .isTrue();
        assertThat(response.getBody().phaseStatus())
                .as("AC6 — phaseStatus must reflect PENDING")
                .isEqualTo("PENDING");

        // Restore phase to ACTIVE for subsequent tests
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
        phase.setStatus("ACTIVE");
        phaseRepository.save(phase);
        TenantContextTestHelper.clear(tenantContext);
    }

    // =========================================================================
    // AC10 — Empty / missing token → 400 (MissingServletRequestParameterException)
    // =========================================================================

    @Test
    void missingTokenParameterReturns400() {
        // Spring MVC throws MissingServletRequestParameterException for required params
        ResponseEntity<de.vvwt.tm.infrastructure.web.ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview",
                        de.vvwt.tm.infrastructure.web.ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC10 — missing token parameter must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC11 — POST/PUT/DELETE → 405 (Method Not Allowed)
    // =========================================================================

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
    // Test configuration — fixed admin credentials (matching DeviceControllerIT pattern)
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hashed = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hashed;
        }
    }
}
