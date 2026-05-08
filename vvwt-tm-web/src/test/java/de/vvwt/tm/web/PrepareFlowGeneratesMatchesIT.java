package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
 * RED-first integration test for E48S21: {@code POST /api/phases/{phaseId}/prepare} must accept
 * slot payload, persist TeamAvatars, and invoke match generation.
 *
 * <h2>Acceptance Criteria Covered</h2>
 *
 * <ul>
 *   <li>AC-TEST-PREPARE-FLOW-GENERATES-MATCHES-RED (AC1): prepare flow generates matches
 *   <li>AC-TEST-PREPARE-FLOW-DEC9-IDENTITY-RED (AC2): TeamAvatars have DEC-9 structural identity
 *   <li>AC-TEST-NON-PARTICIPATING-TEAMS-EXCLUDED-RED (AC3): non-participating teams excluded
 *   <li>AC-TEST-START-AFTER-PREPARE-MATCHES-VISIBLE-RED (AC4): start() after prepare → matches
 *       visible
 *   <li>AC-TEST-PREPARE-IDEMPOTENT-ON-PREPARED-GREEN (AC6): re-prepare on PREPARED is no-op
 *   <li>AC-ERROR-HANDLING-EMPTY-SLOTS-PAYLOAD: empty slots → HTTP 400
 *   <li>AC-ERROR-HANDLING-WRONG-PHASE-STATUS: ACTIVE phase → HTTP 409
 *   <li>AC-SECURITY-PREPARE-AUTH-UNCHANGED: unauthenticated → HTTP 401
 * </ul>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)} per
 * DEC-44 D1. Fixture data inserted via direct JDBC (DEC-26 Rule 3). State verified via direct JDBC
 * after mutation (DEC-26 Rule 2).
 *
 * <p>This test is authored RED-first before ANY production code changes per DEC-22 Iron Law. Under
 * current code (E48S17 state), the prepare endpoint accepts no body → slots are silently discarded
 * → no TeamAvatars or Matches are generated → tests AC1–AC4 FAIL as expected.
 *
 * @see de.vvwt.tm.web.PhaseLifecycleController
 * @see de.vvwt.tm.tournament.internal.DefaultPhaseLifecycleService
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity (phaseId, groupNumber,
 *     groupPosition)</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first; Q-1a legacy-code path)</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E48S21">E48S21 — Fix prepare-flow match generation</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, PrepareFlowGeneratesMatchesIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName(
        "PrepareFlowGeneratesMatchesIT — E48S21 RED-first (AC1–AC4 + error-handling + security)")
class PrepareFlowGeneratesMatchesIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E48S21PrepareFlowGeneratesMatchesIT01";

    /**
     * DraftConfig JSON for a 2-phase tournament where Phase 1 (sectionNumber=1) has sortType
     * team_number, groupCount=1, gameMode=roundRobin. This drives Phase-1-Branch algorithm in
     * DefaultPhaseTransitionService.proposeTransition (E48S18).
     */
    private static final String DRAFT_JSON =
            "{\"sections\": ["
                    + "  {\"sectionNumber\": 1, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []},"
                    + "  {\"sectionNumber\": 2, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"siegerehrung\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []}"
                    + "]}";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    private UUID tournamentId;
    private UUID locationId;

    /** Phase 1 — PENDING, sequenceNumber=1, roundRobin gameMode — for AC1–AC4. */
    private UUID pendingPhaseId;

    /** Phase for ACTIVE status wrong-status test. */
    private UUID activePhaseId;

    /** Two participating teams. */
    private UUID teamId1;

    private UUID teamId2;

    /** Non-participating team — must NOT appear in avatars/matches (AC3). */
    private UUID teamIdNonParticipating;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "E48S21 IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E48S21 IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                2,
                3,
                DRAFT_JSON);

        // Phase 1 — PENDING, sequenceNumber=1
        pendingPhaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                pendingPhaseId,
                tournamentId,
                1,
                "Vorrunde",
                "PENDING",
                0);

        // Active phase — for wrong-status 409 test
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

        // Participating teams
        teamId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                teamId1,
                tournamentId,
                1,
                "E48S21 Team 1",
                true,
                LocalDateTime.now());

        teamId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                teamId2,
                tournamentId,
                2,
                "E48S21 Team 2",
                true,
                LocalDateTime.now());

        // Non-participating team (AC3 — must be excluded from avatars + matches)
        teamIdNonParticipating = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                teamIdNonParticipating,
                tournamentId,
                3,
                "E48S21 Non-Participating Team",
                false,
                LocalDateTime.now());

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
    // AC-TEST-PREPARE-FLOW-GENERATES-MATCHES-RED (AC1)
    // + AC-TEST-PREPARE-FLOW-DEC9-IDENTITY-RED (AC2)
    // + AC-TEST-NON-PARTICIPATING-TEAMS-EXCLUDED-RED (AC3)
    // =========================================================================

    /**
     * AC1 + AC2 + AC3: POST /api/phases/{phaseId}/prepare with slot payload persists TeamAvatars
     * with DEC-9 structural identity and generates matches; non-participating team is excluded.
     *
     * <p>This test is authored RED-first. Under the current code (E48S17 state), the endpoint takes
     * no body → the slot payload is silently discarded → no TeamAvatars or Matches are generated →
     * assertions on avatar count and match count FAIL (expected: fail RED).
     */
    @Test
    @DisplayName(
            "POST /api/phases/{id}/prepare with slots → PREPARED + avatars + matches"
                    + " (AC1 + AC2 + AC3 RED-first)")
    void prepare_withSlotPayload_generatesPreparedPhaseWithAvatarsAndMatches() throws Exception {
        // Slot payload: 2 participating teams in group 1 (positions 1 + 2)
        // DEC-9: structural identity is (groupNumber, groupPosition); teamId is source reference
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
                        new URI(baseUrl + "/api/phases/" + pendingPhaseId + "/prepare"),
                        request,
                        String.class);

        assertThat(response.getStatusCode())
                .as("prepare() with slot payload must return 200 OK")
                .isEqualTo(HttpStatus.OK);

        tenantBinder.bindDefaultTenant();
        try {
            // AC1: phase status = PREPARED
            String status =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase WHERE id = ?", String.class, pendingPhaseId);
            assertThat(status)
                    .as("AC1: Phase status must be PREPARED after prepare() with slots")
                    .isEqualTo("PREPARED");

            // AC2: TeamAvatars persisted with DEC-9 structural identity
            Integer avatarCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                            Integer.class,
                            pendingPhaseId);
            assertThat(avatarCount)
                    .as("AC2: 2 TeamAvatars must be persisted (one per participating team)")
                    .isEqualTo(2);

            // AC2: DEC-9 structural identity — verify (phaseId, groupNumber, groupPosition) unique
            List<Map<String, Object>> avatars =
                    jdbcTemplate.queryForList(
                            "SELECT team_id, group_number, group_position"
                                    + " FROM team_avatar WHERE phase_id = ?"
                                    + " ORDER BY group_position",
                            pendingPhaseId);
            assertThat(avatars).as("AC2: 2 avatar rows").hasSize(2);
            assertThat(avatars.get(0).get("GROUP_NUMBER"))
                    .as("AC2: avatar 1 in group 1")
                    .isEqualTo(1);
            assertThat(avatars.get(0).get("GROUP_POSITION"))
                    .as("AC2: avatar 1 at position 1")
                    .isEqualTo(1);
            assertThat(avatars.get(1).get("GROUP_NUMBER"))
                    .as("AC2: avatar 2 in group 1")
                    .isEqualTo(1);
            assertThat(avatars.get(1).get("GROUP_POSITION"))
                    .as("AC2: avatar 2 at position 2")
                    .isEqualTo(2);

            // AC3: non-participating team NOT in avatars
            Integer nonParticipatingAvatarCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ? AND team_id = ?",
                            Integer.class,
                            pendingPhaseId,
                            teamIdNonParticipating);
            assertThat(nonParticipatingAvatarCount)
                    .as("AC3: non-participating team must NOT have a TeamAvatar")
                    .isEqualTo(0);

            // AC1: matches generated via generateMatches (roundRobin: 1 match for 2 teams in 1
            // group)
            Integer matchCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM match WHERE phase_id = ?",
                            Integer.class,
                            pendingPhaseId);
            assertThat(matchCount)
                    .as("AC1: at least 1 match must be generated via generateMatches")
                    .isGreaterThan(0);

        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // AC-TEST-START-AFTER-PREPARE-MATCHES-VISIBLE-RED (AC4)
    // =========================================================================

    /**
     * AC4: After a successful prepare(), calling start() transitions PREPARED → ACTIVE, and matches
     * remain visible (not cleared by the start transition).
     *
     * <p>This test is authored RED-first. Under the current code, prepare() generates no matches →
     * match count is 0 after start() → assertion FAILS (expected: fail RED).
     */
    @Test
    @DisplayName(
            "POST /api/phases/{id}/start after prepare → ACTIVE + matches visible (AC4 RED-first)")
    void start_afterPrepare_matchesVisible() throws Exception {
        // First: prepare with slots
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
        HttpEntity<String> prepareRequest = new HttpEntity<>(slotsJson, headers);

        ResponseEntity<String> prepareResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + pendingPhaseId + "/prepare"),
                        prepareRequest,
                        String.class);
        assertThat(prepareResponse.getStatusCode())
                .as("AC4 precondition: prepare() must return 200 before start()")
                .isEqualTo(HttpStatus.OK);

        // Then: start() the phase (PREPARED → ACTIVE)
        ResponseEntity<String> startResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + pendingPhaseId + "/start"),
                        null,
                        String.class);
        assertThat(startResponse.getStatusCode())
                .as("AC4: start() after prepare() must return 200 OK")
                .isEqualTo(HttpStatus.OK);

        tenantBinder.bindDefaultTenant();
        try {
            // AC4: phase status = ACTIVE
            String status =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase WHERE id = ?", String.class, pendingPhaseId);
            assertThat(status)
                    .as("AC4: Phase status must be ACTIVE after start()")
                    .isEqualTo("ACTIVE");

            // AC4: matches still visible (not cleared by start())
            Integer matchCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM match WHERE phase_id = ?",
                            Integer.class,
                            pendingPhaseId);
            assertThat(matchCount)
                    .as("AC4: Matches must be visible after start() — match grid not empty")
                    .isGreaterThan(0);

        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // AC-TEST-PREPARE-IDEMPOTENT-ON-PREPARED-GREEN (AC6)
    // =========================================================================

    /**
     * AC6: Re-calling prepare() on an already PREPARED phase is idempotent — returns 200 OK without
     * creating duplicate avatars or matches.
     *
     * <p>This test is GREEN after the fix (idempotent path already existed in E48S17 for the status
     * flip; E48S21 must preserve it by short-circuiting before commitTransition if already
     * PREPARED).
     */
    @Test
    @DisplayName(
            "POST /api/phases/{id}/prepare on already-PREPARED phase → 200 idempotent (AC6 GREEN)")
    void prepare_alreadyPrepared_isIdempotentNoNewAvatars() throws Exception {
        // First prepare — sets PREPARED + generates avatars + matches
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

        authed.postForEntity(
                new URI(baseUrl + "/api/phases/" + pendingPhaseId + "/prepare"),
                request,
                String.class);

        // Second prepare — idempotent (no duplicate avatars)
        ResponseEntity<String> secondResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + pendingPhaseId + "/prepare"),
                        request,
                        String.class);
        assertThat(secondResponse.getStatusCode())
                .as("AC6: Second prepare() on PREPARED phase must return 200 OK (idempotent)")
                .isEqualTo(HttpStatus.OK);

        tenantBinder.bindDefaultTenant();
        try {
            Integer avatarCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                            Integer.class,
                            pendingPhaseId);
            assertThat(avatarCount)
                    .as("AC6: Idempotent re-prepare must NOT create duplicate avatars")
                    .isEqualTo(2);
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // AC-ERROR-HANDLING-EMPTY-SLOTS-PAYLOAD
    // =========================================================================

    /** Empty slot payload → HTTP 400 (operator-actionable, no partial persistence). */
    @Test
    @DisplayName("POST /api/phases/{id}/prepare with empty slots → 400 Bad Request")
    void prepare_emptySlots_returns400() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("[]", headers);

        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + pendingPhaseId + "/prepare"),
                        request,
                        String.class);

        assertThat(response.getStatusCode())
                .as("Empty slots must return HTTP 400 (AC-ERROR-HANDLING-EMPTY-SLOTS-PAYLOAD)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC-ERROR-HANDLING-WRONG-PHASE-STATUS
    // =========================================================================

    /**
     * Prepare on ACTIVE phase → HTTP 409 Conflict (operator-actionable message naming current
     * status and expected status).
     */
    @Test
    @DisplayName("POST /api/phases/{id}/prepare on ACTIVE phase → 409 Conflict")
    void prepare_activePhase_returns409() throws Exception {
        String slotsJson =
                "["
                        + "{\"teamId\":\""
                        + teamId1
                        + "\",\"groupNumber\":1,\"groupPosition\":1}"
                        + "]";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(slotsJson, headers);

        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/phases/" + activePhaseId + "/prepare"),
                        request,
                        String.class);

        assertThat(response.getStatusCode())
                .as(
                        "prepare() on ACTIVE phase must return 409 Conflict"
                                + " (AC-ERROR-HANDLING-WRONG-PHASE-STATUS)")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // =========================================================================
    // AC-SECURITY-PREPARE-AUTH-UNCHANGED
    // =========================================================================

    /** Unauthenticated POST to /api/phases/{id}/prepare → HTTP 401 Unauthorized. */
    @Test
    @DisplayName(
            "POST /api/phases/{id}/prepare unauthenticated → 401"
                    + " (AC-SECURITY-PREPARE-AUTH-UNCHANGED)")
    void prepare_unauthenticated_returns401() throws Exception {
        String slotsJson =
                "[{\"teamId\":\"" + teamId1 + "\",\"groupNumber\":1,\"groupPosition\":1}]";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(slotsJson, headers);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/phases/" + pendingPhaseId + "/prepare"),
                        request,
                        String.class);

        assertThat(response.getStatusCode())
                .as(
                        "Unauthenticated prepare() must return 401"
                                + " (AC-SECURITY-PREPARE-AUTH-UNCHANGED)")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // @TestConfiguration — per-IT admin credentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("E48S21PrepareFlowGeneratesMatchesITAdminCredentials")
        @Primary
        AdminCredentialsProvider adminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
