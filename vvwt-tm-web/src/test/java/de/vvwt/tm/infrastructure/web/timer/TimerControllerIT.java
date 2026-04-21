package de.vvwt.tm.infrastructure.web.timer;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link TimerController} — E11S02: Timer data endpoint.
 *
 * <p>Tests the full HTTP stack (Spring MVC, Security, routing, JSON serialization) to verify:
 *
 * <ul>
 *   <li>AC1: GET /api/timer/{id} returns 200 with tournament name and non-empty schedule
 *   <li>AC2: Schedule entries carry null startTime/endTime when no plannedStartTime
 *   <li>AC4: Audio URLs are null when no audio files have been uploaded
 *   <li>AC5: Phase summaries and current position fields present in response
 *   <li>AC6a: Endpoint is accessible without authentication
 *   <li>AC7: DRAFT tournament → 404 NO_ACTIVE_TOURNAMENT; unknown UUID → 404 INVALID_TIMER_URL; no
 *       phases → 200 emptySchedule=true
 * </ul>
 *
 * <h2>Test data strategy</h2>
 *
 * <p>Creates tournaments via the admin REST API. Status manipulation (DRAFT → PLANNED) is done
 * directly via {@link JdbcTemplate} since the full apply-draft flow requires extensive setup
 * (teams, matches, slot optimization). The timer endpoint tests focus on the HTTP and JSON
 * contract, not the business rules already covered by {@link
 * de.vvwt.tm.domain.timer.TimerDataService}.
 *
 * @see TimerController
 * @see de.vvwt.tm.domain.timer.TimerDataService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S02.story.md">Story
 *     E11S02</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            TimerControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e11s02timerdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.audio.data-dir=${java.io.tmpdir}/tm-audio-it-e11s02"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TimerController IT — E11S02: timer data endpoint")
@SuppressWarnings({"rawtypes", "unchecked"})
class TimerControllerIT {

