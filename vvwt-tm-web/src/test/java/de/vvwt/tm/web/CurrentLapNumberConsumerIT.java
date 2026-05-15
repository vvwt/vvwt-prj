package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.display.DisplayPhaseOverviewResponse;
import de.vvwt.tm.scoring.ScoreEntryResult;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.timer.TimerDataResponse;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for the three consumer defects closed by DEC-65 operationalization (E56S01).
 *
 * <ul>
 *   <li>AC-TEST-DISPLAY-ACTIVE-ROUND-IT — Display overview surfaces {@code currentLap=K} when
 *       ACTIVE in lap K.
 *   <li>AC-TEST-SCORE-TABLET-ACTIVE-MATCH-IT — Score-Tablet match lookup returns the match in
 *       progress on lap 1 (not 204 / empty, which would happen with the old {@code
 *       currentLapNumber=0}).
 *   <li>AC-TEST-TIMER-CURRENTLAP-IT — Timer response reports the running lap K, not K−1.
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 Iron Law Q-1a — RED-first; RED commit = E56S01 RED commit; GREEN = production fix.
 *   <li>DEC-44 D1 — {@code @AutoConfigureTestRestTemplate @SpringBootTest(RANDOM_PORT)} +
 *       {@code @Import({WebModuleTestConfig, TestAdminCredentials})}.
 *   <li>DEC-44 D2 — per-IT inner {@code TestAdminCredentials} provides {@code @Primary
 *       AdminCredentialsProvider}.
 *   <li>DEC-65 D-1 — ACTIVE mid-phase lap K: {@code currentLapNumber == K} where K ∈ [1,
 *       lapCount].
 * </ul>
 *
 * @see de.vvwt.tm.web.DisplayOverviewControllerIT
 * @see de.vvwt.tm.web.timer.TimerControllerIT
 * @since E56S01
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e56s01consumeritdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, CurrentLapNumberConsumerIT.TestAdminCredentials.class})
class CurrentLapNumberConsumerIT {

    static final String TEST_PASSWORD = "CurrentLapNumberConsumerIT56S01";

    // -------------------------------------------------------------------------
    // Field injection
    // -------------------------------------------------------------------------

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;

    @Autowired private TournamentRepository tournamentRepository;

    @Autowired private PhaseRepository phaseRepository;

    @Autowired private DeviceRepository deviceRepository;

    @Autowired private MatchRepository matchRepository;

    @Autowired private TeamRepository teamRepository;

    @Autowired private TeamAvatarRepository teamAvatarRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // Shared state set by setupTestData()
    // -------------------------------------------------------------------------

    private String baseUrl;
    private UUID defaultLocationId;

    private UUID tournamentId;
    private UUID phaseId;
    private String displayDeviceToken;
    private String scoringTabletToken;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        tenantContextBinder.bindDefaultTenant();
        defaultLocationId = tenantContextBinder.getDefaultLocationId();
        cleanupTestData();
        setupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
        tenantContextBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // Test data helpers
    // -------------------------------------------------------------------------

    /**
     * Removes all test data in FK-safe order (children before parents). Called both before and after
     * each test to ensure isolation even after a prior run left data behind.
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
        jdbcTemplate.update("DELETE FROM phase_breaks");
    }

    /**
     * Creates a minimal ACTIVE tournament + ACTIVE phase with {@code currentLapNumber=1} (lap 1
     * running, DEC-65 D-2), one OPEN match at {@code lapNumber=1, fieldNumber=1}, a DISPLAY device,
     * and a SCORING_TABLET device assigned to field 1.
     *
     * <p>This setup is the "lap 1 actively running" scenario shared by all three consumer IT tests.
     */
    private void setupTestData() {
        LocalDateTime now = LocalDateTime.now();

        // ACTIVE tournament
        tournamentId = UUID.randomUUID();
        Tournament tournament =
                new Tournament(
                        tournamentId,
                        "E56S01 Consumer IT Tournament",
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

        // ACTIVE phase, lap 1 running (DEC-65 D-2: currentLapNumber == 1)
        phaseId = UUID.randomUUID();
        Phase phase =
                new Phase(phaseId, tournamentId, 1, "Vorrunde E56S01", "ACTIVE", 1, now);
        phaseRepository.save(phase);

        // Two teams (required by team_avatar FK chain)
        UUID teamAId = UUID.randomUUID();
        UUID teamBId = UUID.randomUUID();
        teamRepository.save(new Team(teamAId, tournamentId, 1, "Team Alpha E56S01", true, false, false, now));
        teamRepository.save(new Team(teamBId, tournamentId, 2, "Team Beta E56S01", true, false, false, now));

        // Two avatars (required by match.member_avatar_* FK)
        UUID avatarAId = UUID.randomUUID();
        UUID avatarBId = UUID.randomUUID();
        teamAvatarRepository.save(new TeamAvatar(avatarAId, tournamentId, phaseId, 1, 1, teamAId, null, now));
        teamAvatarRepository.save(new TeamAvatar(avatarBId, tournamentId, phaseId, 1, 2, teamBId, null, now));

        // OPEN match at lap 1, field 1 — the match the Score-Tablet must find
        UUID matchId = UUID.randomUUID();
        Match match =
                new Match(
                        matchId,
                        tournamentId,
                        phaseId,
                        avatarAId,
                        avatarBId,
                        MatchState.OPEN.getLegacyCode(),
                        1, // setLimit
                        1, // lapNumber
                        1, // fieldNumber
                        null,
                        null,
                        null,
                        now);
        matchRepository.save(match);

        // DISPLAY device (for Display overview AC)
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
                        "Display E56S01",
                        null);
        deviceRepository.save(displayDevice);

        // SCORING_TABLET device assigned to field 1 (for Score-Tablet AC)
        scoringTabletToken = UUID.randomUUID().toString();
        Device tablet =
                new Device(
                        UUID.randomUUID(),
                        null,
                        scoringTabletToken,
                        "0000",
                        Device.TYPE_SCORING_TABLET,
                        1, // assignedField = 1
                        Device.STATUS_ASSIGNED,
                        now,
                        null,
                        "Tablet E56S01",
                        null);
        deviceRepository.save(tablet);
    }

