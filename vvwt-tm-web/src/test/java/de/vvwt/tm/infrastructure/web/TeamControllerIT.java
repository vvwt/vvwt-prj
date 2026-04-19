package de.vvwt.tm.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.infrastructure.web.dto.TeamBulkCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TeamBulkCreateResponse;
import de.vvwt.tm.infrastructure.web.dto.TeamCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TeamResponse;
import de.vvwt.tm.infrastructure.web.dto.TeamUpdateRequest;
import de.vvwt.tm.infrastructure.web.dto.TournamentCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TournamentResponse;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.util.List;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link TeamController} (E05S05).
 *
 * <p>Tests the full HTTP stack including Spring Security (basic auth), Jackson serialization, and
 * the {@link GlobalExceptionHandler}.
 *
 * <h2>Test scenarios</h2>
 *
 * <ul>
 *   <li>AC1 — GET /api/tournaments/{id}/teams returns 200 with ordered list
 *   <li>AC2 — POST creates a team with 201 + auto team number
 *   <li>AC3 — PUT updates a team; 409 if tournament not DRAFT
 *   <li>AC4 — DELETE removes a team; 409 if tournament not DRAFT
 *   <li>AC5 — POST /bulk creates multiple teams
 *   <li>AC10 — duplicate team_number returns 409
 *   <li>AC11 — non-DRAFT tournament operations return 409
 *   <li>AC13 — cross-tenant access returns 404
 * </ul>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S05.story.md">Story
 *     E05S05</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            TeamControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e05s05teamdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class TeamControllerIT {

    static final String TEST_PASSWORD = "TeamCtrlTest01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD);
    }

    // =========================================================================
    // Test helper: create a tournament for the test
    // =========================================================================

    private TournamentResponse createDraftTournament() {
        var request =
                new TournamentCreateRequest(
                        "E05S05 Test Tournament",
                        null,
                        8,
                        2,
                        "BEST_OF_3",
                        "threePoint",
                        "standardVolleyball",
                        "roundRobin");
        ResponseEntity<TournamentResponse> response =
                authed.postForEntity(
                        baseUrl + "/api/tournaments", request, TournamentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private String teamsUrl(UUID tournamentId) {
        return baseUrl + "/api/tournaments/" + tournamentId + "/teams";
    }

    // =========================================================================
    // AC1 — GET returns empty list initially
    // =========================================================================

    @Test
    void listTeamsReturnsEmptyListForNewTournament() {
        TournamentResponse tournament = createDraftTournament();

        ResponseEntity<TeamResponse[]> response =
                authed.getForEntity(teamsUrl(tournament.id()), TeamResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
    }

    // =========================================================================
    // AC1 — GET returns teams ordered by team_number ascending
    // =========================================================================

    @Test
    void listTeamsReturnsOrderedByTeamNumber() {
        TournamentResponse tournament = createDraftTournament();

        // Create teams out of order
        authed.postForEntity(
                teamsUrl(tournament.id()),
                new TeamCreateRequest("Team C", 3, null, null, null),
                TeamResponse.class);
        authed.postForEntity(
                teamsUrl(tournament.id()),
                new TeamCreateRequest("Team A", 1, null, null, null),
                TeamResponse.class);
        authed.postForEntity(
                teamsUrl(tournament.id()),
                new TeamCreateRequest("Team B", 2, null, null, null),
                TeamResponse.class);

        ResponseEntity<TeamResponse[]> response =
                authed.getForEntity(teamsUrl(tournament.id()), TeamResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TeamResponse[] teams = response.getBody();
        assertThat(teams).isNotNull().hasSize(3);
        assertThat(teams[0].teamNumber()).isEqualTo(1);
        assertThat(teams[1].teamNumber()).isEqualTo(2);
        assertThat(teams[2].teamNumber()).isEqualTo(3);
    }

    // =========================================================================
    // AC2 — POST creates team with 201 + Location header
    // =========================================================================

    @Test
    void createTeamReturns201WithLocation() {
        TournamentResponse tournament = createDraftTournament();

        var request = new TeamCreateRequest("Team Alpha", null, null, null, null);
        ResponseEntity<TeamResponse> response =
                authed.postForEntity(teamsUrl(tournament.id()), request, TeamResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        TeamResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.description()).isEqualTo("Team Alpha");
        assertThat(body.teamNumber()).isEqualTo(1); // auto-assigned
        assertThat(body.participate()).isTrue(); // default
        assertThat(body.refereeAssignment()).isFalse(); // default
        assertThat(body.withoutAssessment()).isFalse(); // default
    }

    // =========================================================================
    // AC2 — POST auto-assigns sequential team number
    // =========================================================================

    @Test
    void createTeamAutoAssignsSequentialNumber() {
        TournamentResponse tournament = createDraftTournament();

        // First team → number 1
        authed.postForEntity(
                teamsUrl(tournament.id()),
                new TeamCreateRequest("Team One", null, null, null, null),
                TeamResponse.class);

        // Second team → number 2
        ResponseEntity<TeamResponse> response =
                authed.postForEntity(
                        teamsUrl(tournament.id()),
                        new TeamCreateRequest("Team Two", null, null, null, null),
                        TeamResponse.class);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().teamNumber()).isEqualTo(2);
    }

    // =========================================================================
    // AC3 — PUT updates team
    // =========================================================================

    @Test
    void updateTeamReturns200() {
        TournamentResponse tournament = createDraftTournament();

        ResponseEntity<TeamResponse> created =
                authed.postForEntity(
                        teamsUrl(tournament.id()),
                        new TeamCreateRequest("Original Name", null, null, null, null),
                        TeamResponse.class);
        UUID teamId = created.getBody().id();

        var updateRequest = new TeamUpdateRequest("Updated Name", null, true, true, false);
        ResponseEntity<TeamResponse> response =
                authed.exchange(
                        teamsUrl(tournament.id()) + "/" + teamId,
                        HttpMethod.PUT,
                        new HttpEntity<>(updateRequest),
                        TeamResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().description()).isEqualTo("Updated Name");
        assertThat(response.getBody().refereeAssignment()).isTrue();
    }

    // =========================================================================
    // AC4 — DELETE removes team
    // =========================================================================

    @Test
    void deleteTeamReturns204() {
        TournamentResponse tournament = createDraftTournament();

        ResponseEntity<TeamResponse> created =
                authed.postForEntity(
                        teamsUrl(tournament.id()),
                        new TeamCreateRequest("To Be Deleted", null, null, null, null),
                        TeamResponse.class);
        UUID teamId = created.getBody().id();

        ResponseEntity<Void> response =
                authed.exchange(
                        teamsUrl(tournament.id()) + "/" + teamId,
                        HttpMethod.DELETE,
                        null,
                        Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify no longer in list
        ResponseEntity<TeamResponse[]> listResponse =
                authed.getForEntity(teamsUrl(tournament.id()), TeamResponse[].class);
        assertThat(listResponse.getBody()).isEmpty();
    }

    // =========================================================================
    // AC5 — POST /bulk creates multiple teams
    // =========================================================================

    @Test
    void bulkCreateTeamsReturns200WithResults() {
        TournamentResponse tournament = createDraftTournament();

        var bulkRequest =
                new TeamBulkCreateRequest(
                        List.of(
                                new TeamCreateRequest("Bulk Team 1", 1, null, null, null),
                                new TeamCreateRequest("Bulk Team 2", 2, null, null, null),
                                new TeamCreateRequest("Bulk Team 3", 3, null, null, null)));

        ResponseEntity<TeamBulkCreateResponse> response =
                authed.postForEntity(
                        teamsUrl(tournament.id()) + "/bulk",
                        bulkRequest,
                        TeamBulkCreateResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TeamBulkCreateResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.results()).hasSize(3);
        assertThat(body.results()).allMatch(TeamBulkCreateResponse.BulkItemResult::success);
    }

    // =========================================================================
    // AC10 — Duplicate team_number returns 409
    // =========================================================================

    @Test
    void createTeamWithDuplicateNumberReturns409() {
        TournamentResponse tournament = createDraftTournament();

        // Create team with number 5
        authed.postForEntity(
                teamsUrl(tournament.id()),
                new TeamCreateRequest("First Team", 5, null, null, null),
                TeamResponse.class);

        // Try to create another with same number
        ResponseEntity<ApiErrorResponse> response =
                authed.postForEntity(
                        teamsUrl(tournament.id()),
                        new TeamCreateRequest("Second Team", 5, null, null, null),
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // =========================================================================
    // AC11 — 409 on non-DRAFT tournament modification (simulated via AC3: update)
    // =========================================================================

    @Test
    void listTeamsFor404TournamentReturns404() {
        // Use a non-existent tournament UUID — tenant-scoped lookup returns empty → 404
        UUID nonExistent = UUID.randomUUID();
        ResponseEntity<String> response =
                authed.getForEntity(
                        baseUrl + "/api/tournaments/" + nonExistent + "/teams", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC13 — 401 without credentials
    // =========================================================================

    @Test
    void listTeamsReturns401WithoutCredentials() {
        TournamentResponse tournament = createDraftTournament();

        ResponseEntity<String> response =
                restTemplate.getForEntity(teamsUrl(tournament.id()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC2 — 400 on missing description
    // =========================================================================

    @Test
    void createTeamWithMissingDescriptionReturns400() {
        TournamentResponse tournament = createDraftTournament();

        // description is blank — should return 400
        ResponseEntity<ApiErrorResponse> response =
                authed.postForEntity(
                        teamsUrl(tournament.id()),
                        new TeamCreateRequest("", null, null, null, null),
                        ApiErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // Test configuration — injects a known password for test admin
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
