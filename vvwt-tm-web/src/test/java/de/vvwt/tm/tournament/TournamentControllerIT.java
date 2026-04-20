package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Minimalist integration tests for {@link TournamentController} (E21S02,
 * AC-REST-IT-HAPPY-TournamentController + AC-REST-IT-SEC-TournamentController).
 *
 * <h2>Approach C minimalist-IT</h2>
 *
 * <p>Exactly 2 {@code @Test} methods per controller:
 *
 * <ol>
 *   <li>Happy-path authenticated POST → 201 + assertj-db independent DB verification (DEC-26 Rule
 *       2)
 *   <li>Unauthenticated request → 401 (security gate)
 * </ol>
 *
 * <p>Slice tests ({@link TournamentControllerSliceTest}) cover all other scenarios.
 *
 * <h2>assertj-db independent verifier (DEC-26 Rule 2)</h2>
 *
 * <p>The happy-path test reads the persisted row directly from the DataSource via assertj-db — not
 * via the service or repository. This confirms the write path reaches the DB independently of the
 * read path.
 *
 * @see TournamentController
 * @see TournamentControllerSliceTest
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            TournamentControllerIT.TestAdminCredentials.class
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TournamentController IT — E21S02 AC-REST-IT (2-test minimalist)")
class TournamentControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S02TournamentControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private DataSource dataSource;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
    }

    // =========================================================================
    // Happy-path: authenticated POST → 201, row verified via assertj-db
    // =========================================================================

    @Test
    @DisplayName(
            "authenticated POST /api/tm/tournaments creates tournament; assertj-db verifies row")
    void authenticatedPostCreatesTournamentAndPersistsRow() throws Exception {
        var request =
                new TournamentCreateRequest(
                        "IT Hallenturnier E21S02",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");

        ResponseEntity<TournamentResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tm/tournaments"),
                        request,
                        TournamentResponse.class);

        assertThat(response.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().description()).isEqualTo("IT Hallenturnier E21S02");
        assertThat(response.getBody().status()).isEqualTo("DRAFT");
        assertThat(response.getBody().id()).isNotNull();

        // DEC-26 Rule 2 — assertj-db independent verifier
        UUID newId = response.getBody().id();
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table tournamentTable = assertDb.table("tournament").build();
            List<Object> ids =
                    tournamentTable.getRowsList().stream()
                            .map(row -> row.getColumnValue("ID").getValue())
                            .toList();
            assertThat(ids)
                    .as("tournament table must contain the newly created tournament UUID")
                    .contains(newId);
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // Security: unauthenticated POST → 401
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /api/tm/tournaments returns 401")
    void unauthenticatedPostReturns401() throws Exception {
        var request =
                new TournamentCreateRequest(
                        "Unauthorized Tournament",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/tm/tournaments"), request, String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Test-local AdminCredentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
