package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterRequest;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterResponse;
import de.vvwt.tm.tournament.internal.dto.DeviceStatusResponse;
import de.vvwt.tm.tournament.internal.dto.DeviceSummaryResponse;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * Integration tests for the E49S02 PIN-on-revisit fix.
 *
 * <p>Verifies that {@code GET /api/devices/status?token=...} returns the {@code pin} field for a
 * SCORING_TABLET in REGISTERED status (M-A mechanism — DEC-24 §E PIN-stable).
 *
 * <p>RED tests: {@code AC-TEST-PIN-VISIBLE-ON-REVISIT-REGISTERED-RED} and {@code
 * AC-TEST-PIN-NOT-EXPOSED-WITHOUT-DEVICE-TOKEN-RED}.
 *
 * <p>Web-module IT per DEC-44 D1: {@code @SpringBootTest(RANDOM_PORT, classes =
 * TournamentManagerApplication.class)}.
 *
 * @see DeviceController
 * @see de.vvwt.tm.tournament.internal.dto.DeviceStatusResponse
 * @see <a href="DEC-44">DEC-44 — web-module IT annotation</a>
 * @see <a href="E49S02">E49S02 — PIN not displayed on /score/register on revisit</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, DeviceStatusPinRevisitIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("DeviceStatusPinRevisitIT — E49S02 PIN-on-revisit (M-A mechanism)")
class DeviceStatusPinRevisitIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E49S02DeviceStatusPinRevisitIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
    }

    // =========================================================================
    // AC-TEST-PIN-VISIBLE-ON-REVISIT-REGISTERED-RED
    // AC-TEST-PIN-STABLE-PER-REVISIT-GREEN
    // =========================================================================

    /**
     * When a SCORING_TABLET in REGISTERED status is queried via its device-token, the /status
     * response MUST include the {@code pin} field matching the originally assigned PIN (E49S02,
     * DEC-24 §E PIN-lifetime-stable).
     *
     * <p>RED test: fails on current code because {@link DeviceStatusResponse} has no {@code pin}
     * field (the server omits it — the NOTE at register.mustache:484-491 documents the gap).
     */
    @Test
    @DisplayName(
            "AC-TEST-PIN-VISIBLE-ON-REVISIT-REGISTERED-RED: "
                    + "GET /status with REGISTERED device token returns pin field")
    void getStatus_registeredDevice_returnsPinInResponse() throws Exception {
        // Register a new SCORING_TABLET → get token + pin from register response
        DeviceRegisterRequest regRequest = new DeviceRegisterRequest("SCORING_TABLET");
        ResponseEntity<DeviceRegisterResponse> regResponse =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/devices/register"),
                        regRequest,
                        DeviceRegisterResponse.class);

        assertThat(regResponse.getStatusCode())
                .as("POST /register must return 201")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(regResponse.getBody()).isNotNull();
        String token = regResponse.getBody().deviceToken();
        String registeredPin = regResponse.getBody().pin();
        assertThat(token).isNotBlank();
        assertThat(registeredPin).isNotBlank();

        // Now simulate tablet revisit: GET /api/devices/status?token=<token>
        ResponseEntity<DeviceStatusResponse> statusResponse =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/devices/status?token=" + token),
                        DeviceStatusResponse.class);

        assertThat(statusResponse.getStatusCode())
                .as("GET /status must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody()).isNotNull();
        assertThat(statusResponse.getBody().status())
                .as("device must be in REGISTERED status")
                .isEqualTo("REGISTERED");

        // AC-TEST-PIN-VISIBLE-ON-REVISIT-REGISTERED-RED: pin field must be present
        assertThat(statusResponse.getBody().pin())
                .as(
                        "AC-TEST-PIN-VISIBLE-ON-REVISIT-REGISTERED-RED: "
                                + "status response must include pin field for REGISTERED device")
                .isNotNull()
                .isNotBlank();

        // AC-TEST-PIN-STABLE-PER-REVISIT-GREEN: pin must equal the original registration PIN
        assertThat(statusResponse.getBody().pin())
                .as(
                        "AC-TEST-PIN-STABLE-PER-REVISIT-GREEN: "
                                + "revisit PIN must match the registration PIN (DEC-24 §E stable)")
                .isEqualTo(registeredPin);
    }

    // =========================================================================
    // AC-TEST-PIN-NOT-EXPOSED-WITHOUT-DEVICE-TOKEN-RED (cross-device isolation)
    // =========================================================================

    /**
     * A device-token belonging to device-B MUST NOT expose device-A's PIN.
     *
     * <p>The /status endpoint returns the PIN for the device whose token was presented. A
     * different-device token MUST return that device's own PIN — not another device's PIN.
     *
     * <p>RED test: verifies cross-device PIN isolation (security boundary of M-A mechanism).
     */
    @Test
    @DisplayName(
            "AC-TEST-PIN-NOT-EXPOSED-WITHOUT-DEVICE-TOKEN-RED: "
                    + "GET /status with device-B token does not expose device-A PIN")
    void getStatus_pinNotExposedToOtherToken() throws Exception {
        // Register device-A
        ResponseEntity<DeviceRegisterResponse> regA =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/devices/register"),
                        new DeviceRegisterRequest("SCORING_TABLET"),
                        DeviceRegisterResponse.class);
        assertThat(regA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tokenA = regA.getBody().deviceToken();
        String pinA = regA.getBody().pin();

        // Register device-B
        ResponseEntity<DeviceRegisterResponse> regB =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/devices/register"),
                        new DeviceRegisterRequest("SCORING_TABLET"),
                        DeviceRegisterResponse.class);
        assertThat(regB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tokenB = regB.getBody().deviceToken();
        String pinB = regB.getBody().pin();

        // Devices must have distinct PINs (probabilistic — 7^4 = 2401 space)
        // Even if they collide (extremely rare), the test logic below still holds:
        // token-B must only return device-B's PIN

        // Query status with token-B → should return device-B's own PIN
        ResponseEntity<DeviceStatusResponse> statusB =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/devices/status?token=" + tokenB),
                        DeviceStatusResponse.class);
        assertThat(statusB.getStatusCode()).isEqualTo(HttpStatus.OK);

        // token-B must return pinB (device-B's PIN), which is correct isolation
        // Even if pinA == pinB coincidentally, the structural property holds:
        // only the device associated with the presented token has its PIN returned
        assertThat(statusB.getBody().pin())
                .as(
                        "token-B query must return device-B PIN (token-B's own device PIN,"
                                + " not device-A's)")
                .isEqualTo(pinB);

        // Verify device-A's PIN is still device-A's PIN (independent of token-B query)
        ResponseEntity<DeviceStatusResponse> statusA =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/devices/status?token=" + tokenA),
                        DeviceStatusResponse.class);
        assertThat(statusA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusA.getBody().pin())
                .as("token-A query must return device-A PIN")
                .isEqualTo(pinA);
    }

    // =========================================================================
    // AC-TEST-PIN-NOT-EXPOSED-WITHOUT-DEVICE-TOKEN-RED (invalid token → 404)
    // =========================================================================

    /**
     * An invalid device-token MUST return 404 — not 200 with any PIN data.
     *
     * <p>Unauthenticated callers (no valid device-token) MUST be rejected.
     */
    @Test
    @DisplayName(
            "AC-TEST-PIN-NOT-EXPOSED-WITHOUT-DEVICE-TOKEN-RED: "
                    + "GET /status with invalid token returns 404 (not 200)")
    void getStatus_invalidToken_returns404() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(
                                baseUrl
                                        + "/api/devices/status?token=invalid-token-xyz-does-not-exist"),
                        String.class);

        assertThat(response.getStatusCode())
                .as(
                        "AC-TEST-PIN-NOT-EXPOSED-WITHOUT-DEVICE-TOKEN-RED: "
                                + "invalid token must return 404, not 200 with leaked data")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC-TEST-LIST-PIN-OMISSION-PRESERVED-GREEN
    // =========================================================================

    /**
     * GET /api/devices/list (admin-session caller) MUST NOT contain {@code pin} for any device.
     *
     * <p>E49S01 D-13 preserved: the admin list response uses {@link DeviceSummaryResponse} which
     * has no {@code pin} field.
     */
    @Test
    @DisplayName(
            "AC-TEST-LIST-PIN-OMISSION-PRESERVED-GREEN: "
                    + "GET /api/devices/list response JSON must not contain pin field")
    void getList_adminSession_doesNotContainPinField() throws Exception {
        // Register at least one SCORING_TABLET to ensure non-empty list
        restTemplate.postForEntity(
                new URI(baseUrl + "/api/devices/register"),
                new DeviceRegisterRequest("SCORING_TABLET"),
                DeviceRegisterResponse.class);

        // Fetch list via admin credentials
        ResponseEntity<String> listResponse =
                authed.getForEntity(new URI(baseUrl + "/api/devices/list"), String.class);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotNull();

        // The JSON response body must not contain a "pin" key (E49S01 D-13 preserved)
        assertThat(listResponse.getBody())
                .as(
                        "AC-TEST-LIST-PIN-OMISSION-PRESERVED-GREEN: "
                                + "admin list response must not contain pin field (E49S01 D-13)")
                .doesNotContain("\"pin\"");
    }

    // =========================================================================
    // AC-TEST-LOGS-NO-PIN-LEAK-GREEN
    // =========================================================================

    /**
     * The PIN value MUST NOT appear in server logs during a /status call.
     *
     * <p>Uses Logback {@link ListAppender} to capture log records during the status endpoint
     * invocation and asserts no log record contains the 4-digit numeric PIN string.
     */
    @Test
    @DisplayName(
            "AC-TEST-LOGS-NO-PIN-LEAK-GREEN: "
                    + "PIN value must not appear in server logs during /status call")
    void getStatus_pinNotInServerLog() throws Exception {
        // Register to get a known PIN
        ResponseEntity<DeviceRegisterResponse> regResponse =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/devices/register"),
                        new DeviceRegisterRequest("SCORING_TABLET"),
                        DeviceRegisterResponse.class);
        assertThat(regResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = regResponse.getBody().deviceToken();
        String pin = regResponse.getBody().pin();
        assertThat(pin).isNotBlank();

        // Attach a ListAppender to the root logger to capture all log events
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        rootLogger.addAppender(listAppender);

        try {
            // Execute the status call
            restTemplate.getForEntity(
                    new URI(baseUrl + "/api/devices/status?token=" + token),
                    DeviceStatusResponse.class);
        } finally {
            rootLogger.detachAppender(listAppender);
        }

        // Assert no log record contains the PIN value
        List<ILoggingEvent> logEvents = listAppender.list;
        for (ILoggingEvent event : logEvents) {
            String formattedMessage = event.getFormattedMessage();
            if (formattedMessage != null) {
                assertThat(formattedMessage)
                        .as(
                                "AC-TEST-LOGS-NO-PIN-LEAK-GREEN: "
                                        + "server log must not contain PIN value '"
                                        + pin
                                        + "'")
                        .doesNotContain(pin);
            }
        }
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