    // =========================================================================
    // AC-TEST-DISPLAY-ACTIVE-ROUND-IT (DEC-65 D-1 / E56S01)
    // =========================================================================

    /**
     * AC-TEST-DISPLAY-ACTIVE-ROUND-IT: Display overview surfaces {@code currentLap == 1} when the
     * phase is ACTIVE in lap 1.
     *
     * <p>DEC-65 D-1: ACTIVE mid-phase lap K → {@code currentLapNumber == K}. The FE {@code
     * CourtGrid.svelte} uses {@code lap === currentLap} for the active-round highlight; this test
     * closes the latent Display highlight defect by asserting the backend emits K=1, not the old
     * 0-based value 0.
     */
    @Test
    void displayOverview_activeLap1_surfacesCurrentLapEqualsOne() {
        ResponseEntity<DisplayPhaseOverviewResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/display/overview?token=" + displayDeviceToken,
                        DisplayPhaseOverviewResponse.class);

        assertThat(response.getStatusCode())
                .as("Display overview must return 200 for valid DISPLAY token")
                .isEqualTo(HttpStatus.OK);

        DisplayPhaseOverviewResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.phaseStatus())
                .as("Phase status must be ACTIVE")
                .isEqualTo("ACTIVE");
        assertThat(body.currentLap())
                .as(
                        "AC-TEST-DISPLAY-ACTIVE-ROUND-IT (DEC-65 D-1): currentLap must be 1"
                                + " (lap 1 running), not 0 (old 0-based counter)")
                .isEqualTo(1);
    }

    // =========================================================================
    // AC-TEST-SCORE-TABLET-ACTIVE-MATCH-IT (DEC-65 D-1 / E56S01)
    // =========================================================================

    /**
     * AC-TEST-SCORE-TABLET-ACTIVE-MATCH-IT: Score-Tablet active-match lookup returns the in-progress
     * match during lap 1 (HTTP 200 with match data, not 204 No Content).
     *
     * <p>DEC-65 D-1 operationalization: {@code DefaultScoreEntryService.resolveActiveMatch()} calls
     * {@code findByFieldNumberAndLapNumber(field, phase.currentLapNumber)}. With the old 0-based
     * counter, {@code currentLapNumber=0} while all matches carry {@code lapNumber=1} → query returns
     * empty → 204. With the corrected 1-based init-hook, {@code currentLapNumber=1} → query finds
     * the OPEN match on field 1 → 200.
     */
    @Test
    void scoreTablet_activeLap1_findsMatchOnField1() {
        ResponseEntity<ScoreEntryResult> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/score/match?field=1&token=" + scoringTabletToken,
                        ScoreEntryResult.class);

        assertThat(response.getStatusCode())
                .as(
                        "AC-TEST-SCORE-TABLET-ACTIVE-MATCH-IT (DEC-65 D-1): Score-Tablet must"
                                + " return 200 (match found) for field=1 during lap 1,"
                                + " not 204 (old 0-based currentLapNumber=0 returned empty)")
                .isEqualTo(HttpStatus.OK);

        ScoreEntryResult body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.lapNumber())
                .as("Match returned must be at lapNumber=1")
                .isEqualTo(1);
        assertThat(body.fieldNumber())
                .as("Match returned must be at fieldNumber=1")
                .isEqualTo(1);
    }

    // =========================================================================
    // AC-TEST-TIMER-CURRENTLAP-IT (DEC-65 D-1 / E56S01)
    // =========================================================================

    /**
     * AC-TEST-TIMER-CURRENTLAP-IT: Timer response reports the running lap (1), not the old 0-based
     * value (0), during initial ACTIVE play of lap 1.
     *
     * <p>DEC-65 D-1 operationalization: {@code DefaultTimerDataService} reads {@code
     * phase.currentLapNumber} directly into {@link TimerDataResponse}. With the corrected 1-based
     * init-hook, the Timer surfaces lap 1 immediately when the phase becomes ACTIVE — closing the
     * latent off-by-one defect.
     */
    @Test
    void timer_activeLap1_reportsCurrentLapNumberOne() {
        ResponseEntity<TimerDataResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/timer/tournaments/" + tournamentId,
                        TimerDataResponse.class);

        assertThat(response.getStatusCode())
                .as("Timer endpoint must return 200 for valid ACTIVE tournament")
                .isEqualTo(HttpStatus.OK);

        TimerDataResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCurrentLapNumber())
                .as(
                        "AC-TEST-TIMER-CURRENTLAP-IT (DEC-65 D-1): Timer must report"
                                + " currentLapNumber=1 during lap 1, not 0 (old off-by-one)")
                .isEqualTo(1);
        assertThat(body.getCurrentPhaseNumber())
                .as("Timer must report phase sequence number 1")
                .isEqualTo(1);
    }

    // =========================================================================
    // Test-local admin credentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
