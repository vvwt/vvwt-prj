package de.vvwt.tm.web.slotopt;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.phaselifecycle.CancelFlagRegistry;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.web.WebModuleTestConfig;
import java.time.Instant;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link SlotOptimizationCancelController} E55S05 additions (AC-IMPL-REST-CONTRACT-PRESERVED + AC-SEC-CANCEL-AUTH-PRESERVED).
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>AC-IMPL-REST-CONTRACT-PRESERVED: POST /cancel — response shape unchanged (200 with
 *       OptimizationResult; 409 with ErrorResponse)
 *   <li>AC-SEC-CANCEL-AUTH-PRESERVED: unauthorized POST /cancel → 401
 *   <li>AC-IMPL-CANCEL-HANDLER-WIRING: controller calls markCancelled + requestCancel (verified
 *       by asserting DB cancelled=TRUE and CancelFlagRegistry.isCancelled()=true after a cancel
 *       with a RUNNING job in the phase_lifecycle_job table)
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-44 carve-out — {@code @SpringBootTest(RANDOM_PORT)} for web controller ITs
 *   <li>DEC-22 Iron Law — RED-first (tests reference new cancel-handler wiring not yet implemented)
 * </ul>
 *
 * @since E55S05
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:cancelcontrollere55s05db;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({
    WebModuleTestConfig.class,
    SlotOptimizationCancelControllerE55S05IT.TestAdminCredentials.class
})
@DisplayName("SlotOptimizationCancelControllerE55S05IT — REST contract + auth (E55S05)")
class SlotOptimizationCancelControllerE55S05IT {

    static final String TEST_PASSWORD = "SlotOptCancelCtrlE55S05";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;
    @Autowired private SlotOptimizationJobRegistry jobRegistry;
    @Autowired private CancelFlagRegistry cancelFlagRegistry;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        tenantContextBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "CancelCtrlE55S05 Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E55S05 Test Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                true);

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "PENDING",
                0,
                false);
    }

    @AfterEach
    void tearDown() {
        cancelFlagRegistry.clear(tournamentId);
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentId);
            jdbcTemplate.update(
                    "DELETE FROM phase_lifecycle_job WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        tenantContextBinder.unbind();
    }

    // =========================================================================
    // AC-IMPL-REST-CONTRACT-PRESERVED: 409 when no active optimization
    // =========================================================================

    @Test
    @DisplayName("POST /cancel with no active optimization → 409 Conflict (contract unchanged)")
    void cancel_noActiveOptimization_returns409_contractUnchanged() {
        ResponseEntity<SlotOptimizationCancelController.ErrorResponse> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .postForEntity(
                                baseUrl + "/api/slotopt/tournaments/" + tournamentId + "/cancel",
                                null,
                                SlotOptimizationCancelController.ErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("response must be 409 Conflict — REST contract preserved")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().messageKey()).isEqualTo("NO_ACTIVE_OPTIMIZATION");
    }

    // =========================================================================
    // AC-IMPL-REST-CONTRACT-PRESERVED: 200 with OptimizationResult when active
    // =========================================================================

    @Test
    @DisplayName("POST /cancel with active optimization → 200 with BSF result (contract unchanged)")
    void cancel_activeOptimization_returns200_contractUnchanged() {
        CancellationToken token = CancellationToken.create();
        JobHandle handle = new JobHandle(token, Instant.now());
        handle.updateBestSoFar(OptimizationResult.cancelled(2L, 0.3));
        jobRegistry.register(tournamentId, handle);

        try {
            ResponseEntity<OptimizationResult> response =
                    restTemplate
                            .withBasicAuth("admin", TEST_PASSWORD)
                            .postForEntity(
                                    baseUrl
                                            + "/api/slotopt/tournaments/"
                                            + tournamentId
                                            + "/cancel",
                                    null,
                                    OptimizationResult.class);

            assertThat(response.getStatusCode())
                    .as("response must be 200 OK — REST contract preserved")
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            // Verify token was cancelled (existing behavior preserved)
            assertThat(token.isCancelled()).isTrue();
        } finally {
            jobRegistry.complete(tournamentId);
        }
    }

    // =========================================================================
    // AC-IMPL-CANCEL-HANDLER-WIRING: DB cancelled=TRUE + in-memory flag set
    // =========================================================================

    @Test
    @DisplayName(
            "POST /cancel with RUNNING job → markCancelled called (DB cancelled=TRUE) +"
                    + " requestCancel (in-memory flag set)")
    void cancel_withRunningJob_marksCancelledInDbAndInMemory() {
        // Insert a RUNNING phase_lifecycle_job row (simulating an active orchestrator claim)
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase_lifecycle_job (id, tournament_id, phase_id, game_mode,"
                        + " sequence, status, cancelled, claimed_by, claimed_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'RUNNING', FALSE, 'test-jvm', CURRENT_TIMESTAMP)",
                jobId,
                tournamentId,
                phaseId,
                "roundRobin",
                1);

        // Also register a job handle so the controller doesn't return 409
        CancellationToken token = CancellationToken.create();
        JobHandle handle = new JobHandle(token, Instant.now());
        jobRegistry.register(tournamentId, handle);

        try {
            ResponseEntity<OptimizationResult> response =
                    restTemplate
                            .withBasicAuth("admin", TEST_PASSWORD)
                            .postForEntity(
                                    baseUrl
                                            + "/api/slotopt/tournaments/"
                                            + tournamentId
                                            + "/cancel",
                                    null,
                                    OptimizationResult.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            // AC-IMPL-CANCEL-HANDLER-WIRING: cancelled=TRUE in DB
            Boolean dbCancelled =
                    jdbcTemplate.queryForObject(
                            "SELECT cancelled FROM phase_lifecycle_job WHERE id = ?",
                            Boolean.class,
                            jobId);
            assertThat(dbCancelled)
                    .as(
                            "phase_lifecycle_job.cancelled must be TRUE after cancel"
                                    + " (AC-IMPL-CANCEL-HANDLER-WIRING)")
                    .isTrue();

            // AC-IMPL-CANCEL-HANDLER-WIRING: in-memory flag set
            assertThat(cancelFlagRegistry.isCancelled(tournamentId))
                    .as(
                            "CancelFlagRegistry.isCancelled() must be true after cancel"
                                    + " (AC-IMPL-CANCEL-HANDLER-WIRING)")
                    .isTrue();
        } finally {
            jobRegistry.complete(tournamentId);
        }
    }

    // =========================================================================
    // AC-SEC-CANCEL-AUTH-PRESERVED: unauthorized → 401
    // =========================================================================

    @Test
    @DisplayName("POST /cancel without auth → 401 Unauthorized (auth contract preserved)")
    void cancel_unauthorized_returns401() {
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/slotopt/tournaments/" + tournamentId + "/cancel",
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("unauthorized request must return 401 — security contract preserved")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("POST /cancel with wrong password → 401 (auth contract preserved)")
    void cancel_wrongPassword_returns401() {
        ResponseEntity<String> response =
                restTemplate
                        .withBasicAuth("admin", "wrongpassword")
                        .postForEntity(
                                baseUrl + "/api/slotopt/tournaments/" + tournamentId + "/cancel",
                                null,
                                String.class);

        assertThat(response.getStatusCode())
                .as("wrong-password request must return 401 — security contract preserved")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Inner TestAdminCredentials (DEC-44 D2)
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean("cancelCtrlE55S05AdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hashed = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hashed;
        }
    }
}
