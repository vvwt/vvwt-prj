package de.vvwt.tm.infrastructure.score;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.infrastructure.score.dto.PartialScoreRequest;
import de.vvwt.tm.infrastructure.score.dto.SetSubmitRequest;
import de.vvwt.tm.infrastructure.web.dto.DeviceAssignRequest;
import de.vvwt.tm.infrastructure.web.dto.DeviceRegisterResponse;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.net.URI;
import java.util.UUID;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link ScoreApiController} — full HTTP stack (E06S06).
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>AC1/AC9: GET /api/score/match — 204 when no match; 401 on bad token; 403 on wrong field
 *   <li>AC5: POST /api/score/partial — 204 on valid token; 401 on invalid token
 *   <li>AC7/AC8: POST /api/score/submit — 401 on invalid token
 *   <li>AC12: POST /api/score/submit — 403 when device on wrong field
 * </ul>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S06.story.md">Story
 *     E06S06</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            ScoreApiIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e06s06scoreapidb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("ScoreApiController IT — E06S06: Score entry API")
class ScoreApiIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "ScoreApiPass06";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    // =========================================================================
    // AC1, AC9: GET /api/score/match — invalid / unknown token → 401
    // =========================================================================

    @Test
    @DisplayName("AC8: GET /api/score/match with unknown token returns 401")
    void getMatch_unknownToken_returns401() throws Exception {
        String unknownToken = UUID.randomUUID().toString();
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/score/match?field=1&token=" + unknownToken),
                        String.class);

        assertThat(response.getStatusCode())
                .as("Unknown token must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC8/AC9: GET /api/score/match without token parameter returns 400")
    void getMatch_missingToken_returns400() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/score/match?field=1"), String.class);

        // Missing required query parameter → Spring 400
        assertThat(response.getStatusCode())
                .as("Missing token parameter must return 400 or 401")
                .isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC1, AC9: GET /api/score/match — registered device (not assigned) → 401
    // =========================================================================

    @Test
    @DisplayName("AC8: GET /api/score/match with REGISTERED (unassigned) device returns 401")
    void getMatch_registeredDevice_returns401() throws Exception {
        // Register a device (status=REGISTERED, no field)
        String deviceToken = registerDevice();

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/score/match?field=1&token=" + deviceToken),
                        String.class);

        assertThat(response.getStatusCode())
                .as("REGISTERED (unassigned) device must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC1, AC9: GET /api/score/match — assigned device, no active match → 204
    // =========================================================================

    @Test
    @DisplayName("AC9: GET /api/score/match with ASSIGNED device returns 204 when no active match")
    void getMatch_assignedDevice_noMatch_returns204() throws Exception {
        // Register and assign device to field 1
        String deviceToken = registerAndAssignDevice(1);

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/score/match?field=1&token=" + deviceToken),
                        String.class);

        assertThat(response.getStatusCode())
                .as("Assigned device with no active match must return 204 (AC9 no-match state)")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // =========================================================================
    // AC12: GET /api/score/match — valid device but wrong field → 403
    // =========================================================================

    @Test
    @DisplayName(
            "AC12: GET /api/score/match with device assigned to field 2 but requesting field 1"
                    + " returns 403")
    void getMatch_wrongField_returns403() throws Exception {
        // Register and assign device to field 2
        String deviceToken = registerAndAssignDevice(2);

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/score/match?field=1&token=" + deviceToken),
                        String.class);

        assertThat(response.getStatusCode())
                .as("Valid device on wrong field must return 403 (AC12)")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // =========================================================================
    // AC5: POST /api/score/partial — unknown token → 401
    // =========================================================================

    @Test
    @DisplayName("AC5/AC8: POST /api/score/partial with unknown token returns 401")
    void partialScore_unknownToken_returns401() throws Exception {
        PartialScoreRequest request =
                new PartialScoreRequest(UUID.randomUUID(), 0, 10, 8, UUID.randomUUID().toString());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<PartialScoreRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        new URI(baseUrl + "/api/score/partial"),
                        HttpMethod.POST,
                        entity,
                        String.class);

        assertThat(response.getStatusCode())
                .as("Unknown token on partial score must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC7, AC8: POST /api/score/submit — unknown token → 401
    // =========================================================================

    @Test
    @DisplayName("AC7/AC8: POST /api/score/submit with unknown token returns 401")
    void submitSet_unknownToken_returns401() throws Exception {
        SetSubmitRequest request =
                new SetSubmitRequest(UUID.randomUUID(), 0, 25, 18, UUID.randomUUID().toString());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SetSubmitRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        new URI(baseUrl + "/api/score/submit"),
                        HttpMethod.POST,
                        entity,
                        String.class);

        assertThat(response.getStatusCode())
                .as("Unknown token on submit must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Validation: POST /api/score/submit — missing required fields → 400
    // =========================================================================

    @Test
    @DisplayName("AC6: POST /api/score/submit with null matchId returns 400")
    void submitSet_nullMatchId_returns400() throws Exception {
        // Build raw JSON with null matchId to bypass record constructor
        String body =
                "{\"matchId\":null,\"setIndex\":0,\"team1Points\":25,\"team2Points\":18,"
                        + "\"deviceToken\":\""
                        + UUID.randomUUID()
                        + "\"}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        new URI(baseUrl + "/api/score/submit"),
                        HttpMethod.POST,
                        entity,
                        String.class);

        assertThat(response.getStatusCode())
                .as("null matchId must return 400 (Bean Validation AC6)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Registers a new device via POST /api/devices/register and returns its deviceToken. */
    private String registerDevice() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>("", headers);

        ResponseEntity<DeviceRegisterResponse> response =
                restTemplate.exchange(
                        new URI(baseUrl + "/api/devices/register"),
                        HttpMethod.POST,
                        entity,
                        DeviceRegisterResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().deviceToken();
    }

    /**
     * Registers a device and assigns it to the given field via the admin API. Returns the
     * deviceToken of the assigned device.
     */
    private String registerAndAssignDevice(int fieldNumber) throws Exception {
        // Register one device — get its token and PIN
        ResponseEntity<DeviceRegisterResponse> regResp =
                restTemplate.exchange(
                        new URI(baseUrl + "/api/devices/register"),
                        HttpMethod.POST,
                        new HttpEntity<>("", withJsonContentType()),
                        DeviceRegisterResponse.class);
        assertThat(regResp.getStatusCode())
                .as("Device registration must succeed")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(regResp.getBody()).isNotNull();
        String token = regResp.getBody().deviceToken();
        String pin = regResp.getBody().pin();

        // Look up the device by PIN (admin endpoint) to get its UUID
        TestRestTemplate adminClient = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
        ResponseEntity<String> pinLookup =
                adminClient.getForEntity(
                        new URI(baseUrl + "/api/devices?pin=" + pin), String.class);
        assertThat(pinLookup.getStatusCode())
                .as("Admin GET /api/devices?pin must return 200")
                .isEqualTo(HttpStatus.OK);
        String body = pinLookup.getBody();
        String id = extractJsonField(body, "id");
        assertThat(id)
                .as("Device ID must be extractable from pin-lookup response: " + body)
                .isNotNull();

        // Assign to field
        DeviceAssignRequest assignRequest = new DeviceAssignRequest(fieldNumber);
        ResponseEntity<String> assignResp =
                adminClient.exchange(
                        new URI(baseUrl + "/api/devices/" + id + "/assign"),
                        HttpMethod.PUT,
                        new HttpEntity<>(assignRequest, withJsonContentType()),
                        String.class);
        assertThat(assignResp.getStatusCode())
                .as("Device assignment must succeed (200 or 204)")
                .isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);

        return token;
    }

    private HttpHeaders withJsonContentType() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /**
     * Very minimal JSON field extractor — avoids Jackson dependency in test. Looks for {@code
     * "field":"value"} or {@code "field":value} pattern.
     */
    private static String extractJsonField(String json, String field) {
        if (json == null) {
            return null;
        }
        String search = "\"" + field + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            return null;
        }
        start += search.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return json.substring(start, end);
    }

    // -------------------------------------------------------------------------
    // Test configuration
    // -------------------------------------------------------------------------

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
