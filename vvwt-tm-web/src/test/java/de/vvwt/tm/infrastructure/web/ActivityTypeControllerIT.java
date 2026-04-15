package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.domain.AssignmentRule;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.dto.ActivityTypeCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.ActivityTypeResponse;
import de.vvwt.tm.infrastructure.web.dto.ActivityTypeUpdateRequest;
import de.vvwt.tm.tenant.DefaultTenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ActivityTypeController} (E08S06, AC1, AC7, AC9).
 *
 * <h2>Test scenarios</h2>
 * <ul>
 *   <li>AC1 — GET returns 200 with empty array when no activity types</li>
 *   <li>AC1 — POST returns 201 with Location header</li>
 *   <li>AC1 — PUT updates an existing activity type</li>
 *   <li>AC1 — DELETE removes an activity type (204)</li>
 *   <li>AC7 — POST with duplicate name returns 409</li>
 *   <li>AC1 — POST with unknown rule returns 400</li>
 *   <li>AC1 — POST with capacity=0 returns 400 (Bean Validation @Min)</li>
 *   <li>AC9 — 401 without credentials</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S06.story.md">Story E08S06</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                ActivityTypeControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e08s06ctrldb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class ActivityTypeControllerIT {

    static final String TEST_PASSWORD = "E08S06CtrlTest01";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private DefaultTenantProvider defaultTenantProvider;

    @Autowired
    private TournamentRepository tournamentRepository;

    private String baseUrl;
    private TestRestTemplate authed;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD);

        // Set up tenant context for direct repository calls
        tenantContext.set(defaultTenantProvider.getDefaultTenantId());

        // Create a tournament for activity type operations
        tournamentId = UUID.randomUUID();
        Tournament t = new Tournament(
                tournamentId, defaultTenantProvider.getDefaultTenantId(),
                "Activity Test Tournament " + tournamentId,
                MatchFormat.BEST_OF_3.name(), "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now());
        tournamentRepository.save(t);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    // =========================================================================
    // AC9 — Security: 401 without credentials
    // =========================================================================

    @Test
    void listActivityTypesReturns401WithoutCredentials() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                String.class);

        assertThat(response.getStatusCode())
                .as("AC9 — /api/tournaments/{id}/activity-types without credentials must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC1 — GET returns empty list when no activity types configured
    // =========================================================================

    @Test
    void listActivityTypesReturnsEmptyArrayWhenNoneConfigured() throws Exception {
        ResponseEntity<ActivityTypeResponse[]> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                ActivityTypeResponse[].class);

        assertThat(response.getStatusCode())
                .as("AC1 — GET with no activity types must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("AC1 — response body must be an empty array (not null)")
                .isNotNull()
                .isEmpty();
    }

    // =========================================================================
    // AC1 — POST creates activity type (201 + Location)
    // =========================================================================

    @Test
    void createActivityTypeReturns201WithLocationHeader() throws Exception {
        ActivityTypeCreateRequest request = new ActivityTypeCreateRequest(
                "Mannschaftsfoto", AssignmentRule.FIRST_FREE_ROUND.name(), null, 1);

        ResponseEntity<ActivityTypeResponse> response = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                request,
                ActivityTypeResponse.class);

        assertThat(response.getStatusCode())
                .as("AC1 — POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation())
                .as("AC1 — 201 response must include Location header")
                .isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("Mannschaftsfoto");
        assertThat(response.getBody().assignmentRule()).isEqualTo("FIRST_FREE_ROUND");
        assertThat(response.getBody().capacityPerRound()).isNull();
        assertThat(response.getBody().id()).isNotNull();
    }

    // =========================================================================
    // AC1 — GET list includes created activity type, sorted by sortOrder
    // =========================================================================

    @Test
    void listActivityTypesReturnsCreatedEntries() throws Exception {
        // Create two activity types with different sort orders
        authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                new ActivityTypeCreateRequest("Fotoshooting", AssignmentRule.FIRST_FREE_ROUND.name(), 2, 2),
                ActivityTypeResponse.class);
        authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                new ActivityTypeCreateRequest("Warm-up", AssignmentRule.FIRST_FREE_ROUND.name(), null, 1),
                ActivityTypeResponse.class);

        ResponseEntity<ActivityTypeResponse[]> listResponse = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                ActivityTypeResponse[].class);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(2);
        // Ordered by sort_order ascending: Warm-up (1) before Fotoshooting (2)
        assertThat(listResponse.getBody()[0].name()).isEqualTo("Warm-up");
        assertThat(listResponse.getBody()[1].name()).isEqualTo("Fotoshooting");
    }

    // =========================================================================
    // AC1 — PUT updates activity type (200)
    // =========================================================================

    @Test
    void updateActivityTypeReturns200() throws Exception {
        // Create first
        ResponseEntity<ActivityTypeResponse> createResponse = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                new ActivityTypeCreateRequest("OldName", AssignmentRule.FIRST_FREE_ROUND.name(), null, 1),
                ActivityTypeResponse.class);
        assertThat(createResponse.getBody()).isNotNull();
        UUID activityId = createResponse.getBody().id();

        // Update
        ActivityTypeUpdateRequest updateRequest = new ActivityTypeUpdateRequest(
                "NewName", AssignmentRule.FIRST_FREE_ROUND.name(), 3, 2);
        ResponseEntity<ActivityTypeResponse> updateResponse = authed.exchange(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types/" + activityId),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest),
                ActivityTypeResponse.class);

        assertThat(updateResponse.getStatusCode())
                .as("AC1 — PUT must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody()).isNotNull();
        assertThat(updateResponse.getBody().name()).isEqualTo("NewName");
        assertThat(updateResponse.getBody().capacityPerRound()).isEqualTo(3);
    }

    // =========================================================================
    // AC1 — DELETE removes activity type (204)
    // =========================================================================

    @Test
    void deleteActivityTypeReturns204() throws Exception {
        // Create first
        ResponseEntity<ActivityTypeResponse> createResponse = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                new ActivityTypeCreateRequest("ToDelete", AssignmentRule.FIRST_FREE_ROUND.name(), null, 1),
                ActivityTypeResponse.class);
        assertThat(createResponse.getBody()).isNotNull();
        UUID activityId = createResponse.getBody().id();

        // Delete
        ResponseEntity<Void> deleteResponse = authed.exchange(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types/" + activityId),
                HttpMethod.DELETE,
                null,
                Void.class);

        assertThat(deleteResponse.getStatusCode())
                .as("AC1 — DELETE must return 204 No Content")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Verify gone
        ResponseEntity<ActivityTypeResponse[]> listAfterDelete = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                ActivityTypeResponse[].class);
        assertThat(listAfterDelete.getBody()).isNotNull().isEmpty();
    }

    // =========================================================================
    // AC7 — Duplicate name returns 409
    // =========================================================================

    @Test
    void createWithDuplicateNameReturns409() throws Exception {
        String name = "UniquePhoto-" + UUID.randomUUID();

        authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                new ActivityTypeCreateRequest(name, AssignmentRule.FIRST_FREE_ROUND.name(), null, 1),
                ActivityTypeResponse.class);

        ResponseEntity<String> dupResponse = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                new ActivityTypeCreateRequest(name, AssignmentRule.FIRST_FREE_ROUND.name(), null, 2),
                String.class);

        assertThat(dupResponse.getStatusCode())
                .as("AC7 — duplicate name must return 409 Conflict")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // =========================================================================
    // AC1 — Unknown assignment rule returns 400
    // =========================================================================

    @Test
    void createWithUnknownRuleReturns400() throws Exception {
        ResponseEntity<String> response = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                new ActivityTypeCreateRequest("Photo", "UNKNOWN_RULE", null, 1),
                String.class);

        assertThat(response.getStatusCode())
                .as("AC1 — unknown assignmentRule must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC1 — capacity=0 returns 400 (Bean Validation @Min(1))
    // =========================================================================

    @Test
    void createWithZeroCapacityReturns400() throws Exception {
        // Bean Validation rejects capacity < 1 before the service layer
        ResponseEntity<String> response = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                new ActivityTypeCreateRequest("Photo", AssignmentRule.FIRST_FREE_ROUND.name(), 0, 1),
                String.class);

        assertThat(response.getStatusCode())
                .as("AC1 — capacityPerRound=0 must return 400 Bad Request")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC1 — GET on unknown tournament returns 404
    // =========================================================================

    @Test
    void listActivityTypesOnUnknownTournamentReturns404() throws Exception {
        ResponseEntity<String> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + UUID.randomUUID() + "/activity-types"),
                String.class);

        assertThat(response.getStatusCode())
                .as("AC1 — GET on unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // Test-local AdminCredentials — overrides prod bootstrap for test isolation
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
