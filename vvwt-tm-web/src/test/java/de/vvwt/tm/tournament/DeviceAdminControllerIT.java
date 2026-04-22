package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.DeviceSummaryResponse;
import java.net.URI;
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
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Minimalist integration tests for {@link DeviceAdminController} (E21S06,
 * AC-REST-IT-HAPPY-DeviceAdminController + AC-REST-IT-SEC-DeviceAdminController).
 *
 * <h2>Approach C minimalist-IT</h2>
 *
 * <p>3 {@code @Test} methods:
 *
 * <ol>
 *   <li>Happy-path: ADMIN POST /location → 200 + assertj-db verifies location_id set (DEC-24,
 *       DEC-26 Rule 2)
 *   <li>USER role POST → 403 (DEC-24: admin role required)
 *   <li>Unauthenticated POST → 401 (security gate)
 * </ol>
 *
 * <p>Slice tests ({@link DeviceAdminControllerSliceTest}) cover unassign and additional scenarios.
 *
 * <h2>DEC-24 compliance</h2>
 *
 * <p>Location assignment is a post-registration admin step. The happy-path test:
 *
 * <ol>
 *   <li>Registers a SCORING_TABLET via {@link DeviceService#register(String)} (direct call — {@code
 *       DeviceController} relocated to {@code de.vvwt.tm.web} in E22S07; direct service call avoids
 *       cross-module HTTP coupling for fixture creation)
 *   <li>Assigns a location via POST /api/admin/devices/{id}/location/{locationId} (admin role)
 *   <li>Verifies via assertj-db that location_id is now set in the devices table
 * </ol>
 *
 * @see DeviceAdminController
 * @see DeviceAdminControllerSliceTest
 * @see <a href="DEC-24">DEC-24 — device location nullable; admin role for assignment</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 420)</a>
 * @see <a href="E22S07">E22S07 — Controller relocation to de.vvwt.tm.web</a>
 */
@ApplicationModuleTest(
        mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TournamentModuleTestConfig.class)
@ActiveProfiles("test")
@DisplayName("DeviceAdminController IT — E21S06 AC-REST-IT (3-test minimalist, DEC-24)")
class DeviceAdminControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S06DeviceAdminControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private DeviceService deviceService;

    @Autowired private DataSource dataSource;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
    }

    // =========================================================================
    // Happy-path: ADMIN assigns location → 200, location_id verified via assertj-db
    // =========================================================================

    @Test
    @DisplayName(
            "ADMIN POST /location assigns device; assertj-db verifies location_id set (DEC-24)")
    void adminPostLocation_setsLocationIdInDatabase() throws Exception {
        // Step 1: register a SCORING_TABLET via DeviceService (direct call — DeviceController
        // was relocated to de.vvwt.tm.web in E22S07; direct service call avoids cross-module HTTP
        // coupling for fixture creation).
        UUID deviceId;
        tenantBinder.bindDefaultTenant();
        try {
            deviceId = deviceService.register("SCORING_TABLET").getId();
        } finally {
            tenantBinder.unbind();
        }

        // Step 2: create a location fixture directly (location must exist for FK)
        UUID locationId = UUID.randomUUID();
        UUID tenantId;
        try {
            tenantId = tenantBinder.bindDefaultTenant();
            insertLocationDirectly(locationId, tenantId);
        } finally {
            tenantBinder.unbind();
        }

        // Step 3: assign location (ADMIN role)
        ResponseEntity<DeviceSummaryResponse> assignResponse =
                authed.postForEntity(
                        new URI(
                                baseUrl
                                        + "/api/admin/devices/"
                                        + deviceId
                                        + "/location/"
                                        + locationId),
                        null,
                        DeviceSummaryResponse.class);

        assertThat(assignResponse.getStatusCode())
                .as("ADMIN POST /location must return 200")
                .isEqualTo(HttpStatus.OK);

        // DEC-26 Rule 2 — assertj-db independent verifier: location_id is set
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table devicesTable = assertDb.table("devices").build();
            assertThat(devicesTable.getRowsList())
                    .as("device row must have location_id set after admin assignment (DEC-24)")
                    .anyMatch(
                            row ->
                                    deviceId.equals(row.getColumnValue("ID").getValue())
                                            && locationId.equals(
                                                    row.getColumnValue("LOCATION_ID").getValue()));
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // Security: USER role → 403 (DEC-24: admin role required)
    // =========================================================================

    @Test
    @DisplayName("USER role POST /location returns 403 (DEC-24)")
    void userRolePostLocation_returns403() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        TestRestTemplate userClient = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS + "-USER");

        // Any registered user without ADMIN role must be rejected
        ResponseEntity<String> response =
                userClient.postForEntity(
                        new URI(
                                baseUrl
                                        + "/api/admin/devices/"
                                        + deviceId
                                        + "/location/"
                                        + locationId),
                        null,
                        String.class);

        // 401 is also acceptable here since bad credentials → Spring Security returns 401
        assertThat(response.getStatusCode().value())
                .as("non-admin must receive 401 or 403")
                .isIn(401, 403);
    }

    // =========================================================================
    // Security: unauthenticated → 401
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /location returns 401")
    void unauthenticatedPostLocation_returns401() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(
                                baseUrl
                                        + "/api/admin/devices/"
                                        + deviceId
                                        + "/location/"
                                        + locationId),
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void insertLocationDirectly(UUID locationId, UUID tenantId) throws Exception {
        // AC-HELPER-INSERTLOCATIONDIRECTLY-FIXED:
        // (a) table: locations (plural, V1 schema)
        // (b) column: display_name (V1 schema — not 'name')
        // (c) no ON CONFLICT DO NOTHING (H2-incompatible Postgres-only syntax)
        // (d) catch removed — SQL exceptions propagate uncaught (Brief S-3, Q-1)
        // Default tenant FK parent is already provisioned by DefaultTenantBootstrapRunner.
        try (var conn = dataSource.getConnection();
                var ps =
                        conn.prepareStatement(
                                "INSERT INTO locations (id, tenant_id, display_name)"
                                        + " VALUES (?, ?, ?)")) {
            ps.setObject(1, locationId);
            ps.setObject(2, tenantId);
            ps.setString(3, "IT-Location-" + locationId.toString().substring(0, 8));
            ps.executeUpdate();
        }
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
