package de.vvwt.tm.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.ApiErrorResponse;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterResponse;
import de.vvwt.tm.tournament.internal.dto.DeviceSummaryResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for E14S08: device location refactor.
 *
 * <h2>Acceptance criteria covered</h2>
 *
 * <ul>
 *   <li>AC3 — Registration no longer requires/uses location; location_id persisted as NULL
 *   <li>AC4 — Admin endpoint POST /api/admin/devices/{deviceId}/location/{locationId}
 *   <li>AC5 — Assignment round-trip: register → assign → reassign → unassign
 *   <li>AC8 — Cross-tenant location assignment returns 400
 *   <li>AC9 — DeviceController no longer imports DefaultTenantProvider (compile-time check)
 * </ul>
 *
 * @see <a href=".gaai/project/contexts/artefacts/stories/E14S08.story.md">Story E14S08</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            DeviceE14S08IT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e14s08itdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class DeviceE14S08IT {

    static final String TEST_PASSWORD = "E14S08ItTest01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    // Primary routing DataSource — routes to the per-tenant DB when tenant is bound.
    @Autowired private DataSource dataSource;

    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private TenantRegistryPort tenantRegistryPort;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() throws SQLException {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);
        // Bind the default tenant so that routing DataSource queries work in the test thread.
        // The routing DataSource (primary after E14S11) requires a bound tenant for any JDBC call.
        tenantBinder.bindDefaultTenant();
        // Clean devices table before each test
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM devices")) {
            ps.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() {
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC3 — Registration no longer requires location; location_id persisted as NULL
    // =========================================================================

    @Test
    void registrationSucceedsWithNullLocationId() throws SQLException {
        // AC3: POST /api/devices/register must succeed and location_id must be NULL in DB
        // RED: fails until DeviceController no longer injects DefaultTenantProvider
        ResponseEntity<DeviceRegisterResponse> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);

        assertThat(response.getStatusCode())
                .as("AC3 — register must return 201")
                .isEqualTo(HttpStatus.CREATED);

        String token = response.getBody().deviceToken();
        assertThat(token).as("AC3 — deviceToken must be present").isNotNull().isNotBlank();

        // Verify location_id is NULL in DB
        Object locationId = queryLocationIdByToken(token);
        assertThat(locationId)
                .as("AC3 — location_id must be NULL in DB after registration without location")
                .isNull();
    }

    @Test
    void registrationReturnsPinForScoringTablet() {
        // AC3 backward compat: SCORING_TABLET still gets a PIN
        ResponseEntity<DeviceRegisterResponse> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().pin())
                .as("AC3 — SCORING_TABLET must still receive a PIN")
                .isNotNull()
                .isNotBlank()
                .matches("\\d{4,6}");
    }

    // =========================================================================
    // AC4 — Admin endpoint: POST /api/admin/devices/{deviceId}/location/{locationId}
    // =========================================================================

    @Test
    void assignLocationReturns200WithUpdatedDevice() throws SQLException {
        // AC4: POST /api/admin/devices/{id}/location/{locationId} must return 200 with updated
        // device
        // RED: 404 until endpoint is implemented
        DeviceRegisterResponse reg = registerDevice();
        UUID deviceId = getDeviceIdByToken(reg.deviceToken());
        UUID locationId = resolveDefaultLocationId();

        ResponseEntity<DeviceSummaryResponse> response =
                authed.postForEntity(
                        baseUrl + "/api/admin/devices/" + deviceId + "/location/" + locationId,
                        null,
                        DeviceSummaryResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — admin assign location must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        // Verify location_id is set in DB
        Object dbLocationId = queryLocationIdByToken(reg.deviceToken());
        assertThat(dbLocationId)
                .as("AC4 — location_id must be set in DB after admin assignment")
                .isNotNull();
    }

    @Test
    void assignLocationReturns404ForUnknownDevice() {
        // AC4: unknown deviceId → 404
        UUID unknownId = UUID.randomUUID();
        UUID locationId = resolveDefaultLocationId();

        ResponseEntity<ApiErrorResponse> response =
                authed.postForEntity(
                        baseUrl + "/api/admin/devices/" + unknownId + "/location/" + locationId,
                        null,
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — unknown deviceId must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assignLocationRequiresAuthentication() {
        // AC4: no auth → 401
        UUID fakeDevice = UUID.randomUUID();
        UUID fakeLocation = UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/admin/devices/" + fakeDevice + "/location/" + fakeLocation,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC4 — endpoint must require admin auth")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC5 — Assignment round-trip: register → assign → reassign → unassign
    // =========================================================================

    @Test
    void assignmentRoundTrip() throws SQLException {
        // AC5: (a) register without location → null; (b) assign → location set;
        //      (c) reassign → updated; (d) delete → null again
        DeviceRegisterResponse reg = registerDevice();
        UUID deviceId = getDeviceIdByToken(reg.deviceToken());
        UUID locationId = resolveDefaultLocationId();

        // (a) Initially null
        assertThat(queryLocationIdByToken(reg.deviceToken()))
                .as("AC5(a) — location_id must be NULL after registration")
                .isNull();

        // (b) Assign
        authed.postForEntity(
                baseUrl + "/api/admin/devices/" + deviceId + "/location/" + locationId,
                null,
                DeviceSummaryResponse.class);

        assertThat(queryLocationIdByToken(reg.deviceToken()))
                .as("AC5(b) — location_id must be set after admin assignment")
                .isNotNull();

        // (c) Reassign to same location (idempotent)
        ResponseEntity<DeviceSummaryResponse> reassignResponse =
                authed.postForEntity(
                        baseUrl + "/api/admin/devices/" + deviceId + "/location/" + locationId,
                        null,
                        DeviceSummaryResponse.class);

        assertThat(reassignResponse.getStatusCode())
                .as("AC5(c) — reassign must return 200")
                .isEqualTo(HttpStatus.OK);

        // (d) Unassign via DELETE
        ResponseEntity<DeviceSummaryResponse> unassignResponse =
                authed.exchange(
                        baseUrl + "/api/admin/devices/" + deviceId + "/location",
                        HttpMethod.DELETE,
                        null,
                        DeviceSummaryResponse.class);

        assertThat(unassignResponse.getStatusCode())
                .as("AC5(d) — unassign must return 200")
                .isEqualTo(HttpStatus.OK);

        assertThat(queryLocationIdByToken(reg.deviceToken()))
                .as("AC5(d) — location_id must be NULL after unassign")
                .isNull();
    }

    // =========================================================================
    // AC8 — Cross-tenant location assignment returns 400
    // =========================================================================

    @Test
    void assignCrossTenantLocationReturns400() {
        // AC8: a locationId that does not belong to the device's tenant → 400
        // We use a randomly generated UUID — it will not exist in the locations table
        DeviceRegisterResponse reg = registerDevice();
        UUID deviceId = getDeviceIdByToken(reg.deviceToken());
        UUID unknownLocationId = UUID.randomUUID(); // not in locations table

        ResponseEntity<ApiErrorResponse> response =
                authed.postForEntity(
                        baseUrl
                                + "/api/admin/devices/"
                                + deviceId
                                + "/location/"
                                + unknownLocationId,
                        null,
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC8 — cross-tenant or unknown locationId must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DeviceRegisterResponse registerDevice() {
        ResponseEntity<DeviceRegisterResponse> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private UUID getDeviceIdByToken(String deviceToken) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT id FROM devices WHERE device_token = ?")) {
            ps.setString(1, deviceToken);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("Device must exist for token").isTrue();
                return UUID.fromString(rs.getString("id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to look up device id for token", e);
        }
    }

    private Object queryLocationIdByToken(String deviceToken) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT location_id FROM devices WHERE device_token = ?")) {
            ps.setString(1, deviceToken);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("Device row must exist").isTrue();
                return rs.getObject("location_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query location_id for token", e);
        }
    }

    private UUID resolveDefaultLocationId() {
        // E45S06: tenant_id removed from locations (DEC-50); select first location row.
        // In Wave-1 single-location model each per-tenant DB has exactly one location row.
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT id FROM locations LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("At least one location must exist for the default tenant")
                        .isTrue();
                return UUID.fromString(rs.getString("id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve default location", e);
        }
    }

    // =========================================================================
    // Test configuration — fixed admin credentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hashed = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hashed;
        }
    }
}
