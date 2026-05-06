package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.ApiErrorResponse;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Controller IT for E48S13 Tournament Admin Escape Hatch (AC-TEST-CONTROLLER-IT-RED).
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>(a) {@code DELETE /api/tournaments/{id}} — 204 for DRAFT, PLANNED, CANCELLED; 409 with
 *       typed messageKey for ACTIVE, COMPLETED; 404 for unknown ID
 *   <li>(b) {@code POST /api/tournaments/{id}/draft/reset-plan} — 200 for PLANNED with body
 *       asserting status = DRAFT; 409 with typed messageKey for ACTIVE, CANCELLED, COMPLETED,
 *       DRAFT; 404 for unknown ID
 * </ul>
 *
 * <h2>DEC-44 annotation (§2026-04-27)</h2>
 *
 * <p>{@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)} +
 * {@code @Import({WebModuleTestConfig.class,
 * TournamentAdminEscapeHatchIT.TestAdminCredentials.class})}
 *
 * <h2>DEC-26 Rule 2 — independent JDBC verifier</h2>
 *
 * <p>Status assertions on happy paths read directly from {@link JdbcTemplate} — not via the
 * service.
 *
 * @see GlobalExceptionHandler
 * @see DraftController
 * @see de.vvwt.tm.tournament.TournamentController
 * @see <a href="E48S13">E48S13 — AC-TEST-CONTROLLER-IT-RED</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, TournamentAdminEscapeHatchIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("TournamentAdminEscapeHatch IT — E48S13 controller coverage")
class TournamentAdminEscapeHatchIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E48S13TournamentAdminEscapeHatchIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    private UUID locationId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        tenantBinder.bindDefaultTenant();
        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "EscapeHatch IT Location");
        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM phase");
        jdbcTemplate.update("DELETE FROM team");
        jdbcTemplate.update("DELETE FROM tournament");
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // DELETE /api/tournaments/{id} — cascade-delete
    // =========================================================================

    @Test
    @DisplayName("authenticated DELETE on DRAFT tournament returns 204")
    void delete_draft_returns204() throws Exception {
        UUID id = seedTournament("DRAFT");

        ResponseEntity<Void> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + id),
                        org.springframework.http.HttpMethod.DELETE,
                        null,
                        Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("authenticated DELETE on PLANNED tournament returns 204")
    void delete_planned_returns204() throws Exception {
        UUID id = seedTournament("PLANNED");

        ResponseEntity<Void> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + id),
                        org.springframework.http.HttpMethod.DELETE,
                        null,
                        Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("authenticated DELETE on CANCELLED tournament returns 204")
    void delete_cancelled_returns204() throws Exception {
        UUID id = seedTournament("CANCELLED");

        ResponseEntity<Void> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + id),
                        org.springframework.http.HttpMethod.DELETE,
                        null,
                        Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName(
            "authenticated DELETE on ACTIVE tournament returns 409 with"
                    + " error.tournament.cascadeDelete.activeRejected")
    void delete_active_returns409WithTypedMessageKey() throws Exception {
        UUID id = seedTournament("ACTIVE");

        ResponseEntity<ApiErrorResponse> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + id),
                        org.springframework.http.HttpMethod.DELETE,
                        null,
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .isEqualTo("error.tournament.cascadeDelete.activeRejected");
    }

    @Test
    @DisplayName(
            "authenticated DELETE on COMPLETED tournament returns 409 with"
                    + " error.tournament.cascadeDelete.completedRejected")
    void delete_completed_returns409WithTypedMessageKey() throws Exception {
        UUID id = seedTournament("COMPLETED");

        ResponseEntity<ApiErrorResponse> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + id),
                        org.springframework.http.HttpMethod.DELETE,
                        null,
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .isEqualTo("error.tournament.cascadeDelete.completedRejected");
    }

    @Test
    @DisplayName(
            "authenticated DELETE on unknown tournament ID returns 400"
                    + " (findByIdForUpdate throws IllegalArgumentException → 400)")
    void delete_unknownId_returns400() throws Exception {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + unknownId),
                        org.springframework.http.HttpMethod.DELETE,
                        null,
                        String.class);

        // findByIdForUpdate throws IllegalArgumentException → GlobalExceptionHandler → 400
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // POST /api/tournaments/{id}/draft/reset-plan — reset plan
    // =========================================================================

    @Test
    @DisplayName(
            "authenticated POST reset-plan on PLANNED tournament returns 200 with status=DRAFT")
    void resetPlan_planned_returns200WithDraftStatus() throws Exception {
        UUID id = seedTournament("PLANNED");

        ResponseEntity<TournamentResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + id + "/draft/reset-plan"),
                        null,
                        TournamentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("DRAFT");

        // DEC-26 Rule 2 — independent JDBC verifier
        tenantBinder.bindDefaultTenant();
        try {
            String dbStatus =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM tournament WHERE id = ?", String.class, id);
            assertThat(dbStatus).isEqualTo("DRAFT");
        } finally {
            tenantBinder.unbind();
        }
    }

    @Test
    @DisplayName(
            "authenticated POST reset-plan on ACTIVE tournament returns 409 with"
                    + " error.tournament.resetPlan.activeRejected")
    void resetPlan_active_returns409WithTypedMessageKey() throws Exception {
        UUID id = seedTournament("ACTIVE");

        ResponseEntity<ApiErrorResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + id + "/draft/reset-plan"),
                        null,
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .isEqualTo("error.tournament.resetPlan.activeRejected");
    }

    @Test
    @DisplayName(
            "authenticated POST reset-plan on CANCELLED tournament returns 409 with"
                    + " error.tournament.resetPlan.cancelledRejected")
    void resetPlan_cancelled_returns409WithTypedMessageKey() throws Exception {
        UUID id = seedTournament("CANCELLED");

        ResponseEntity<ApiErrorResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + id + "/draft/reset-plan"),
                        null,
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .isEqualTo("error.tournament.resetPlan.cancelledRejected");
    }

    @Test
    @DisplayName(
            "authenticated POST reset-plan on COMPLETED tournament returns 409 with"
                    + " error.tournament.resetPlan.completedRejected")
    void resetPlan_completed_returns409WithTypedMessageKey() throws Exception {
        UUID id = seedTournament("COMPLETED");

        ResponseEntity<ApiErrorResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + id + "/draft/reset-plan"),
                        null,
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .isEqualTo("error.tournament.resetPlan.completedRejected");
    }

    @Test
    @DisplayName(
            "authenticated POST reset-plan on DRAFT tournament returns 409 with"
                    + " error.tournament.resetPlan.draftIdempotent")
    void resetPlan_draft_returns409DraftIdempotent() throws Exception {
        UUID id = seedTournament("DRAFT");

        ResponseEntity<ApiErrorResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + id + "/draft/reset-plan"),
                        null,
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .isEqualTo("error.tournament.resetPlan.draftIdempotent");
    }

    @Test
    @DisplayName(
            "authenticated POST reset-plan on unknown tournament ID returns 400"
                    + " (findByIdForUpdate throws IllegalArgumentException → 400)")
    void resetPlan_unknownId_returns400() throws Exception {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + unknownId + "/draft/reset-plan"),
                        null,
                        String.class);

        // findByIdForUpdate throws IllegalArgumentException → GlobalExceptionHandler → 400
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // Security gate
    // =========================================================================

    @Test
    @DisplayName("unauthenticated DELETE returns 401")
    void delete_unauthenticated_returns401() throws Exception {
        UUID id = seedTournament("DRAFT");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        new URI(baseUrl + "/api/tournaments/" + id),
                        org.springframework.http.HttpMethod.DELETE,
                        null,
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("unauthenticated POST reset-plan returns 401")
    void resetPlan_unauthenticated_returns401() throws Exception {
        UUID id = seedTournament("PLANNED");

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + id + "/draft/reset-plan"),
                        null,
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID seedTournament(String status) {
        tenantBinder.bindDefaultTenant();
        try {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO tournament (id, location_id, description, match_format,"
                            + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                            + " status, created_at, field_count, team_count)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id,
                    locationId,
                    "EscapeHatch IT Tournament (" + status + ")",
                    "BEST_OF_3",
                    "setPoints",
                    "standardVolleyball",
                    "roundRobin",
                    status,
                    LocalDateTime.now(),
                    2,
                    4);
            return id;
        } finally {
            tenantBinder.unbind();
        }
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
