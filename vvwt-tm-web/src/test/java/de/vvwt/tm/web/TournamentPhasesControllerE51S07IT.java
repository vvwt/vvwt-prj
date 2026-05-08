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
 * RED-first IT for {@code GET /api/tournaments/:tournamentId/phases} E51S07 extension: {@code
 * jobStatus} field per phase row (AC-TEST-PHASELIST-RESPONSE-INCLUDES-JOB-STATUS-RED,
 * AC-IMPL-PHASELIST-ENDPOINT-EXTENSION).
 *
 * <p>DEC-22 Iron Law Q-1a: tests written before production code changes. DEC-44 D1:
 * {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)}.
 *
 * @see TournamentPhasesController
 * @see de.vvwt.tm.tournament.PhaseOverviewResponse
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E51S07">E51S07 — AC-TEST-PHASELIST-RESPONSE-INCLUDES-JOB-STATUS-RED</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({
    WebModuleTestConfig.class,
    TournamentPhasesControllerE51S07IT.TestAdminCredentials.class,
    TenantContextTestSupport.class
})
@ActiveProfiles("test")
@DisplayName("TournamentPhasesController IT — E51S07 jobStatus field extension")
class TournamentPhasesControllerE51S07IT {

    static final String TEST_PASSWORD = "PhasesCtrlE51S07IT!";

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

        // Create tournament via service (default tenant)
        tenantContextBinder.bindDefaultTenant();
        try {
            tournamentId =
                    tournamentService
                            .createTournament(
                                    "E51S07 PhasesCtrl IT Tournament",
                                    null,
                                    4,
                                    2,
                                    "BEST_OF_1",
                                    "defaultScoringRule",
                                    "defaultSetValidationRule",
                                    "roundRobin",
                                    null,
                                    null)
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
                jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
                jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            }
        } finally {
            tenantContextBinder.unbind();
        }
    }

    @Test
    @DisplayName(
            "GET phases — response includes jobStatus field per row"
                    + " (AC-TEST-PHASELIST-RESPONSE-INCLUDES-JOB-STATUS-RED)")
    void listPhases_includesJobStatus() {
        // Seed a phase with last_job_state set via JDBC (DEC-26 Rule 3)
        phaseId = UUID.randomUUID();
        tenantContextBinder.bindDefaultTenant();
        try {
            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number, optimized, last_job_state)"
                            + " VALUES (?, ?, 1, 'Vorrunde', 'PREPARED', 0, FALSE,"
                            + " 'slot_opt_running')",
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
        // AC-TEST-PHASELIST-RESPONSE-INCLUDES-JOB-STATUS-RED: jobStatus field present and matches
        // phase.last_job_state
        assertThat(phase.jobStatus()).isEqualTo("slot_opt_running");
    }

    @Test
    @DisplayName("GET phases — jobStatus null when last_job_state is null")
    void listPhases_jobStatusNullWhenNoJob() {
        // Seed a phase with null last_job_state
        phaseId = UUID.randomUUID();
        tenantContextBinder.bindDefaultTenant();
        try {
            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number, optimized)"
                            + " VALUES (?, ?, 1, 'Vorrunde', 'PREPARED', 0, FALSE)",
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
        // AC-ERROR-HANDLING-PHASELIST-ICON-NULL-LAST-JOB-STATE: null jobStatus is acceptable
        assertThat(phases.get(0).jobStatus()).isNull();
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
