package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.scoring.ScoringResult;
import de.vvwt.tm.scoring.ScoringRule;
import de.vvwt.tm.scoring.TournamentRuleResolver;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.web.internal.dto.MatchCorrectionRequest;
import de.vvwt.tm.web.internal.dto.MatchCorrectionResultResponse;
import de.vvwt.tm.web.internal.dto.SetScoreEntry;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link MatchCorrectionController} — full HTTP stack (E48S25, DEC-44,
 * AC-TEST-CONTROLLER-IT-CORRECTION).
 *
 * <h2>DEC-44 module annotation</h2>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)} per
 * DEC-44 D1.
 *
 * <h2>Security coverage</h2>
 *
 * <ul>
 *   <li>Unauthenticated POST → 401
 *   <li>Authenticated admin POST → 200 (happy path) or 404 (match not found)
 * </ul>
 *
 * <h2>Happy path</h2>
 *
 * <p>Inserts a FINISHED_WINNER1 match in an ACTIVE phase. Submits a correction. Verifies HTTP 200
 * and {@code auditOnly=false} in the response.
 *
 * <h2>CANCELED audit-only</h2>
 *
 * <p>Inserts a CANCELED match in an ACTIVE phase. Submits correction. Verifies HTTP 200 and {@code
 * auditOnly=true}.
 *
 * <h2>Guard: phase not ACTIVE → 409</h2>
 *
 * <p>Inserts a PENDING phase. Verifies HTTP 409.
 *
 * <h2>DEC-74 lap-advance (AC-TEST-CORRECTION-ADVANCE-IT)</h2>
 *
 * <p>Verifies end-to-end that correcting the last match in the current lap advances {@code
 * phase.currentLapNumber} forward-only, using the guard added in E56S02 (DEC-74).
 *
 * @see MatchCorrectionController
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="DEC-74">DEC-74 — path-independent lap-advance</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 * @see <a href="E56S02">E56S02 — DefaultMatchCorrectionService DEC-74 operationalization</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, MatchCorrectionControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("MatchCorrectionControllerIT — E48S25 AC-TEST-CONTROLLER-IT-CORRECTION (minimalist)")
class MatchCorrectionControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E48S25MatchCorrectionControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    /**
     * The {@code @Primary} mock TournamentRuleResolver injected by {@link WebModuleTestConfig}.
     * Stubbed in {@code setUp()} so that {@code DefaultMatchCorrectionService.refreshAvatarRating}
     * receives a non-null ResolvedRules for the happy-path tests (E48S25).
     */
    @Autowired private TournamentRuleResolver ruleResolverMock;

    private String baseUrl;
    private TestRestTemplate authed;

    // Fixture IDs — allocated per test class
    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID matchId;
    private UUID avatar1Id;
    private UUID avatar2Id;
    private UUID team1Id;
    private UUID team2Id;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        // Stub the @Primary TournamentRuleResolver mock (from WebModuleTestConfig) so that
        // DefaultMatchCorrectionService.refreshAvatarRating() can resolve scoring rules without
        // NPE.
        // The ScoringRule stub returns 1 point per set won (setPoints semantics, simplified).
        ScoringRule scoringRuleStub = Mockito.mock(ScoringRule.class);
        when(scoringRuleStub.calculatePoints(any(), any()))
                .thenAnswer(
                        inv -> {
                            de.vvwt.tm.tournament.MatchOutcome mo =
                                    inv.getArgument(0, de.vvwt.tm.tournament.MatchOutcome.class);
                            return new ScoringResult(mo.getTeam1SetsWon(), mo.getTeam2SetsWon());
                        });
        when(ruleResolverMock.resolve(any()))
                .thenReturn(new TournamentRuleResolver.ResolvedRules(scoringRuleStub, null));

        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        matchId = UUID.randomUUID();
        avatar1Id = UUID.randomUUID();
        avatar2Id = UUID.randomUUID();
        team1Id = UUID.randomUUID();
        team2Id = UUID.randomUUID();

        // Insert location
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "MatchCorrectionIT Location");

        // Insert ACTIVE tournament (BEST_OF_3)
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "MatchCorrectionIT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                2,
                4);

        // Insert teams (team_number NOT NULL, participate/without_assessment/referee_assignment
        // have defaults)
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description) VALUES (?, ?, ?,"
                        + " ?)",
                team1Id,
                tournamentId,
                1,
                "Team 1");
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description) VALUES (?, ?, ?,"
                        + " ?)",
                team2Id,
                tournamentId,
                2,
                "Team 2");

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM set_result WHERE match_id = ?", matchId);
        jdbcTemplate.update("DELETE FROM match_outcome WHERE match_id = ?", matchId);
        jdbcTemplate.update("DELETE FROM match WHERE id = ?", matchId);
        jdbcTemplate.update(
                "DELETE FROM team_avatar_rating WHERE avatar_id IN (?, ?)", avatar1Id, avatar2Id);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE phase_id = ?", phaseId);
        jdbcTemplate.update("DELETE FROM phase WHERE id = ?", phaseId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // Security: unauthenticated POST → 401
    // =========================================================================

    @Test
    @DisplayName("AC-SECURITY-CORRECTION-AUTH: unauthenticated POST returns 401")
    void unauthenticatedPost_correction_returns401() throws Exception {
        MatchCorrectionRequest request =
                new MatchCorrectionRequest(
                        tournamentId, phaseId, List.of(new SetScoreEntry(0, 25, 10)), null);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<MatchCorrectionRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        new URI(baseUrl + "/api/matches/" + matchId + "/correction"),
                        HttpMethod.POST,
                        entity,
                        String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated correction request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Happy path: FINISHED_WINNER1 match in ACTIVE phase → 200 + auditOnly=false
    // (AC-TEST-CORRECT-SET-FINISHED)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-CORRECT-SET-FINISHED: FINISHED_WINNER1 match in ACTIVE phase → 200 +"
                    + " auditOnly=false")
    void correctionOnFinishedMatch_activePhaseTournament_returns200() throws Exception {
        tenantBinder.bindDefaultTenant();
        seedActivePhaseWithFinishedMatch(MatchState.FINISHED_WINNER1);
        tenantBinder.unbind();

        MatchCorrectionRequest request =
                new MatchCorrectionRequest(
                        tournamentId,
                        phaseId,
                        List.of(new SetScoreEntry(0, 25, 10), new SetScoreEntry(1, 25, 15)),
                        "Correction test");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<MatchCorrectionRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<MatchCorrectionResultResponse> response =
                authed.exchange(
                        new URI(baseUrl + "/api/matches/" + matchId + "/correction"),
                        HttpMethod.POST,
                        entity,
                        MatchCorrectionResultResponse.class);

        assertThat(response.getStatusCode())
                .as("correction on finished match in ACTIVE phase must return 200")
                .isEqualTo(HttpStatus.OK);

        MatchCorrectionResultResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.auditOnly())
                .as("auditOnly must be false for FINISHED_* corrections")
                .isFalse();
        assertThat(body.newMatchState())
                .as("newMatchState must be a valid MatchState name")
                .isIn("FINISHED_WINNER1", "FINISHED_WINNER2", "FINISHED_STANDOFF", "ONCHECK");
    }

    // =========================================================================
    // CANCELED audit-only: → 200 + auditOnly=true
    // (AC-TEST-CANCELED-AUDIT-ONLY)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-CANCELED-AUDIT-ONLY: CANCELED match in ACTIVE phase → 200 + auditOnly=true")
    void correctionOnCanceledMatch_activePhaseTournament_returns200AuditOnly() throws Exception {
        tenantBinder.bindDefaultTenant();
        seedActivePhaseWithFinishedMatch(MatchState.CANCELED);
        tenantBinder.unbind();

        MatchCorrectionRequest request =
                new MatchCorrectionRequest(
                        tournamentId,
                        phaseId,
                        List.of(new SetScoreEntry(0, 25, 10)),
                        "Audit correction for CANCELED match");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<MatchCorrectionRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<MatchCorrectionResultResponse> response =
                authed.exchange(
                        new URI(baseUrl + "/api/matches/" + matchId + "/correction"),
                        HttpMethod.POST,
                        entity,
                        MatchCorrectionResultResponse.class);

        assertThat(response.getStatusCode())
                .as("correction on CANCELED match must return 200")
                .isEqualTo(HttpStatus.OK);

        MatchCorrectionResultResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.auditOnly())
                .as("auditOnly must be true for CANCELED match correction")
                .isTrue();
        assertThat(body.newMatchState())
                .as("CANCELED match state must remain CANCELED")
                .isEqualTo("CANCELED");
    }

    // =========================================================================
    // Guard: phase not ACTIVE → 409
    // (AC-TEST-PHASE-STATUS-GUARD)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-PHASE-STATUS-GUARD: match in PENDING phase → 409"
                    + " error.correction.phase-not-active")
    void correctionOnPendingPhaseMatch_returns409() throws Exception {
        tenantBinder.bindDefaultTenant();
        seedPhaseWithMatch("PENDING", MatchState.FINISHED_WINNER1);
        tenantBinder.unbind();

        MatchCorrectionRequest request =
                new MatchCorrectionRequest(
                        tournamentId, phaseId, List.of(new SetScoreEntry(0, 25, 10)), null);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<MatchCorrectionRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/matches/" + matchId + "/correction"),
                        HttpMethod.POST,
                        entity,
                        String.class);

        assertThat(response.getStatusCode())
                .as("correction on PENDING phase must return 409")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody())
                .as("response body must contain the error key")
                .contains("error.correction.phase-not-active");
    }

    // =========================================================================
    // Guard: match INPROGRESS → 409
    // (AC-TEST-MATCH-STATE-GUARD-INPROGRESS)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-MATCH-STATE-GUARD-INPROGRESS: INPROGRESS match → 409"
                    + " error.correction.match-live-scoring")
    void correctionOnInprogressMatch_returns409() throws Exception {
        tenantBinder.bindDefaultTenant();
        seedActivePhaseWithFinishedMatch(MatchState.INPROGRESS);
        tenantBinder.unbind();

        MatchCorrectionRequest request =
                new MatchCorrectionRequest(
                        tournamentId, phaseId, List.of(new SetScoreEntry(0, 25, 10)), null);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<MatchCorrectionRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/matches/" + matchId + "/correction"),
                        HttpMethod.POST,
                        entity,
                        String.class);

        assertThat(response.getStatusCode())
                .as("correction on INPROGRESS match must return 409")
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody())
                .as("response body must contain match-live-scoring error key")
                .contains("error.correction.match-live-scoring");
    }

    // =========================================================================
    // Match not found (unknown matchId) → 404
    // (AC-TEST-TENANT-ISOLATION: cross-tenant → 404; also covers unknown match)
    // =========================================================================

    @Test
    @DisplayName("Unknown matchId → 404 (tenant-scoped match not found)")
    void unknownMatchId_returns404() throws Exception {
        UUID unknownMatchId = UUID.randomUUID();

        MatchCorrectionRequest request =
                new MatchCorrectionRequest(
                        tournamentId, phaseId, List.of(new SetScoreEntry(0, 25, 10)), null);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<MatchCorrectionRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/matches/" + unknownMatchId + "/correction"),
                        HttpMethod.POST,
                        entity,
                        String.class);

        assertThat(response.getStatusCode())
                .as("unknown matchId must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // GET /api/phases/{phaseId}/matches → 200 with match list
    // (E48S25 regression: endpoint exists and returns match list)
    // =========================================================================

    @Test
    @DisplayName(
            "GET /api/phases/{phaseId}/matches returns 200"
                    + " with non-empty match list for seeded phase")
    void getPhaseMatches_returnsMatchList() throws Exception {
        tenantBinder.bindDefaultTenant();
        seedActivePhaseWithFinishedMatch(MatchState.FINISHED_WINNER1);
        tenantBinder.unbind();

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/phases/" + phaseId + "/matches"),
                        HttpMethod.GET,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("GET phase matches must return 200")
                .isEqualTo(HttpStatus.OK);

        assertThat(response.getBody())
                .as("response body must be a non-empty JSON array")
                .isNotNull()
                .contains(matchId.toString())
                .contains("FINISHED_WINNER1");
    }

    // =========================================================================
    // DEC-74 end-to-end lap-advance via REST (AC-TEST-CORRECTION-ADVANCE-IT)
    // =========================================================================

    /**
     * AC-TEST-CORRECTION-ADVANCE-IT — DEC-74 forward-only lap-advance via the correction REST
     * endpoint.
     *
     * <p>Setup: ACTIVE phase with {@code current_lap_number=1}; one match in lap 1 (sole match in
     * that lap). After submitting a correction that keeps the match terminal, the DEC-74 guard
     * fires: lap 1 is the last lap (max lap = 1), so the sentinel 0 is written to {@code
     * current_lap_number}.
     *
     * <p>Assertion: {@code phase.current_lap_number} in the DB is 0 (sentinel) after the
     * correction.
     *
     * @see <a href="DEC-74">DEC-74 — path-independent lap-advance</a>
     * @see <a href="E56S02">E56S02 — DefaultMatchCorrectionService DEC-74 operationalization</a>
     */
    @Test
    @DisplayName(
            "AC-TEST-CORRECTION-ADVANCE-IT: correcting last match of current lap advances"
                    + " phase.current_lap_number (DEC-74 end-to-end)")
    void correctionCompletingCurrentLap_advancesCurrentLapNumber() throws Exception {
        tenantBinder.bindDefaultTenant();
        seedActivePhaseWithLapMatch(1, 1, MatchState.FINISHED_WINNER1);
        tenantBinder.unbind();

        // Correct the match: submit 2 winning sets for team1 (BEST_OF_3 → FINISHED_WINNER1)
        MatchCorrectionRequest request =
                new MatchCorrectionRequest(
                        tournamentId,
                        phaseId,
                        List.of(new SetScoreEntry(0, 25, 10), new SetScoreEntry(1, 25, 15)),
                        "DEC-74 IT correction");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<MatchCorrectionRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<MatchCorrectionResultResponse> response =
                authed.exchange(
                        new URI(baseUrl + "/api/matches/" + matchId + "/correction"),
                        HttpMethod.POST,
                        entity,
                        MatchCorrectionResultResponse.class);

        assertThat(response.getStatusCode())
                .as("correction must return HTTP 200")
                .isEqualTo(HttpStatus.OK);

        MatchCorrectionResultResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.auditOnly())
                .as("auditOnly must be false for terminal-state match correction")
                .isFalse();

        // DEC-74 independent verifier: read current_lap_number directly via JdbcTemplate (DEC-26)
        tenantBinder.bindDefaultTenant();
        Integer currentLapNumber =
                jdbcTemplate.queryForObject(
                        "SELECT current_lap_number FROM phase WHERE id = ?",
                        Integer.class,
                        phaseId.toString());
        tenantBinder.unbind();

        assertThat(currentLapNumber)
                .as(
                        "AC-TEST-CORRECTION-ADVANCE-IT: after completing the sole match in lap 1"
                            + " (last lap), phase.current_lap_number must be 0 (sentinel, DEC-74)")
                .isEqualTo(0);
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    /**
     * Seeds an ACTIVE phase and a match with the given state in the test tournament. Also inserts
     * minimal team_avatar rows so FK constraints are satisfied.
     */
    private void seedActivePhaseWithFinishedMatch(MatchState matchState) {
        seedPhaseWithMatch("ACTIVE", matchState);
    }

    /**
     * Seeds an ACTIVE phase with {@code current_lap_number=currentLap} and one match with {@code
     * lap_number=matchLap} in the given state. Used for DEC-74 lap-advance IT
     * (AC-TEST-CORRECTION-ADVANCE-IT).
     */
    private void seedActivePhaseWithLapMatch(int currentLap, int matchLap, MatchState matchState) {
        // Insert phase with explicit current_lap_number
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "DEC-74 IT Phase",
                "ACTIVE",
                currentLap,
                LocalDateTime.now());

        // Insert team_avatar rows (FK required by match.member_avatar_1_id and member_avatar_2_id)
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                avatar1Id,
                tournamentId,
                phaseId,
                1,
                1,
                team1Id);
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                avatar2Id,
                tournamentId,
                phaseId,
                1,
                2,
                team2Id);

        // Insert match with lap_number
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, lap_number, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                matchState.getLegacyCode(),
                3, // BEST_OF_3
                matchLap,
                LocalDateTime.now());
    }

    private void seedPhaseWithMatch(String phaseStatus, MatchState matchState) {
        // Insert phase
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Test Phase",
                phaseStatus,
                0,
                LocalDateTime.now());

        // Insert team_avatar rows (FK required by match.member_avatar_1_id and member_avatar_2_id)
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                avatar1Id,
                tournamentId,
                phaseId,
                1,
                1,
                team1Id);
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                avatar2Id,
                tournamentId,
                phaseId,
                1,
                2,
                team2Id);

        // Insert match
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                matchState.getLegacyCode(),
                3, // BEST_OF_3
                LocalDateTime.now());
    }

    // =========================================================================
    // Test-local AdminCredentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