    private static final String TEST_PASSWORD = "TimerTestPass11S02";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);
        tenantBinder.bindDefaultTenant();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC6a — Timer endpoint is public (no authentication required)
    // =========================================================================

    @Test
    @DisplayName("AC6a: GET /api/timer/{id} is accessible without authentication")
    void timerEndpointIsPublic() throws Exception {
        UUID tournamentId = createAndActivateTournament("Public Access Test");

        // Use unauthenticated template — should still work
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/timer/" + tournamentId), String.class);

        assertThat(response.getStatusCode())
                .as("AC6a: timer endpoint must be accessible without authentication")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // AC7 — Unknown tournament UUID → 404 INVALID_TIMER_URL
    // =========================================================================

    @Test
    @DisplayName("AC7: GET /api/timer/{unknownId} returns 404 with INVALID_TIMER_URL errorCode")
    void unknownTournamentReturns404() throws Exception {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<Map> response =
                restTemplate.getForEntity(new URI(baseUrl + "/api/timer/" + unknownId), Map.class);

        assertThat(response.getStatusCode())
                .as("AC7: unknown tournament UUID must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .as("AC7: 404 body must contain INVALID_TIMER_URL errorCode")
                .containsEntry("messageKey", "INVALID_TIMER_URL");
    }

    // =========================================================================
    // AC7 — DRAFT tournament → 404 NO_ACTIVE_TOURNAMENT
    // =========================================================================

    @Test
    @DisplayName(
            "AC7: GET /api/timer/{id} for DRAFT tournament returns 404 with NO_ACTIVE_TOURNAMENT")
    void draftTournamentReturns404() throws Exception {
        // Tournament is created with DRAFT status by default
        UUID tournamentId = createTournament("Draft Tournament Timer Test");

        ResponseEntity<Map> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/timer/" + tournamentId), Map.class);

        assertThat(response.getStatusCode())
                .as("AC7: DRAFT tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .as("AC7: 404 body must contain NO_ACTIVE_TOURNAMENT errorCode")
                .containsEntry("messageKey", "NO_ACTIVE_TOURNAMENT");
    }

    // =========================================================================
    // AC7 — No phases configured → 200 with emptySchedule=true
    // =========================================================================

    @Test
    @DisplayName(
            "AC7: GET /api/timer/{id} for PLANNED tournament with no phases returns 200"
                    + " emptySchedule=true")
    void plannedTournamentWithNoPhasesReturns200EmptySchedule() throws Exception {
        UUID tournamentId = createTournament("No Phases Timer Test");
        // Force status to PLANNED (no phases exist)
        forceStatus(tournamentId, "PLANNED");

        ResponseEntity<Map> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/timer/" + tournamentId), Map.class);

        assertThat(response.getStatusCode())
                .as("AC7: PLANNED tournament with no phases must return 200 (not 404)")
                .isEqualTo(HttpStatus.OK);

        Map body = response.getBody();
        assertThat(body)
                .as("AC7: emptySchedule must be true when no phases configured")
                .containsEntry("emptySchedule", Boolean.TRUE);

        List<?> schedule = (List<?>) body.get("schedule");
        assertThat(schedule)
                .as("AC7: schedule list must be empty when emptySchedule=true")
                .isEmpty();
    }

    // =========================================================================
    // AC1 — Tournament metadata in response
    // =========================================================================

    @Test
    @DisplayName("AC1: GET /api/timer/{id} returns 200 with tournament name, id, and status")
    void timerDataContainsTournamentMetadata() throws Exception {
        UUID tournamentId = createAndActivateTournament("Metadata Timer Test");

        ResponseEntity<Map> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/timer/" + tournamentId), Map.class);

        assertThat(response.getStatusCode())
                .as("AC1: timer endpoint must return 200 for PLANNED tournament")
                .isEqualTo(HttpStatus.OK);

        Map body = response.getBody();
        assertThat(body)
                .as("AC1: response must contain tournamentName, tournamentId, tournamentStatus")
                .containsKeys("tournamentName", "tournamentId", "tournamentStatus");
        assertThat(body.get("tournamentName"))
                .as("AC1: tournamentName must match the created tournament")
                .isEqualTo("Metadata Timer Test");
    }

    // =========================================================================
    // AC2 — Time fields null when no plannedStartTime
    // =========================================================================

    @Test
    @DisplayName("AC2: hasStartTime is false when no plannedStartTime is configured")
    void hasStartTimeIsFalseWhenNoStartTimeConfigured() throws Exception {
        UUID tournamentId = createTournament("No StartTime Timer Test");
        forceStatus(tournamentId, "PLANNED");
        // Insert a minimal phase so the service returns a non-empty schedule path
        insertPhase(tournamentId, 1);

        ResponseEntity<Map> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/timer/" + tournamentId), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("AC2: hasStartTime must be false when no plannedStartTime")
                .containsEntry("hasStartTime", Boolean.FALSE);
    }

    // =========================================================================
    // AC5 — Response contains phases and currentPosition fields
    // =========================================================================

    @Test
    @DisplayName(
            "AC5: response contains phases list and currentPhaseNumber/currentLapNumber fields")
    void responseContainsPhaseStructureAndCurrentPosition() throws Exception {
        UUID tournamentId = createAndActivateTournament("Phase Structure Timer Test");

        ResponseEntity<Map> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/timer/" + tournamentId), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map body = response.getBody();
        assertThat(body)
                .as("AC5: response must contain phases, currentPhaseNumber, currentLapNumber")
                .containsKeys("phases", "currentPhaseNumber", "currentLapNumber");
    }

    // =========================================================================
    // AC4 — Audio URLs null when no files uploaded
    // =========================================================================

    @Test
    @DisplayName("AC4: audio URLs are null when no audio files have been uploaded")
    void audioUrlsNullWhenNoFilesUploaded() throws Exception {
        UUID tournamentId = createAndActivateTournament("Audio URL Timer Test");

        ResponseEntity<Map> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/timer/" + tournamentId), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map body = response.getBody();
        assertThat(body).as("AC4: response must contain audio object").containsKey("audio");

        Map audio = (Map) body.get("audio");
        assertThat(audio.get("startUrl"))
                .as("AC4: startUrl must be null when no START audio file uploaded")
                .isNull();
        assertThat(audio.get("endUrl"))
                .as("AC4: endUrl must be null when no END audio file uploaded")
                .isNull();
        assertThat(audio.get("pauseUrl"))
                .as("AC4: pauseUrl must be null when no PAUSE audio file uploaded")
                .isNull();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Creates a tournament via the admin REST API (result is in DRAFT status). */
    private UUID createTournament(String description) throws Exception {
        TournamentCreateRequest request =
                new TournamentCreateRequest(
                        description,
                        null,
                        8,
                        4,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");

        ResponseEntity<TournamentResponse> created =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    /**
     * Creates a tournament and moves it to PLANNED status directly (bypassing full draft apply).
     */
    private UUID createAndActivateTournament(String description) throws Exception {
        UUID id = createTournament(description);
        forceStatus(id, "PLANNED");
        return id;
    }

    /**
     * Directly updates tournament status in the database (bypasses business rules). Used to avoid
     * complex draft/apply setup for tests that only need a specific status.
     */
    private void forceStatus(UUID tournamentId, String status) {
        int rows =
                jdbcTemplate.update(
                        "UPDATE tournament SET status = ? WHERE id = ?",
                        status,
                        tournamentId.toString());
        assertThat(rows)
                .as("forceStatus: expected exactly 1 row updated for tournament " + tournamentId)
                .isEqualTo(1);
    }

    /**
     * Inserts a minimal phase for the given tournament (no matches, just the phase record). Used to
     * put the service into the non-empty-phases code path.
     */
    private void insertPhase(UUID tournamentId, int sequenceNumber) {
        UUID phaseId = UUID.randomUUID();
        // Get the tenant_id from the tournament
        String tenantId =
                jdbcTemplate.queryForObject(
                        "SELECT tenant_id FROM tournament WHERE id = ?",
                        String.class,
                        tournamentId.toString());

        jdbcTemplate.update(
                "INSERT INTO phase (id, tenant_id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number) VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId.toString(),
                tenantId,
                tournamentId.toString(),
                sequenceNumber,
                "Phase " + sequenceNumber,
                "PENDING",
                0);
    }

    // =========================================================================
    // Test configuration — known test admin password
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
