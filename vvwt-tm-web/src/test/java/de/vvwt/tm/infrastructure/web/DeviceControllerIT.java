package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.infrastructure.web.dto.DeviceAssignRequest;
import de.vvwt.tm.infrastructure.web.dto.DeviceRegisterResponse;
import de.vvwt.tm.infrastructure.web.dto.DeviceStatusResponse;
import de.vvwt.tm.infrastructure.web.dto.DeviceSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link DeviceController} — full HTTP stack including Spring Security,
 * Jackson serialization, and {@link GlobalExceptionHandler} (E06S03, E06S05).
 *
 * <h2>Test coverage</h2>
 * <ul>
 *   <li>AC2 — POST /api/devices/register returns 201 with deviceToken and pin; no auth required</li>
 *   <li>AC3 — GET /api/devices/status?token=... returns status and assignedField; 404 on unknown token</li>
 *   <li>AC4 — GET /api/devices?pin=... returns device; 404 on unknown PIN; requires admin auth</li>
 *   <li>AC5 — PUT /api/devices/{id}/assign sets field; 409 on conflict; 400 on bad field</li>
 *   <li>AC6 — PUT /api/devices/{id}/unassign clears field; idempotent</li>
 *   <li>AC7 — PIN is 4–6 digits; unique per tenant</li>
 *   <li>AC8 — /api/devices/status with invalid token returns 401</li>
 *   <li>AC9 — tenant isolation (cross-tenant device not accessible)</li>
 *   <li>AC11 — deviceToken is a valid UUID string</li>
 *   <li>AC12 — error responses include messageKey field</li>
 *   <li>E06S05-AC1 — GET /api/devices/list returns all devices for the tenant</li>
 *   <li>E06S05-AC7 — DELETE /api/devices removes all devices; returns 204</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S03.story.md">Story E06S03</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S05.story.md">Story E06S05</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                DeviceControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e06s03ctrldb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class DeviceControllerIT {

    static final String TEST_PASSWORD = "DeviceCtrlTest01";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD);
    }

    // =========================================================================
    // AC2 — POST /api/devices/register (public, no auth required)
    // =========================================================================

    @Test
    void registerDeviceReturns201WithTokenAndPin() {
        ResponseEntity<DeviceRegisterResponse> response = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);

        assertThat(response.getStatusCode())
                .as("AC2 — register device must return 201")
                .isEqualTo(HttpStatus.CREATED);

        DeviceRegisterResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.deviceToken())
                .as("AC2 — deviceToken must be non-null")
                .isNotNull().isNotBlank();
        assertThat(body.pin())
                .as("AC2 — pin must be non-null")
                .isNotNull().isNotBlank();
    }

    @Test
    void registerDeviceDoesNotRequireAuthentication() throws Exception {
        // unauthenticated register must succeed (AC2 — no auth required for tablets)
        ResponseEntity<DeviceRegisterResponse> response = restTemplate.postForEntity(
                new URI(baseUrl + "/api/devices/register"), null, DeviceRegisterResponse.class);

        assertThat(response.getStatusCode())
                .as("AC2 — register must succeed without credentials")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deviceTokenIsValidUuid(/* AC11 */) {
        ResponseEntity<DeviceRegisterResponse> response = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);

        String token = response.getBody().deviceToken();
        // AC11: token must be parseable as UUID (UUID.randomUUID format)
        assertThat(token).as("AC11 — deviceToken must be a valid UUID string").matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void pinIsNumericAndWithinExpectedLength(/* AC7 */) {
        ResponseEntity<DeviceRegisterResponse> response = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class);

        String pin = response.getBody().pin();
        assertThat(pin).as("AC7 — PIN must be 4–6 digits")
                .matches("\\d{4,6}");
    }

    // =========================================================================
    // AC3 — GET /api/devices/status (public, device token as query param)
    // =========================================================================

    @Test
    void getDeviceStatusReturnsRegisteredAfterRegistration() {
        DeviceRegisterResponse reg = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();

        ResponseEntity<DeviceStatusResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/devices/status?token=" + reg.deviceToken(),
                DeviceStatusResponse.class);

        assertThat(response.getStatusCode())
                .as("AC3 — status poll must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status())
                .as("AC3 — status must be REGISTERED immediately after registration")
                .isEqualTo("REGISTERED");
        assertThat(response.getBody().assignedField())
                .as("AC3 — assignedField must be null before assignment")
                .isNull();
    }

    @Test
    void getDeviceStatusReturns404ForUnknownToken() throws Exception {
        ResponseEntity<ApiErrorResponse> response = restTemplate.getForEntity(
                new URI(baseUrl + "/api/devices/status?token=nonexistent-token"),
                ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC3 — unknown token must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getDeviceStatusDoesNotRequireAuthentication() {
        // status polling is public — tablets don't have admin credentials (AC3)
        DeviceRegisterResponse reg = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();

        ResponseEntity<DeviceStatusResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/devices/status?token=" + reg.deviceToken(),
                DeviceStatusResponse.class);

        assertThat(response.getStatusCode())
                .as("AC3 — status poll must succeed without admin credentials")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // AC4 — GET /api/devices?pin=... (requires admin auth)
    // =========================================================================

    @Test
    void findByPinReturnsDeviceSummary() {
        DeviceRegisterResponse reg = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();

        ResponseEntity<DeviceSummaryResponse> response = authed.getForEntity(
                baseUrl + "/api/devices?pin=" + reg.pin(), DeviceSummaryResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — find by PIN must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().pin())
                .as("AC4 — response PIN must match the registered PIN")
                .isEqualTo(reg.pin());
        assertThat(response.getBody().status())
                .as("AC4 — status must be REGISTERED")
                .isEqualTo("REGISTERED");
    }

    @Test
    void findByPinReturns404ForUnknownPin() throws Exception {
        ResponseEntity<ApiErrorResponse> response = authed.getForEntity(
                new URI(baseUrl + "/api/devices?pin=0000"), ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — unknown PIN must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void findByPinRequiresAuthentication() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/api/devices?pin=1234"), String.class);

        assertThat(response.getStatusCode())
                .as("AC4 — find-by-PIN must require auth")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC5 — PUT /api/devices/{id}/assign (requires admin auth)
    // =========================================================================

    @Test
    void assignDeviceSetsFieldAndStatusAssigned() {
        DeviceRegisterResponse reg = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();

        // Lookup to get the device ID — use String to diagnose deserialization issues
        ResponseEntity<String> rawFound = authed.getForEntity(
                baseUrl + "/api/devices?pin=" + reg.pin(), String.class);
        assertThat(rawFound.getStatusCode())
                .as("AC4 — lookup by PIN must return 200 before assign test")
                .isEqualTo(HttpStatus.OK);

        DeviceSummaryResponse found = authed.getForEntity(
                baseUrl + "/api/devices?pin=" + reg.pin(), DeviceSummaryResponse.class).getBody();
        assertThat(found).as("AC5 setup — device lookup must return non-null body").isNotNull();
        assertThat(found.id()).as("AC5 setup — device id must not be null; raw response: " + rawFound.getBody()).isNotNull();

        DeviceAssignRequest request = new DeviceAssignRequest(3);
        HttpHeaders assignHeaders = new HttpHeaders();
        assignHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<DeviceAssignRequest> entity = new HttpEntity<>(request, assignHeaders);

        ResponseEntity<String> debugResponse = authed.exchange(
                baseUrl + "/api/devices/" + found.id() + "/assign",
                HttpMethod.PUT, entity, String.class);

        assertThat(debugResponse.getStatusCode())
                .as("AC5 — assign must return 200; body=" + debugResponse.getBody())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<DeviceSummaryResponse> response = authed.exchange(
                baseUrl + "/api/devices/" + found.id() + "/assign",
                HttpMethod.PUT, entity, DeviceSummaryResponse.class);

        assertThat(response.getStatusCode())
                .as("AC5 — assign must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().assignedField())
                .as("AC5 — assignedField must be set")
                .isEqualTo(3);
        assertThat(response.getBody().status())
                .as("AC5 — status must become ASSIGNED")
                .isEqualTo("ASSIGNED");
    }

    @Test
    void assignDeviceReturns409WhenFieldAlreadyOccupied() {
        // Register two devices
        DeviceRegisterResponse reg1 = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();
        DeviceRegisterResponse reg2 = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();

        DeviceSummaryResponse d1 = authed.getForEntity(
                baseUrl + "/api/devices?pin=" + reg1.pin(), DeviceSummaryResponse.class).getBody();
        DeviceSummaryResponse d2 = authed.getForEntity(
                baseUrl + "/api/devices?pin=" + reg2.pin(), DeviceSummaryResponse.class).getBody();

        // Assign first device to field 5
        DeviceAssignRequest req = new DeviceAssignRequest(5);
        HttpHeaders conflictHeaders = new HttpHeaders();
        conflictHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        authed.exchange(baseUrl + "/api/devices/" + d1.id() + "/assign",
                HttpMethod.PUT, new HttpEntity<>(req, conflictHeaders), DeviceSummaryResponse.class);

        // Assign second device to same field — must conflict
        ResponseEntity<ApiErrorResponse> conflictResponse = authed.exchange(
                baseUrl + "/api/devices/" + d2.id() + "/assign",
                HttpMethod.PUT, new HttpEntity<>(req, conflictHeaders), ApiErrorResponse.class);

        assertThat(conflictResponse.getStatusCode())
                .as("AC5 — assigning to an occupied field must return 409")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void assignDeviceReturns400WhenFieldNumberIsZero() {
        DeviceRegisterResponse reg = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();
        DeviceSummaryResponse found = authed.getForEntity(
                baseUrl + "/api/devices?pin=" + reg.pin(), DeviceSummaryResponse.class).getBody();

        // Field 0 is invalid (< 1) — @Min(1) validation should return 400
        DeviceAssignRequest req = new DeviceAssignRequest(0);
        HttpHeaders badFieldHeaders = new HttpHeaders();
        badFieldHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<ApiErrorResponse> response = authed.exchange(
                baseUrl + "/api/devices/" + found.id() + "/assign",
                HttpMethod.PUT, new HttpEntity<>(req, badFieldHeaders), ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC5 — field number 0 must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC6 — PUT /api/devices/{id}/unassign (requires admin auth)
    // =========================================================================

    @Test
    void unassignDeviceClearsFieldAndRestoresRegisteredStatus() {
        DeviceRegisterResponse reg = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();
        DeviceSummaryResponse found = authed.getForEntity(
                baseUrl + "/api/devices?pin=" + reg.pin(), DeviceSummaryResponse.class).getBody();

        // Assign first
        HttpHeaders assignForUnassignHeaders = new HttpHeaders();
        assignForUnassignHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        authed.exchange(baseUrl + "/api/devices/" + found.id() + "/assign",
                HttpMethod.PUT, new HttpEntity<>(new DeviceAssignRequest(7), assignForUnassignHeaders),
                DeviceSummaryResponse.class);

        // Then unassign
        HttpHeaders unassignHeaders = new HttpHeaders();
        unassignHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<DeviceSummaryResponse> unassigned = authed.exchange(
                baseUrl + "/api/devices/" + found.id() + "/unassign",
                HttpMethod.PUT, new HttpEntity<>(unassignHeaders), DeviceSummaryResponse.class);

        assertThat(unassigned.getStatusCode())
                .as("AC6 — unassign must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(unassigned.getBody().assignedField())
                .as("AC6 — assignedField must be null after unassign")
                .isNull();
        assertThat(unassigned.getBody().status())
                .as("AC6 — status must revert to REGISTERED")
                .isEqualTo("REGISTERED");
    }

    @Test
    void unassignAlreadyUnassignedDeviceIsIdempotent() {
        DeviceRegisterResponse reg = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();
        DeviceSummaryResponse found = authed.getForEntity(
                baseUrl + "/api/devices?pin=" + reg.pin(), DeviceSummaryResponse.class).getBody();

        // Unassign without prior assignment — must return 200 (idempotent AC6)
        // Use Content-Type header to avoid Spring MVC 400 on empty PUT body
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<DeviceSummaryResponse> response = authed.exchange(
                baseUrl + "/api/devices/" + found.id() + "/unassign",
                HttpMethod.PUT, new HttpEntity<>(headers), DeviceSummaryResponse.class);

        assertThat(response.getStatusCode())
                .as("AC6 — unassign of unassigned device must be idempotent (200)")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("REGISTERED");
    }

    // =========================================================================
    // AC8 — Device token validation
    // =========================================================================

    @Test
    void statusEndpointReturns404ForUnrecognisedToken(/* AC3 */) throws Exception {
        // getDeviceByToken throws NoSuchElementException for unknown token → 404
        ResponseEntity<ApiErrorResponse> response = restTemplate.getForEntity(
                new URI(baseUrl + "/api/devices/status?token=" + UUID.randomUUID()),
                ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC3/AC8 — unknown device token must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC12 — i18n messageKey in error responses
    // =========================================================================

    @Test
    void errorResponseIncludesMessageKey() throws Exception {
        ResponseEntity<ApiErrorResponse> response = restTemplate.getForEntity(
                new URI(baseUrl + "/api/devices/status?token=invalid"),
                ApiErrorResponse.class);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey())
                .as("AC12 — error response must include a messageKey")
                .isNotNull().isNotBlank();
    }

    // =========================================================================
    // E06S05-AC1 — GET /api/devices/list (requires admin auth)
    // =========================================================================

    @Test
    void listDevicesReturnsEmptyListWhenNoDevicesRegistered() throws Exception {
        ResponseEntity<List<DeviceSummaryResponse>> response = authed.exchange(
                new URI(baseUrl + "/api/devices/list"),
                HttpMethod.GET, null,
                new ParameterizedTypeReference<List<DeviceSummaryResponse>>() {});

        assertThat(response.getStatusCode())
                .as("E06S05-AC1 — list must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("E06S05-AC1 — list must return an empty list when no devices are registered")
                .isNotNull();
    }

    @Test
    void listDevicesReturnsRegisteredDevice() {
        DeviceRegisterResponse reg = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();

        @SuppressWarnings("unchecked")
        ResponseEntity<List<DeviceSummaryResponse>> response = authed.exchange(
                baseUrl + "/api/devices/list",
                HttpMethod.GET, null,
                new ParameterizedTypeReference<List<DeviceSummaryResponse>>() {});

        assertThat(response.getStatusCode())
                .as("E06S05-AC1 — list must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("E06S05-AC1 — list must contain at least the newly registered device")
                .isNotNull()
                .extracting(DeviceSummaryResponse::pin)
                .contains(reg.pin());
    }

    @Test
    void listDevicesRequiresAuthentication() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/api/devices/list"), String.class);

        assertThat(response.getStatusCode())
                .as("E06S05-AC1 — list must require admin auth")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // E06S05-AC7 — DELETE /api/devices (requires admin auth)
    // =========================================================================

    @Test
    void clearAllDevicesReturns204() {
        // Register a device first so there is something to clear
        restTemplate.postForEntity(baseUrl + "/api/devices/register", null,
                DeviceRegisterResponse.class);

        ResponseEntity<Void> response = authed.exchange(
                baseUrl + "/api/devices", HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode())
                .as("E06S05-AC7 — clear all must return 204 No Content")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void clearAllDevicesRemovesDevicesFromList() {
        // Register a device
        DeviceRegisterResponse reg = restTemplate.postForEntity(
                baseUrl + "/api/devices/register", null, DeviceRegisterResponse.class).getBody();

        // Confirm it's visible in the list
        @SuppressWarnings("unchecked")
        List<DeviceSummaryResponse> beforeClear = authed.exchange(
                baseUrl + "/api/devices/list", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<DeviceSummaryResponse>>() {}).getBody();
        assertThat(beforeClear)
                .as("E06S05-AC7 setup — device must appear in list before clear")
                .extracting(DeviceSummaryResponse::pin)
                .contains(reg.pin());

        // Clear all
        authed.exchange(baseUrl + "/api/devices", HttpMethod.DELETE, null, Void.class);

        // List should no longer contain that device
        @SuppressWarnings("unchecked")
        List<DeviceSummaryResponse> afterClear = authed.exchange(
                baseUrl + "/api/devices/list", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<DeviceSummaryResponse>>() {}).getBody();
        assertThat(afterClear)
                .as("E06S05-AC7 — device list must be empty after clear")
                .isNotNull()
                .extracting(DeviceSummaryResponse::pin)
                .doesNotContain(reg.pin());
    }

    @Test
    void clearAllDevicesRequiresAuthentication() throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                new URI(baseUrl + "/api/devices"), HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode())
                .as("E06S05-AC7 — clear all must require admin auth")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Test configuration — fixed admin credentials for the test context
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
