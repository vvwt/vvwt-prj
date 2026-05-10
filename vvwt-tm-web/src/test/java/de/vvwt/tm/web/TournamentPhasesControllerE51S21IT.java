package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.PhaseOverviewResponse;
import de.vvwt.tm.tournament.TournamentService;
import java.util.List;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * RED-first IT for {@code GET /api/tournaments/:tournamentId/phases} E51S21 extension: {@code
 * optimized} field per phase row (AC-TEST-PHASE-OVERVIEW-RESPONSE-OPTIMIZED-FIELD-RED,
 * AC-TEST-PHASE-QUERY-SERVICE-PROJECTION).
 *
 * <p>DEC-22 Iron Law Q-1a: tests written before production code changes (RED-first). DEC-44 D1:
 * {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)}.
 *
 * <p>Tests FAIL on staging HEAD because {@code PhaseOverviewResponse} is missing the {@code
 * optimized} field and {@code DefaultPhaseQueryService.SELECT_PHASES} SQL does not project {@code
 * phase.optimized}.
 *
 * @see TournamentPhasesController
 * @see de.vvwt.tm.tournament.PhaseOverviewResponse
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="DEC-26">DEC-26 — independent persistence verifier via assertj-db</a>
 * @see <a href="E51S21">E51S21 — AC-TEST-PHASE-OVERVIEW-RESPONSE-OPTIMIZED-FIELD-RED</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({
    WebModuleTestConfig.class,
    TournamentPhasesControllerE51S21IT.TestAdminCredentials.class,
    TenantContextTestSupport.class
})
@ActiveProfiles("test")
@DisplayName("TournamentPhasesController IT — E51S21 optimized field extension")
class TournamentPhasesControllerE51S21IT {

    static final String TEST_PASSWORD = "PhasesCtrlE51S21IT!";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;
    @Autowired private TournamentService tournamentService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private TestRestTemplate authed;
    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth("admin", TEST_PASSWORD);

