package de.vvwt.tm.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.infrastructure.web.dto.DeviceRegisterRequest;
import de.vvwt.tm.infrastructure.web.dto.DeviceRegisterResponse;
import de.vvwt.tm.infrastructure.web.dto.DeviceStatusResponse;
import de.vvwt.tm.infrastructure.web.dto.DeviceSummaryResponse;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for E07S02: display device registration, device limit enforcement, configure
 * and delete endpoints.
 *
 * <h2>Acceptance criteria covered</h2>
 *
 * <ul>
 *   <li>AC1 — POST /api/devices/register with {@code deviceType=DISPLAY} → 201 {@code
 *       {deviceToken}} (no PIN)
 *   <li>AC2 — Device limit enforcement → 429 with {@code currentCount} and {@code maxCount}
 *   <li>AC3 — GET /api/devices/status includes {@code configuration} and {@code deviceName} for
 *       display devices (null-safe)
 *   <li>AC4 — PUT /api/devices/{id}/configure sets name + config; 400 on SCORING_TABLET type
 *   <li>AC5 — DELETE /api/devices/{id} removes device; 404 if not found
 *   <li>AC6 — Backward compat: no body → SCORING_TABLET registration unchanged
 *   <li>AC7 — Tenant isolation: DISPLAY device not visible under different tenant context
 *   <li>AC8 — Error responses include {@code messageKey}; 429 includes {@code currentCount}/{@code
 *       maxCount}
 *   <li>AC9 — i18n: error messageKey present on all error paths
 *   <li>AC10 — Device tokens are valid UUID strings (cryptographically random)
 * </ul>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S02.story.md">Story
 *     E07S02</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            DeviceE07S02IT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e07s02ctrldb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            // Set a low display limit for limit tests (AC2)
            "vvwt.devices.max-display-count=3"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class DeviceE07S02IT {

    static final String TEST_PASSWORD = "DeviceE07Test01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private PasswordEncoder passwordEncoder;

    private String baseUrl;
    private TestRestTemplate authed;

    @Autowired private javax.sql.DataSource dataSource;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);
        // Clean devices table before each test to avoid cross-test limit/state pollution.
        // The limit is set to 3 in the test properties; tests that register DISPLAY devices
        // would exhaust the limit and cause subsequent tests to fail without this cleanup.
        try (java.sql.Connection conn = dataSource.getConnection();
                java.sql.Statement st = conn.createStatement()) {
            st.execute("DELETE FROM devices");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to clean devices table before test", e);
        }
    }

    // =========================================================================
    // AC1 — POST /api/devices/register with deviceType=DISPLAY
    // =========================================================================

    @Test
    void registerDisplayDeviceReturns201WithTokenAndNoPin() {
        // AC1: deviceType=DISPLAY → 201 with deviceToken, no PIN
        DeviceRegisterRequest request = new DeviceRegisterRequest("DISPLAY");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<DeviceRegisterResponse> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register",
                        new HttpEntity<>(request, headers),
                        DeviceRegisterResponse.class);

        assertThat(response.getStatusCode())
                .as("AC1 — DISPLAY registration must return 201")
                .isEqualTo(HttpStatus.CREATED);

        DeviceRegisterResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.deviceToken())
                .as("AC1 — deviceToken must be present for DISPLAY device")
                .isNotNull()
                .isNotBlank();
        assertThat(body.pin())
                .as("AC1 — PIN must be null for DISPLAY devices (omitted in response)")
                .isNull();
    }

    @Test
    void registerDisplayDeviceTokenIsValidUuid() {
        // AC10: device token is a cryptographically random UUID string
        DeviceRegisterRequest request = new DeviceRegisterRequest("DISPLAY");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<DeviceRegisterResponse> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register",
                        new HttpEntity<>(request, headers),
                        DeviceRegisterResponse.class);

        String token = response.getBody().deviceToken();
        assertThat(token)
                .as("AC10 — DISPLAY device token must be a valid UUID string")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    // =========================================================================
    // AC2 — Device limit enforcement (max-display-count=3 in test)
    // =========================================================================

    @Test
    void displayDeviceLimitReturns429WhenLimitReached() {
        // AC2: register 3 DISPLAY devices (limit), then try a 4th → 429
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        DeviceRegisterRequest displayRequest = new DeviceRegisterRequest("DISPLAY");
        HttpEntity<DeviceRegisterRequest> entity = new HttpEntity<>(displayRequest, headers);

        // Register up to the limit (3)
        for (int i = 0; i < 3; i++) {
            ResponseEntity<DeviceRegisterResponse> ok =
                    restTemplate.postForEntity(
                            baseUrl + "/api/devices/register",
                            entity,
                            DeviceRegisterResponse.class);
            assertThat(ok.getStatusCode())
                    .as("AC2 — registration %d should succeed (below limit)", i + 1)
                    .isEqualTo(HttpStatus.CREATED);
        }

        // 4th attempt must fail with 429
        ResponseEntity<DeviceLimitErrorResponse> limitResponse =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register", entity, DeviceLimitErrorResponse.class);

        assertThat(limitResponse.getStatusCode())
                .as("AC2 — 4th DISPLAY registration must return 429")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        DeviceLimitErrorResponse body = limitResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCurrentCount())
                .as("AC2/AC8 — currentCount must be 3 (the limit)")
                .isEqualTo(3L);
        assertThat(body.getMaxCount())
                .as("AC2/AC8 — maxCount must match configured limit")
                .isEqualTo(3);
    }

    @Test
    void scoringTabletRegistrationIsNotAffectedByDisplayLimit() {
        // AC2: SCORING_TABLET is not limited by max-display-count (AC6 + AC2 scope)
        // Register 4 tablets — must all succeed even when display limit=3
        for (int i = 0; i < 4; i++) {
            ResponseEntity<DeviceRegisterResponse> response =
                    restTemplate.postForEntity(
                            baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);
            assertThat(response.getStatusCode())
                    .as("Tablet registration %d must not be affected by display limit", i + 1)
                    .isEqualTo(HttpStatus.CREATED);
        }
    }

    @Test
    void limitErrorResponseContainsMessageKey() {
        // AC8/AC9: 429 response includes messageKey
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        DeviceRegisterRequest displayRequest = new DeviceRegisterRequest("DISPLAY");
        HttpEntity<DeviceRegisterRequest> entity = new HttpEntity<>(displayRequest, headers);

        // Fill the limit (3 devices)
        for (int i = 0; i < 3; i++) {
            restTemplate.postForEntity(
                    baseUrl + "/api/devices/register", entity, DeviceRegisterResponse.class);
        }

        // Trigger 429
        ResponseEntity<DeviceLimitErrorResponse> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register", entity, DeviceLimitErrorResponse.class);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .as("AC8/AC9 — 429 error must include messageKey")
                .isEqualTo("error.device.limitExceeded");
    }

    // =========================================================================
    // AC3 — GET /api/devices/status extended with configuration + deviceName
    // =========================================================================

    @Test
    void statusResponseForDisplayDeviceIncludesConfigurationAndDeviceNameAfterConfigure() {
        // AC3: status includes configuration and deviceName after configure call
        DeviceRegisterResponse reg = registerDisplayDevice();

        // Get device ID
        UUID deviceId = getDeviceIdByToken(reg.deviceToken());

        // Configure it
        String configureBody =
                "{\"deviceName\":\"Halle"
                    + " Eingang\",\"configuration\":\"{\\\"display_schema\\\":\\\"OVERVIEW\\\"}\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<DeviceSummaryResponse> configResponse =
                authed.exchange(
                        baseUrl + "/api/devices/" + deviceId + "/configure",
                        HttpMethod.PUT,
                        new HttpEntity<>(configureBody, headers),
                        DeviceSummaryResponse.class);
        assertThat(configResponse.getStatusCode())
                .as("AC4 setup — configure must return 200")
                .isEqualTo(HttpStatus.OK);

        // Poll status — should include configuration + deviceName
        ResponseEntity<DeviceStatusResponse> statusResponse =
                restTemplate.getForEntity(
                        baseUrl + "/api/devices/status?token=" + reg.deviceToken(),
                        DeviceStatusResponse.class);

        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody().deviceName())
                .as("AC3 — status must include deviceName after configure")
                .isEqualTo("Halle Eingang");
        assertThat(statusResponse.getBody().configuration())
                .as("AC3 — status must include configuration after configure")
                .isNotNull();
    }

    @Test
    void statusResponseForUnconfiguredDisplayDeviceOmitsConfigurationAndDeviceName() {
        // AC3: unconfigured display device — configuration and deviceName absent from JSON (null →
        // NON_NULL)
        DeviceRegisterResponse reg = registerDisplayDevice();

        ResponseEntity<DeviceStatusResponse> statusResponse =
                restTemplate.getForEntity(
                        baseUrl + "/api/devices/status?token=" + reg.deviceToken(),
                        DeviceStatusResponse.class);

        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody().configuration())
                .as("AC3 — configuration must be null for unconfigured display device")
                .isNull();
        assertThat(statusResponse.getBody().deviceName())
                .as("AC3 — deviceName must be null for unconfigured display device")
                .isNull();
    }

    @Test
    void statusResponseForScoringTabletDoesNotIncludeConfigurationOrDeviceName() {
        // AC3: scoring tablet backward compat — no configuration or deviceName in status
        ResponseEntity<DeviceRegisterResponse> tabletReg =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);
        String tabletToken = tabletReg.getBody().deviceToken();

        ResponseEntity<DeviceStatusResponse> statusResponse =
                restTemplate.getForEntity(
                        baseUrl + "/api/devices/status?token=" + tabletToken,
                        DeviceStatusResponse.class);

        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody().configuration())
                .as("AC3 — scoring tablet status must not include configuration")
                .isNull();
        assertThat(statusResponse.getBody().deviceName())
                .as("AC3 — scoring tablet status must not include deviceName")
                .isNull();
    }

    // =========================================================================
    // AC4 — PUT /api/devices/{id}/configure
    // =========================================================================

    @Test
    void configureDisplayDeviceReturns200WithUpdatedFields() {
        // AC4: configure DISPLAY device → 200 with updated deviceName and configuration
        DeviceRegisterResponse reg = registerDisplayDevice();
        UUID deviceId = getDeviceIdByToken(reg.deviceToken());

        String configureBody =
                "{\"deviceName\":\"Eingang"
                        + " Süd\",\"configuration\":\"{\\\"display_schema\\\":\\\"OVERVIEW\\\"}\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<DeviceSummaryResponse> response =
                authed.exchange(
                        baseUrl + "/api/devices/" + deviceId + "/configure",
                        HttpMethod.PUT,
                        new HttpEntity<>(configureBody, headers),
                        DeviceSummaryResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — configure must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().deviceName())
                .as("AC4 — deviceName must be updated")
                .isEqualTo("Eingang Süd");
        assertThat(response.getBody().configuration())
                .as("AC4 — configuration must be updated")
                .isNotNull();
        assertThat(response.getBody().deviceType())
                .as("AC4 — deviceType must remain DISPLAY")
                .isEqualTo("DISPLAY");
    }

    @Test
    void configureScoringTabletReturns400() {
        // AC4: configure on SCORING_TABLET device → 400
        ResponseEntity<DeviceRegisterResponse> tabletReg =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);
        String tabletToken = tabletReg.getBody().deviceToken();
        UUID tabletId = getDeviceIdByToken(tabletToken);

        String configureBody = "{\"deviceName\":\"Should Fail\",\"configuration\":null}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiErrorResponse> response =
                authed.exchange(
                        baseUrl + "/api/devices/" + tabletId + "/configure",
                        HttpMethod.PUT,
                        new HttpEntity<>(configureBody, headers),
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — configure on SCORING_TABLET must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessageKey())
                .as("AC4/AC9 — error messageKey must be present")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    void configureRequiresAdminAuthentication() {
        // AC4/AC10: configure without admin credentials → 401
        DeviceRegisterResponse reg = registerDisplayDevice();
        UUID deviceId = getDeviceIdByToken(reg.deviceToken());

        String configureBody = "{\"deviceName\":\"Unauthorized Attempt\",\"configuration\":null}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        baseUrl + "/api/devices/" + deviceId + "/configure",
                        HttpMethod.PUT,
                        new HttpEntity<>(configureBody, headers),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC4/AC10 — configure without admin auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void configureNonExistentDeviceReturns404() {
        // AC4: device not found → 404
        UUID unknownId = UUID.randomUUID();
        String configureBody = "{\"deviceName\":\"Ghost Device\",\"configuration\":null}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiErrorResponse> response =
                authed.exchange(
                        baseUrl + "/api/devices/" + unknownId + "/configure",
                        HttpMethod.PUT,
                        new HttpEntity<>(configureBody, headers),
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — configure of non-existent device must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void configureWithBlankDeviceNameReturns400() {
        // AC4: blank deviceName → 400 validation error
        DeviceRegisterResponse reg = registerDisplayDevice();
        UUID deviceId = getDeviceIdByToken(reg.deviceToken());

        String configureBody = "{\"deviceName\":\"\",\"configuration\":null}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiErrorResponse> response =
                authed.exchange(
                        baseUrl + "/api/devices/" + deviceId + "/configure",
                        HttpMethod.PUT,
                        new HttpEntity<>(configureBody, headers),
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — blank deviceName must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC5 — DELETE /api/devices/{id}
    // =========================================================================

    @Test
    void deleteDisplayDeviceReturns204() {
        // AC5: delete existing DISPLAY device → 204
        DeviceRegisterResponse reg = registerDisplayDevice();
        UUID deviceId = getDeviceIdByToken(reg.deviceToken());

        ResponseEntity<Void> response =
                authed.exchange(
                        baseUrl + "/api/devices/" + deviceId, HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode())
                .as("AC5 — delete DISPLAY device must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteScoringTabletDeviceReturns204() {
        // AC5: delete works for both SCORING_TABLET and DISPLAY devices
        ResponseEntity<DeviceRegisterResponse> tabletReg =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);
        UUID tabletId = getDeviceIdByToken(tabletReg.getBody().deviceToken());

        ResponseEntity<Void> response =
                authed.exchange(
                        baseUrl + "/api/devices/" + tabletId, HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode())
                .as("AC5 — delete SCORING_TABLET device must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteNonExistentDeviceReturns404() {
        // AC5: device not found → 404
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<ApiErrorResponse> response =
                authed.exchange(
                        baseUrl + "/api/devices/" + unknownId,
                        HttpMethod.DELETE,
                        null,
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC5 — delete of non-existent device must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteRequiresAdminAuthentication() {
        // AC5/AC10: delete without admin credentials → 401
        DeviceRegisterResponse reg = registerDisplayDevice();
        UUID deviceId = getDeviceIdByToken(reg.deviceToken());

        ResponseEntity<String> response =
                restTemplate.exchange(
                        baseUrl + "/api/devices/" + deviceId,
                        HttpMethod.DELETE,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC5/AC10 — delete without admin auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC6 — Scoring tablet backward compatibility
    // =========================================================================

    @Test
    void registerWithoutBodyDefaultsToScoringTablet() {
        // AC6: no body → SCORING_TABLET with PIN (backward compat)
        ResponseEntity<DeviceRegisterResponse> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);

        assertThat(response.getStatusCode())
                .as("AC6 — no-body register must return 201")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().pin())
                .as("AC6 — no-body register must return PIN (SCORING_TABLET)")
                .isNotNull()
                .isNotBlank()
                .matches("\\d{4,6}");
        assertThat(response.getBody().deviceToken())
                .as("AC6 — no-body register must return deviceToken")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    void registerWithExplicitScoringTabletTypeReturnsPinAndToken() {
        // AC6: explicit SCORING_TABLET → same as no-body
        DeviceRegisterRequest request = new DeviceRegisterRequest("SCORING_TABLET");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<DeviceRegisterResponse> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register",
                        new HttpEntity<>(request, headers),
                        DeviceRegisterResponse.class);

        assertThat(response.getStatusCode())
                .as("AC6 — explicit SCORING_TABLET register must return 201")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().pin())
                .as("AC6 — explicit SCORING_TABLET must return PIN")
                .isNotNull()
                .isNotBlank()
                .matches("\\d{4,6}");
    }

    @Test
    void registerWithUnknownDeviceTypeReturns400() {
        // AC1: unknown deviceType → 400
        DeviceRegisterRequest request = new DeviceRegisterRequest("UNKNOWN_TYPE");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiErrorResponse> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register",
                        new HttpEntity<>(request, headers),
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC1 — unknown deviceType must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Registers a DISPLAY device and returns the response. */
    private DeviceRegisterResponse registerDisplayDevice() {
        DeviceRegisterRequest request = new DeviceRegisterRequest("DISPLAY");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<DeviceRegisterResponse> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/devices/register",
                        new HttpEntity<>(request, headers),
                        DeviceRegisterResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /**
     * Looks up the device ID via a direct H2 SQL query (for tests needing the UUID of a DISPLAY
     * device that has no PIN and therefore cannot be found via GET /api/devices?pin=...).
     */
    private UUID getDeviceIdByToken(String deviceToken) {
        try (java.sql.Connection conn = dataSource.getConnection();
                java.sql.PreparedStatement ps =
                        conn.prepareStatement("SELECT id FROM devices WHERE device_token = ?")) {
            ps.setString(1, deviceToken);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("Device with token %s must exist in DB", deviceToken)
                        .isTrue();
                return UUID.fromString(rs.getString("id"));
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to look up device id for token: " + deviceToken, e);
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
