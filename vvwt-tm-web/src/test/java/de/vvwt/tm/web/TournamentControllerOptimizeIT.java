// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * RED-first IT for {@code tournament.optimize} field roundtrip through POST /api/tournaments and
 * GET /api/tournaments/{id} (E51S07 AC-TEST-TOURNAMENT-FORM-OPTIMIZE-CHECKBOX-ROUNDTRIP-RED,
 * AC-IMPL-TOURNAMENT-FORM-CHECKBOX).
 *
 * <p>DEC-22 Iron Law Q-1a: tests written before production code changes. DEC-44 D1:
 * {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)}. DEC-26 Rule
 * 2: assertj-db verifies DB column directly (independent of read path).
 *
 * @see TournamentController
 * @see de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest
 * @see de.vvwt.tm.tournament.internal.dto.TournamentResponse
 * @see <a href="DEC-44">DEC-44</a>
 * @see <a href="E51S07">E51S07 — AC-TEST-TOURNAMENT-FORM-OPTIMIZE-CHECKBOX-ROUNDTRIP-RED</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e51s07optimizeit;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@Import({
    WebModuleTestConfig.class,
    TournamentControllerOptimizeIT.TestAdminCredentials.class,
    TenantContextTestSupport.class
})
@ActiveProfiles("test")
@DirtiesContext
@DisplayName("TournamentController IT — E51S07 optimize field roundtrip")
class TournamentControllerOptimizeIT {

    static final String TEST_PASSWORD = "TournCtrlOptimizeIT!";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;
    @Autowired private DataSource dataSource;

    private String baseUrl;
    private TestRestTemplate authed;
    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth("admin", TEST_PASSWORD);
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
    }

    @Test
    @DisplayName(
            "POST /api/tournaments with optimize=false — persisted in DB as false"
                    + " (AC-TEST-TOURNAMENT-FORM-OPTIMIZE-CHECKBOX-ROUNDTRIP-RED)")
    void createTournament_withOptimizeFalse_persistedInDb() {
        TournamentCreateRequest req =
                new TournamentCreateRequest(
                        "Optimize-Test Tournament",
                        null,
                        4,
                        2,
                        "BEST_OF_1",
                        "defaultScoringRule",
                        "defaultSetValidationRule",
                        "roundRobin",
                        null,
                        false, // optimize = false
                        null, // seedMannschaftsfoto = null → server default (E53S05)
                        null); // E68S01: organizer = null

        ResponseEntity<TournamentResponse> response =
                authed.postForEntity(baseUrl + "/api/tournaments", req, TournamentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TournamentResponse body = response.getBody();
        assertThat(body).isNotNull();
        // AC-IMPL-TOURNAMENT-FORM-CHECKBOX: optimize field reflected in response
        assertThat(body.optimize()).isFalse();

        // DEC-26 Rule 2: verify DB column independently via assertj-db (getRowsList path)
        UUID id = body.id();
        tenantContextBinder.bindDefaultTenant();
        try {
            Table tournament = assertDb.table("tournament").build();
            boolean dbOptimize =
                    tournament.getRowsList().stream()
                            .filter(
                                    row ->
                                            id.toString()
                                                    .equalsIgnoreCase(
                                                            String.valueOf(
                                                                    row.getColumnValue("ID")
                                                                            .getValue())))
                            .findFirst()
                            .map(
                                    row ->
                                            Boolean.TRUE.equals(
                                                    row.getColumnValue("OPTIMIZE").getValue()))
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "Tournament row not found in DB for id: "
                                                            + id));
            assertThat(dbOptimize).as("DB optimize column must be false").isFalse();
        } finally {
            tenantContextBinder.unbind();
        }
    }

    @Test
    @DisplayName("POST /api/tournaments default (optimize not set) — defaults to true")
    void createTournament_defaultOptimize_isTrue() {
        TournamentCreateRequest req =
                new TournamentCreateRequest(
                        "Default Optimize Tournament",
                        null,
                        4,
                        2,
                        "BEST_OF_1",
                        "defaultScoringRule",
                        "defaultSetValidationRule",
                        "roundRobin",
                        null,
                        null, // optimize = null → default true
                        null, // seedMannschaftsfoto = null → server default (E53S05)
                        null); // E68S01: organizer = null

        ResponseEntity<TournamentResponse> response =
                authed.postForEntity(baseUrl + "/api/tournaments", req, TournamentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TournamentResponse body = response.getBody();
        assertThat(body).isNotNull();
        // Tournament.optimize has default true — null input should use entity default
        assertThat(body.optimize()).isTrue();
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
