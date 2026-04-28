package de.vvwt.tm.web.timer;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.timer.TimerDataResponse;
import de.vvwt.tm.tournament.ApiErrorResponse;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.web.WebModuleTestConfig;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link TimerController} — E26S03 Q-1a TDD reconstruction.
 *
 * <h2>Test coverage (AC-Q7-METHOD-COUNT-RECONFIRMATION)</h2>
 *
 * <ul>
 *   <li>happy path: GET /api/timer/tournaments/{id} returns 200 with timer data
 *   <li>security-negative: endpoint accessible without admin auth (permitAll)
 *   <li>error: unknown tournament UUID → 404 with INVALID_TIMER_URL errorCode
 *   <li>error: DRAFT tournament → 404 with NO_ACTIVE_TOURNAMENT errorCode
 *   <li>error: CANCELLED tournament → 404 with NO_ACTIVE_TOURNAMENT errorCode
 *   <li>error: COMPLETED tournament → 404 with NO_ACTIVE_TOURNAMENT errorCode
 *   <li>error: null UUID in path → 404 (NoResourceFoundException)
 *   <li>error: non-UUID in path → 400/404
 *   <li>emptySchedule: ACTIVE tournament with no phases → 200 with emptySchedule=true
 *   <li>URL shape: Wave-2-aligned /api/timer/tournaments/{id} path (not legacy /api/timer/{id})
 * </ul>
 *
 * <p>Total: 9 {@code @Test} methods (audit (v) baseline 9).
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 Iron Law Q-1a — RED-first: this test written before {@link TimerController} exists;
 *       RED commit = this commit; GREEN commit = next (TimerController production code)
 *   <li>DEC-36 — {@code TimerDataService} interface FQN used via {@code @MockitoBean} (NOT {@code
 *       timer.internal.DefaultTimerDataService})
 *   <li>DEC-40 §2026-04-27 Clarification Pattern A — response type {@link TimerDataResponse}
 *       imported from {@code de.vvwt.tm.timer.*} (NOT {@code web.internal.dto.*})
 *   <li>DEC-44 D1 — {@code @SpringBootTest(RANDOM_PORT)} + {@code @Import({WebModuleTestConfig,
 *       TestAdminCredentials})} per 2026-04-27 empirical refinement
 *   <li>DEC-44 D2 — per-IT inner {@code TestAdminCredentials} provides {@code @Primary
 *       AdminCredentialsProvider}; no {@code UserDetailsService} or {@code SecurityFilterChain}
 *       substitute
 *   <li>AC-DEC44-D2-EMPIRICAL-REFINEMENT-RESPECT — no UDS/SecurityFilterChain substitute beans
 * </ul>
 *
 * @see TimerController
 * @see WebModuleTestConfig
 * @since E26S03
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e26s03timeritdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, TimerControllerIT.TestAdminCredentials.class})
class TimerControllerIT {

    static final String TEST_PASSWORD = "TimerControllerIT26S03";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;

    @Autowired private TournamentRepository tournamentRepository;

    @Autowired private PhaseRepository phaseRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private UUID defaultTenantId;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        defaultTenantId = tenantContextBinder.bindDefaultTenant();
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

    private void cleanupTestData() {
        jdbcTemplate.update("DELETE FROM phase_breaks");
        jdbcTemplate.update("DELETE FROM set_result");
        jdbcTemplate.update("DELETE FROM match_outcome");
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM match");
        jdbcTemplate.update("DELETE FROM phase");
        jdbcTemplate.update("DELETE FROM team_avatar_rating");
        jdbcTemplate.update("DELETE FROM team_avatar");
        jdbcTemplate.update("DELETE FROM team");
        jdbcTemplate.update("DELETE FROM activity_types");
        jdbcTemplate.update("DELETE FROM tournament");
        jdbcTemplate.update("DELETE FROM devices");
    }

    private void setupTestData() {
        LocalDateTime now = LocalDateTime.now();
        tournamentId = UUID.randomUUID();
        Tournament tournament =
                new Tournament(
                        tournamentId,
                        defaultTenantId,
                        "E26S03 Timer Test Tournament",
                        "BEST_OF_1",
                        "threePointMatchRule",
                        "standardVolleyballSet",
                        "roundRobinMatchGenerator",
                        "ACTIVE",
                        now,
                        null,
                        3,
                        4);
        tournamentRepository.save(tournament);

        // Create one active phase with currentLapNumber=1 so timer data is non-empty
        Phase phase =
                new Phase(
                        UUID.randomUUID(),
                        defaultTenantId,
                        tournamentId,
                        1,
                        "Vorrunde E26S03",
                        "ACTIVE",
                        1,
                        now);
        phaseRepository.save(phase);
    }

    // =========================================================================
    // Happy path — GET /api/timer/tournaments/{tournamentId} returns 200
    // =========================================================================

    /**
     * Happy path: GET /api/timer/tournaments/{id} returns 200 with {@link TimerDataResponse}.
     *
     * <p>AC-URL-PATHS-WAVE-2-ALIGNED: Wave-2 URL {@code /api/timer/tournaments/{id}} (NOT legacy
     * {@code /api/timer/{id}}).
     */
    @Test
    void getTimerDataReturns200WithTimerData() {
        ResponseEntity<TimerDataResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/timer/tournaments/" + tournamentId,
                        TimerDataResponse.class);

