// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.PhaseOverviewResponse;
import java.net.URI;
import java.time.LocalDateTime;
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
 * RED-first IT test for {@code GET /api/tournaments/:tournamentId/phases} (E48S05
 * AC-TEST-PHASE-LIST-ENDPOINT-RED).
 *
 * <h2>Approach C minimalist-IT (2 tests)</h2>
 *
 * <ol>
 *   <li>Happy-path authenticated GET → 200 with phase list (list count + assertj-db fixture verify)
 *   <li>Unauthenticated GET → 401 (security gate, AC-SECURITY-PHASES-ENDPOINT-AUTH)
 * </ol>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)} per
 * DEC-44 D1. Fixture data inserted via direct JDBC (DEC-26 Rule 3). Response list count verified
 * directly — no DAO read path involved (DEC-26 Rule 2).
 *
 * @see TournamentPhasesController
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E48S05">E48S05 — AC-TEST-PHASE-LIST-ENDPOINT-RED</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, TournamentPhasesControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("TournamentPhasesController IT — E48S05 AC-TEST-PHASE-LIST-ENDPOINT-RED")
class TournamentPhasesControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E48S05TournamentPhasesControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    private UUID tournamentId;
    private UUID locationId;
    private UUID phaseId1;
    private UUID phaseId2;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        tenantBinder.bindDefaultTenant();

        // DEC-26 Rule 1 — schema from production migration (applied by WebModuleTestConfig)
        // DEC-26 Rule 3 — insert fixture data via direct JDBC
        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "E48S05 IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E48S05 IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                4);

        phaseId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                phaseId1,
                tournamentId,
                1,
                "Vorrunde",
                "ACTIVE",
                2);

        phaseId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                phaseId2,
                tournamentId,
                2,
                "Finale",
                "PENDING",
                0);

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // Happy-path: authenticated GET → 200, phase list returned
    // =========================================================================

    @Test
    @DisplayName("authenticated GET /api/tournaments/{id}/phases returns 200 with phase list")
    void authenticatedGet_phasesForTournament_returns200WithPhaseList() throws Exception {
        ResponseEntity<List<PhaseOverviewResponse>> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/phases"),
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<List<PhaseOverviewResponse>>() {});

        assertThat(response.getStatusCode())
                .as("GET phases must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).as("Response must contain 2 phases").hasSize(2);
    }

    // =========================================================================
    // Happy-path: 404 for unknown tournamentId
    // =========================================================================

    @Test
    @DisplayName("authenticated GET /api/tournaments/{unknownId}/phases returns 404")
    void authenticatedGet_unknownTournament_returns404() throws Exception {
        UUID unknownId = UUID.randomUUID();
        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + unknownId + "/phases"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("GET phases for unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // Security: unauthenticated GET → 401 (AC-SECURITY-PHASES-ENDPOINT-AUTH)
    // =========================================================================

    @Test
    @DisplayName("unauthenticated GET /api/tournaments/{id}/phases returns 401")
    void unauthenticatedGet_phases_returns401() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/phases"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("Unauthenticated GET phases must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Auth substitute (DEC-44 D2 refined pattern)
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("e48s05PhasesItAdminCredentials")
        @Primary
        AdminCredentialsProvider adminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
