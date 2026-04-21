package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterRequest;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterResponse;
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
 * Minimalist integration tests for {@link DeviceController} (E21S06,
 * AC-REST-IT-HAPPY-DeviceController + AC-REST-IT-SEC-DeviceController).
 *
 * <h2>Approach C minimalist-IT</h2>
 *
 * <p>Exactly 2 {@code @Test} methods:
 *
 * <ol>
 *   <li>Happy-path authenticated POST /register → 201 + assertj-db independent DB verification
 *       (DEC-26 Rule 2)
 *   <li>Unauthenticated request → 401 (security gate)
 * </ol>
 *
 * <p>Slice tests ({@link DeviceControllerSliceTest}) cover all other scenarios.
 *
 * <h2>assertj-db independent verifier (DEC-26 Rule 2)</h2>
 *
 * <p>The happy-path test reads the persisted row directly from the DataSource via assertj-db — not
 * via the service or repository. This confirms the write path reaches the DB independently of the
 * read path.
 *
 * @see DeviceController
 * @see DeviceControllerSliceTest
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 420)</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            DeviceControllerIT.TestAdminCredentials.class
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("DeviceController IT — E21S06 AC-REST-IT (2-test minimalist)")
class DeviceControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S06DeviceControllerIT01";

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
    // Happy-path: authenticated POST /register → 201, row verified via assertj-db
    // =========================================================================

    @Test
    @DisplayName(
            "authenticated POST /api/tm/devices/register creates SCORING_TABLET; assertj-db"
                    + " verifies row")
    void authenticatedPostRegisterCreatesDeviceAndPersistsRow() throws Exception {
        DeviceRegisterRequest request = new DeviceRegisterRequest("SCORING_TABLET");

        ResponseEntity<DeviceRegisterResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tm/devices/register"),
                        request,
                        DeviceRegisterResponse.class);

        assertThat(response.getStatusCode())
                .as("POST /register must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().deviceToken()).isNotBlank();

        // DEC-26 Rule 2 — assertj-db independent verifier
        UUID newId = response.getBody().id();
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table devicesTable = assertDb.table("devices").build();
            List<Object> ids =
                    devicesTable.getRowsList().stream()
                            .map(row -> row.getColumnValue("ID").getValue())
                            .toList();
            assertThat(ids)
                    .as("devices table must contain the newly registered device UUID")
                    .contains(newId);
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // Security: unauthenticated POST → 401
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /api/tm/devices/register returns 401")
    void unauthenticatedPostRegisterReturns401() throws Exception {
        DeviceRegisterRequest request = new DeviceRegisterRequest("SCORING_TABLET");

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/tm/devices/register"), request, String.class);

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
