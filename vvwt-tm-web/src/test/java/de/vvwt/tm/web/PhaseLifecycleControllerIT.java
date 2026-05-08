package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for {@link PhaseLifecycleController} — E48S06
 * AC-TEST-CONTROLLER-IT-PER-METHOD-GREEN.
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>POST /api/phases/{id}/start — 200 on PENDING phase, 409 on wrong state
 *   <li>POST /api/phases/{id}/complete — 200 on ACTIVE+all-finished, 409 on unfinished matches
 *   <li>POST /api/phases/{id}/force-complete — 200 on ACTIVE phase
 *   <li>Security: all 3 endpoints return 401 unauthenticated (AC-SECURITY-PHASE-LIFECYCLE-AUTH)
 * </ul>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)} per
 * DEC-44 D1. Fixture data inserted via direct JDBC (DEC-26 Rule 3). Phase status verified via
 * direct JDBC after mutation (DEC-26 Rule 2).
 *
 * @see PhaseLifecycleController
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E48S06">E48S06 — AC-TEST-CONTROLLER-IT-PER-METHOD-GREEN</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, PhaseLifecycleControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("PhaseLifecycleController IT — E48S06 AC-TEST-CONTROLLER-IT-PER-METHOD-GREEN")
class PhaseLifecycleControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E48S06PhaseLifecycleControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    private UUID tournamentId;
    private UUID locationId;
    private UUID pendingPhaseId; // E48S17: now inserts PREPARED status for start() tests
    private UUID trulyPendingPhaseId; // status=PENDING for prepare() tests
    private UUID activePhaseId;
    private UUID activePhaseAllFinishedId;
    private UUID avatarId1;
    private UUID avatarId2;
    private UUID teamId1;
    private UUID teamId2;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "E48S06 IT Location");

        // draft_json must include a section for sectionNumber=5 (trulyPendingPhaseId seq=5)
        // and sectionNumber=1/2/3 for other phases — commitTransition reads draft_json
        // to resolve the gameMode for match generation (E48S21 fix).
        String draftJson =
                "{\"sections\": ["
                        + "  {\"sectionNumber\": 1, \"sortType\": \"team_number\","
                        + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                        + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                        + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []},"
                        + "  {\"sectionNumber\": 2, \"sortType\": \"team_number\","
                        + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                        + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                        + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []},"
                        + "  {\"sectionNumber\": 3, \"sortType\": \"team_number\","
                        + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                        + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                        + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []},"
                        + "  {\"sectionNumber\": 5, \"sortType\": \"team_number\","
                        + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                        + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                        + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []}"
                        + "]}";

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E48S06 IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                2,
                4,
                draftJson);

        // PREPARED phase — for start() 200 test (E48S17: start() now requires PREPARED)
        pendingPhaseId = UUID.randomUUID(); // field name kept for minimal diff; status is PREPARED
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                pendingPhaseId,
                tournamentId,
                1,
                "Vorrunde",
                "PREPARED", // E48S17: start() requires PREPARED status
                0);

        // PENDING phase — for prepare() 200 test (E48S17
        // AC-IMPL-PHASE-LIFECYCLE-CONTROLLER-PREPARE-ENDPOINT)
        trulyPendingPhaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                trulyPendingPhaseId,
                tournamentId,
                5, // high seq number to avoid predecessor-check conflicts with
                // pendingPhaseId(seq=1)
                "Pending For Prepare",
                "PENDING",
                0);

        // ACTIVE phase with unfinished matches — for complete() 409 test and forceComplete() 200
        // test
        activePhaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                activePhaseId,
                tournamentId,
                2,
                "Finale",
                "ACTIVE",
                0);

        // Teams and avatars required by the match FK constraints
        teamId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId1,
                tournamentId,
                1,
                "E48S06 Team 1",
                LocalDateTime.now());
        teamId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId2,
                tournamentId,
                2,
                "E48S06 Team 2",
                LocalDateTime.now());
        avatarId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatarId1,
                tournamentId,
                activePhaseId,
                teamId1,
                1,
                1,
                LocalDateTime.now());
        avatarId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatarId2,
                tournamentId,
                activePhaseId,
                teamId2,
                1,
                2,
                LocalDateTime.now());

        // Insert an unfinished match for the active phase
        UUID matchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                activePhaseId,
                avatarId1,
                avatarId2,
                0, // OPEN
                3,
                LocalDateTime.now());

        // ACTIVE phase with ALL matches finished — for complete() 200 test
        activePhaseAllFinishedId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                activePhaseAllFinishedId,
                tournamentId,
                3,
                "Gruppe C",
                "ACTIVE",
                1);
        // No unfinished matches for this phase (empty = all 0 unfinished)

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM match WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update(
                "DELETE FROM team_avatar WHERE phase_id IN ("
                        + "SELECT id FROM phase WHERE tournament_id = ?)",
                tournamentId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // POST /api/phases/{id}/start
    // =========================================================================

    @Test
    @DisplayName("POST /api/phases/{id}/start — PREPARED phase → 200 OK (E48S17 refactor)")
    void start_pendingPhase_returns200() throws Exception {
        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + pendingPhaseId + "/start"),
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("start() on PENDING phase must return 200 OK")
                .isEqualTo(HttpStatus.OK);

        // DEC-26 Rule 2: verify DB state via direct JDBC
        tenantBinder.bindDefaultTenant();
        try {
            String status =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase WHERE id = ?", String.class, pendingPhaseId);
            assertThat(status).as("Phase status must be ACTIVE after start()").isEqualTo("ACTIVE");
        } finally {
            tenantBinder.unbind();
        }
    }

    @Test
    @DisplayName("POST /api/phases/{id}/start — already ACTIVE phase → 409 Conflict")
    void start_activePhase_returns409() throws Exception {
        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + activePhaseId + "/start"),
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("start() on ACTIVE phase must return 409 Conflict")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("POST /api/phases/{id}/start — unauthenticated → 401")
    void start_unauthenticated_returns401() throws Exception {
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/phases/" + pendingPhaseId + "/start"),
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("Unauthenticated start() must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // POST /api/phases/{id}/prepare (E48S17 AC-IMPL-PHASE-LIFECYCLE-CONTROLLER-PREPARE-ENDPOINT)
    // =========================================================================

    @Test
    @DisplayName(
            "POST /api/phases/{id}/prepare — PENDING phase with slots → 200 OK (E48S21 fix)"
                    + " (AC-TEST-CONTROLLER-IT-PER-METHOD-GREEN)")
    void prepare_pendingPhase_returns200() throws Exception {
        // E48S21: prepare() now requires a slot payload (team-to-group assignments).
        // Fixture teams (teamId1=group1/pos1, teamId2=group1/pos2) satisfy groupCount=1.
        String slotsJson =
                "["
                        + "{\"teamId\":\""
                        + teamId1
                        + "\",\"groupNumber\":1,\"groupPosition\":1},"
                        + "{\"teamId\":\""
                        + teamId2
                        + "\",\"groupNumber\":1,\"groupPosition\":2}"
                        + "]";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(slotsJson, headers);

        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + trulyPendingPhaseId + "/prepare"),
                        request,
                        String.class);

        assertThat(response.getStatusCode())
                .as("prepare() on PENDING phase with slots must return 200 OK (E48S21)")
                .isEqualTo(HttpStatus.OK);

        // DEC-26 Rule 2: verify DB state via direct JDBC
        tenantBinder.bindDefaultTenant();
        try {
            String status =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase WHERE id = ?",
                            String.class,
                            trulyPendingPhaseId);
            assertThat(status)
                    .as("Phase status must be PREPARED after prepare()")
                    .isEqualTo("PREPARED");
        } finally {
            tenantBinder.unbind();
        }
    }

    @Test
    @DisplayName(
            "POST /api/phases/{id}/prepare — unauthenticated → 401"
                    + " (AC-SECURITY-PHASE-LIFECYCLE-AUTH)")
    void prepare_unauthenticated_returns401() throws Exception {
        // Send a minimal body — Spring Security rejects before parsing body, so content is
        // irrelevant, but a non-empty body prevents 400 before auth check on some configs.
        String minimalBody =
                "[{\"teamId\":\"" + teamId1 + "\",\"groupNumber\":1,\"groupPosition\":1}]";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(minimalBody, headers);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/phases/" + trulyPendingPhaseId + "/prepare"),
                        request,
                        String.class);

        assertThat(response.getStatusCode())
                .as("Unauthenticated prepare() must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // POST /api/phases/{id}/complete
    // =========================================================================

    @Test
    @DisplayName("POST /api/phases/{id}/complete — ACTIVE + all finished → 200 OK")
    void complete_activeAllFinished_returns200() throws Exception {
        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + activePhaseAllFinishedId + "/complete"),
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("complete() on ACTIVE+all-finished phase must return 200 OK")
                .isEqualTo(HttpStatus.OK);

        tenantBinder.bindDefaultTenant();
        try {
            String status =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase WHERE id = ?",
                            String.class,
                            activePhaseAllFinishedId);
            assertThat(status)
                    .as("Phase status must be COMPLETED after complete()")
                    .isEqualTo("COMPLETED");
        } finally {
            tenantBinder.unbind();
        }
    }

    @Test
    @DisplayName("POST /api/phases/{id}/complete — ACTIVE + unfinished matches → 409 Conflict")
    void complete_activeWithUnfinishedMatches_returns409() throws Exception {
        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + activePhaseId + "/complete"),
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("complete() with unfinished matches must return 409 Conflict")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("POST /api/phases/{id}/complete — unauthenticated → 401")
    void complete_unauthenticated_returns401() throws Exception {
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/phases/" + activePhaseId + "/complete"),
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("Unauthenticated complete() must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // POST /api/phases/{id}/force-complete
    // =========================================================================

    @Test
    @DisplayName("POST /api/phases/{id}/force-complete — ACTIVE phase → 200 OK")
    void forceComplete_activePhase_returns200() throws Exception {
        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + activePhaseId + "/force-complete"),
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("force-complete() on ACTIVE phase must return 200 OK")
                .isEqualTo(HttpStatus.OK);

        tenantBinder.bindDefaultTenant();
        try {
            String status =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase WHERE id = ?", String.class, activePhaseId);
            assertThat(status)
                    .as("Phase status must be COMPLETED after force-complete()")
                    .isEqualTo("COMPLETED");
        } finally {
            tenantBinder.unbind();
        }
    }

    @Test
    @DisplayName("POST /api/phases/{id}/force-complete — unauthenticated → 401")
    void forceComplete_unauthenticated_returns401() throws Exception {
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/phases/" + activePhaseId + "/force-complete"),
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("Unauthenticated force-complete() must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Auth substitute (DEC-44 D2 refined pattern)
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("e48s06PhaseLifecycleItAdminCredentials")
        @Primary
        AdminCredentialsProvider adminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
