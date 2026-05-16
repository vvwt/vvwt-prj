// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.TeamCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TeamResponse;
import java.net.URI;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * Minimalist integration tests for {@link TeamController} (E21S04, AC-REST-IT-HAPPY-TeamController
 * + AC-REST-IT-SEC-TeamController).
 *
 * <h2>Approach C minimalist-IT</h2>
 *
 * <p>Exactly 2 {@code @Test} methods:
 *
 * <ol>
 *   <li>Happy-path authenticated POST → 201 + assertj-db independent DB verification (DEC-26 Rule
 *       2)
 *   <li>Unauthenticated request → 401 (security gate)
 * </ol>
 *
 * <p>Slice tests ({@link TeamControllerSliceTest}) cover all other scenarios.
 *
 * <h2>assertj-db independent verifier (DEC-26 Rule 2)</h2>
 *
 * <p>The happy-path test reads the persisted row directly from the DataSource via assertj-db — not
 * via the service or repository. This confirms the write path reaches the DB independently of the
 * read path.
 *
 * <h2>Module scope (DEC-38/DEC-40 Clause E)</h2>
 *
 * <p>Relocated from {@code de.vvwt.tm.tournament} to {@code de.vvwt.tm.web} per DEC-40 Clause D
 * (E22S07, Q-1b whole-class relocation). The {@code @ApplicationModuleTest} annotation now resolves
 * {@code de.vvwt.tm.web} as the module under test. {@link TournamentModuleTestConfig} is imported
 * via its FQN from the {@code tournament} test package.
 *
 * @see TeamController
 * @see TeamControllerSliceTest
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S07">E22S07 — Relocate TeamController to de.vvwt.tm.web</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, TeamControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("TeamController IT — E21S04 AC-REST-IT (2-test minimalist)")
class TeamControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S04TeamControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private DataSource dataSource;

    private String baseUrl;
    private TestRestTemplate authed;
    private UUID tournamentId;

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        // Create a tournament fixture via the /api/tournaments endpoint
        var tournamentRequest =
                new de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest(
                        "IT TeamController Tournament E21S04",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null,
                        null); // E53S05: seedMannschaftsfoto = null

        ResponseEntity<de.vvwt.tm.tournament.internal.dto.TournamentResponse> tournamentResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"),
                        tournamentRequest,
                        de.vvwt.tm.tournament.internal.dto.TournamentResponse.class);

        assertThat(tournamentResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        tournamentId = tournamentResponse.getBody().id();
    }

    // =========================================================================
    // Happy-path: authenticated POST → 201, row verified via assertj-db
    // =========================================================================

    @Test
    @DisplayName(
            "authenticated POST /api/tournaments/{id}/teams creates team; assertj-db verifies"
                    + " row")
    void authenticatedPostCreatesTeamAndPersistsRow() throws Exception {
        var request = new TeamCreateRequest("Team Alpha IT", null, null, null, null);

        ResponseEntity<TeamResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                        request,
                        TeamResponse.class);

        assertThat(response.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().description()).isEqualTo("Team Alpha IT");
        assertThat(response.getBody().id()).isNotNull();

        // DEC-26 Rule 2 — assertj-db independent verifier
        UUID newId = response.getBody().id();
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table teamTable = assertDb.table("team").build();
            List<Object> ids =
                    teamTable.getRowsList().stream()
                            .map(row -> row.getColumnValue("ID").getValue())
                            .toList();
            assertThat(ids)
                    .as("team table must contain the newly created team UUID")
                    .contains(newId);
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // Security: unauthenticated POST → 401
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /api/tournaments/{id}/teams returns 401")
    void unauthenticatedPostReturns401() throws Exception {
        var request = new TeamCreateRequest("Unauthorized Team", null, null, null, null);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                        request,
                        String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
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