        tenantContextBinder.bindDefaultTenant();
        try {
            tournamentId =
                    tournamentService
                            .createTournament(
                                    "E51S21 PhasesCtrl IT Tournament",
                                    null,
                                    4,
                                    2,
                                    "BEST_OF_1",
                                    "defaultScoringRule",
                                    "defaultSetValidationRule",
                                    "roundRobin",
                                    null,
                                    null,
                                    null) // E53S05: seedMannschaftsfoto = null
                            .getId();
        } finally {
            tenantContextBinder.unbind();
        }
    }

    @AfterEach
    void tearDown() {
        tenantContextBinder.bindDefaultTenant();
        try {
            if (phaseId != null) {
                jdbcTemplate.update("DELETE FROM phase WHERE id = ?", phaseId);
            }
            if (tournamentId != null) {
                // E53S05: delete seeded ActivityType rows before deleting tournament (FK
                // constraint)
                jdbcTemplate.update(
                        "DELETE FROM activity_types WHERE tournament_id = ?", tournamentId);
                jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
                jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            }
        } finally {
            tenantContextBinder.unbind();
        }
    }

    /**
     * AC-TEST-PHASE-OVERVIEW-RESPONSE-OPTIMIZED-FIELD-RED: for a phase where {@code
     * phase.optimized=TRUE} in DB, the response JSON includes {@code "optimized":true}.
     *
     * <p>FAILS on staging HEAD because {@code PhaseOverviewResponse} has no {@code optimized}
     * accessor and the SQL does not project the column.
     */
    @Test
    @DisplayName(
            "GET phases — response includes optimized=true when phase.optimized=TRUE in DB"
                    + " (AC-TEST-PHASE-OVERVIEW-RESPONSE-OPTIMIZED-FIELD-RED)")
    void listPhases_includesOptimizedTrue() {
        phaseId = UUID.randomUUID();
        tenantContextBinder.bindDefaultTenant();
        try {
            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number, optimized, last_job_state)"
                            + " VALUES (?, ?, 1, 'Vorrunde', 'PREPARED', 0, TRUE, 'idle')",
                    phaseId,
                    tournamentId);
        } finally {
            tenantContextBinder.unbind();
        }

        ResponseEntity<List<PhaseOverviewResponse>> response =
                authed.exchange(
                        baseUrl + "/api/tournaments/" + tournamentId + "/phases",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<PhaseOverviewResponse> phases = response.getBody();
        assertThat(phases).isNotNull().hasSize(1);

        PhaseOverviewResponse phase = phases.get(0);
        // AC-TEST-PHASE-OVERVIEW-RESPONSE-OPTIMIZED-FIELD-RED: optimized field present and true
        assertThat(phase.optimized()).isTrue();
    }

    /**
     * AC-TEST-PHASE-QUERY-SERVICE-PROJECTION: for a phase where {@code phase.optimized=FALSE} in
     * DB, the response JSON includes {@code "optimized":false}.
     *
     * <p>FAILS on staging HEAD for the same reason as above.
     */
    @Test
    @DisplayName(
            "GET phases — response includes optimized=false when phase.optimized=FALSE in DB"
                    + " (AC-TEST-PHASE-QUERY-SERVICE-PROJECTION)")
    void listPhases_includesOptimizedFalse() {
        phaseId = UUID.randomUUID();
        tenantContextBinder.bindDefaultTenant();
        try {
            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number, optimized)"
                            + " VALUES (?, ?, 1, 'Vorrunde', 'ASSIGNED', 0, FALSE)",
                    phaseId,
                    tournamentId);
        } finally {
            tenantContextBinder.unbind();
        }

        ResponseEntity<List<PhaseOverviewResponse>> response =
                authed.exchange(
                        baseUrl + "/api/tournaments/" + tournamentId + "/phases",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<PhaseOverviewResponse> phases = response.getBody();
        assertThat(phases).isNotNull().hasSize(1);

        PhaseOverviewResponse phase = phases.get(0);
        // AC-TEST-PHASE-QUERY-SERVICE-PROJECTION: optimized=false matches DB value
        assertThat(phase.optimized()).isFalse();
    }

    /**
     * AC-TEST-PHASE-OVERVIEW-RESPONSE-OPTIMIZED-FIELD-GREEN: both true and false rows round-trip
     * correctly in a two-phase tournament.
     */
    @Test
    @DisplayName(
            "GET phases — both optimized=true and optimized=false rows present in response"
                    + " (AC-TEST-PHASE-OVERVIEW-RESPONSE-OPTIMIZED-FIELD-GREEN)")
    void listPhases_bothOptimizedValues() {
        UUID phaseId2 = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        tenantContextBinder.bindDefaultTenant();
        try {
            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number, optimized, last_job_state)"
                            + " VALUES (?, ?, 1, 'Vorrunde', 'PREPARED', 0, FALSE, 'idle')",
                    phaseId,
                    tournamentId);
            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number, optimized, last_job_state)"
                            + " VALUES (?, ?, 2, 'Finale', 'PREPARED', 0, TRUE, 'idle')",
                    phaseId2,
                    tournamentId);
        } finally {
            tenantContextBinder.unbind();
        }

        ResponseEntity<List<PhaseOverviewResponse>> response =
                authed.exchange(
                        baseUrl + "/api/tournaments/" + tournamentId + "/phases",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<PhaseOverviewResponse> phases = response.getBody();
        assertThat(phases).isNotNull().hasSize(2);

        // sequenceNumber ordering: phase 1 first
        PhaseOverviewResponse p1 = phases.get(0);
        PhaseOverviewResponse p2 = phases.get(1);
        assertThat(p1.sequenceNumber()).isEqualTo(1);
        assertThat(p2.sequenceNumber()).isEqualTo(2);
        assertThat(p1.optimized()).isFalse();
        assertThat(p2.optimized()).isTrue();

        // Cleanup phaseId2 (tearDown only removes phaseId)
        tenantContextBinder.bindDefaultTenant();
        try {
            jdbcTemplate.update("DELETE FROM phase WHERE id = ?", phaseId2);
        } finally {
            tenantContextBinder.unbind();
        }
    }

    // ── Test configuration ────────────────────────────────────────────────────

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentials(PasswordEncoder encoder) {
            String hash = encoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
