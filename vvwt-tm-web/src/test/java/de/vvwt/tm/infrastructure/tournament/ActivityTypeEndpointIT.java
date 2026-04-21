package de.vvwt.tm.infrastructure.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityTypeCreateRequest;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityTypeResponse;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.AfterEach;
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
 * Minimalist integration tests for {@link ActivityTypeController} (E20S02, AC4 — Approach C).
 *
 * <h2>Approach C minimalist-IT (AC4)</h2>
 *
 * <p>Exactly 2 {@code @Test} methods per controller:
 *
 * <ol>
 *   <li>Happy-path authenticated POST → 201 + assertj-db independent DB verification (AC5)
 *   <li>Unauthenticated request → 401 (AC9)
 * </ol>
 *
 * <p>Slice tests ({@link ActivityTypeControllerTest}) cover all other scenarios.
 *
 * <h2>assertj-db independent verifier (AC5, DEC-26)</h2>
 *
 * <p>The happy-path test reads the persisted row directly from the DataSource via assertj-db — not
 * via the service or repository. This confirms the write path reaches the DB independently of the
 * read path.
 *
 * @see ActivityTypeController
 * @see ActivityTypeControllerTest
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            ActivityTypeEndpointIT.TestAdminCredentials.class
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("ActivityTypeController IT — E20S02 AC4 Approach C (2-test minimalist)")
class ActivityTypeEndpointIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E20S02ActivityTypesIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private TournamentRepository tournamentRepository;

    @Autowired private DataSource dataSource;

    private String baseUrl;
    private TestRestTemplate authed;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        // Bind the default tenant context so repository operations are tenant-scoped
        UUID defaultTenantId = tenantBinder.bindDefaultTenant();

        // Create a tournament for test isolation
        tournamentId = UUID.randomUUID();
        Tournament t =
                new Tournament(
                        tournamentId,
                        defaultTenantId,
                        "IT Tournament " + tournamentId,
                        MatchFormat.BEST_OF_3.name(),
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        "DRAFT",
                        LocalDateTime.now());
        tournamentRepository.save(t);

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        // no-op — each @SpringBootTest run uses isolated in-memory H2 per TenantContextTestSupport
    }

    // =========================================================================
    // AC4 / AC5 — authenticated POST → 201, row verified via assertj-db
    // =========================================================================

    @Test
    @DisplayName("AC4/AC5: authenticated POST creates activity type; assertj-db verifies row")
    void authenticatedPostCreatesActivityTypeAndPersistsRow() throws Exception {
        var request = new ActivityTypeCreateRequest("Mannschaftsfoto", "FIRST_FREE_ROUND", null, 1);

        ResponseEntity<ActivityTypeResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                        request,
                        ActivityTypeResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4: POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("Mannschaftsfoto");

        // AC5 — assertj-db independent verifier: read directly from DataSource, not via service
        // Must bind tenant context before accessing the routing DataSource for direct queries
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table activityTypesTable = assertDb.table("activity_types").build();
            assertThat(activityTypesTable)
                    .as("AC5: activity_types table must contain the newly created row")
                    .column("name")
                    .containsValues("Mannschaftsfoto");
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // AC9 — unauthenticated request → 401
    // =========================================================================

    @Test
    @DisplayName("AC9: unauthenticated POST returns 401")
    void unauthenticatedPostReturns401() throws Exception {
        var request = new ActivityTypeCreateRequest("Foto", "FIRST_FREE_ROUND", null, 1);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                        request,
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC9: unauthenticated request must return 401")
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