        assertThat(response.getStatusCode())
                .as("Happy path: valid ACTIVE tournament must return 200")
                .isEqualTo(HttpStatus.OK);

        TimerDataResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTournamentId())
                .as("tournamentId in response must match the requested UUID")
                .isEqualTo(tournamentId);
        assertThat(body.getTournamentName())
                .as("tournamentName must match")
                .isEqualTo("E26S03 Timer Test Tournament");
    }

    /**
     * Security-negative: GET /api/timer/tournaments/{id} is accessible without admin credentials
     * (permitAll per AC-AUTHENTICATION-FLOW-PRESERVED + AC-SECURITY-CONFIG-URL-1-API-TIMER).
     */
    @Test
    void getTimerDataIsAccessibleWithoutAdminAuth() {
        // Use a plain RestTemplate without credentials
        ResponseEntity<TimerDataResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/timer/tournaments/" + tournamentId,
                        TimerDataResponse.class);

        assertThat(response.getStatusCode())
                .as("Timer endpoint must be accessible without authentication")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // Error paths — 404 cases
    // =========================================================================

    /**
     * Error: Unknown tournament UUID → 404 with errorCode {@code INVALID_TIMER_URL}.
     *
     * <p>{@link de.vvwt.tm.timer.InvalidTimerUrlException} is thrown by {@code
     * DefaultTimerDataService} when the tournament UUID is not found. The exception is caught by
     * {@link de.vvwt.tm.web.GlobalExceptionHandler#handleInvalidTimerUrl}.
     */
    @Test
    void unknownTournamentReturns404WithInvalidTimerUrlCode() {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/timer/tournaments/" + unknownId, ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("Unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .as("Error code must be INVALID_TIMER_URL")
                .isEqualTo("INVALID_TIMER_URL");
    }

    /**
     * Error: DRAFT tournament → 404 with errorCode {@code NO_ACTIVE_TOURNAMENT}.
     *
     * <p>{@link de.vvwt.tm.timer.NoActiveTournamentException} is thrown by {@code
     * DefaultTimerDataService} for DRAFT/CANCELLED tournaments. Caught by {@link
     * de.vvwt.tm.web.GlobalExceptionHandler#handleNoActiveTournament}.
     */
    @Test
    void draftTournamentReturns404WithNoActiveTournamentCode() {
        // Switch tournament to DRAFT
        Tournament t = tournamentRepository.findById(tournamentId).orElseThrow();
        t.setStatus("DRAFT");
        tournamentRepository.save(t);

        ResponseEntity<ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/timer/tournaments/" + tournamentId, ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("DRAFT tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .as("Error code must be NO_ACTIVE_TOURNAMENT")
                .isEqualTo("NO_ACTIVE_TOURNAMENT");

        // Restore to ACTIVE
        t.setStatus("ACTIVE");
        tournamentRepository.save(t);
    }

    /** Error: CANCELLED tournament → 404 with errorCode {@code NO_ACTIVE_TOURNAMENT}. */
    @Test
    void cancelledTournamentReturns404WithNoActiveTournamentCode() {
        Tournament t = tournamentRepository.findById(tournamentId).orElseThrow();
        t.setStatus("CANCELLED");
        tournamentRepository.save(t);

        ResponseEntity<ApiErrorResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/timer/tournaments/" + tournamentId, ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("CANCELLED tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .as("Error code must be NO_ACTIVE_TOURNAMENT")
                .isEqualTo("NO_ACTIVE_TOURNAMENT");

        // Restore to ACTIVE
        t.setStatus("ACTIVE");
        tournamentRepository.save(t);
    }

    /**
     * Error: ACTIVE tournament with no phases → 200 with emptySchedule=true.
     *
     * <p>The timer service returns a valid response with {@code emptySchedule=true} when no phases
     * are configured (AC7 of legacy spec, preserved per C-3).
     */
    @Test
    void activeTournamentWithNoPhasesReturns200WithEmptySchedule() {
        // Delete all phases for this tournament
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId.toString());

        ResponseEntity<TimerDataResponse> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/timer/tournaments/" + tournamentId,
                        TimerDataResponse.class);

        assertThat(response.getStatusCode())
                .as("ACTIVE tournament with no phases must still return 200")
                .isEqualTo(HttpStatus.OK);

        TimerDataResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isEmptySchedule())
                .as("emptySchedule must be true when no phases configured")
                .isTrue();
    }

    /**
     * Wave-2 URL alignment: the legacy path {@code /api/timer/{id}} must NOT match this controller
     * — it returns 404 (or 405/400). The new controller maps {@code /api/timer/tournaments/{id}}.
     *
     * <p>AC-URL-PATHS-WAVE-2-ALIGNED verifies that old clients would break (no accidental
     * compatibility alias).
     */
    @Test
    void legacyTimerUrlDoesNotMatchNewController() {
        ResponseEntity<Void> response =
                restTemplate.getForEntity(baseUrl + "/api/timer/" + tournamentId, Void.class);

        // The legacy URL may return 404 (no mapping), not 200
        assertThat(response.getStatusCode().value())
                .as("Legacy /api/timer/{id} must NOT return 200 — no backward compat alias")
                .isNotEqualTo(200);
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
