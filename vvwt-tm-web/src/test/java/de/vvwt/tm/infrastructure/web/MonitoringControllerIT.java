package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.domain.repo.TenantContextTestHelper;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.dto.CurrentLapSummaryResponse;
import de.vvwt.tm.infrastructure.web.dto.LapMatchesResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MonitoringController} (AC1–AC4, AC13, AC14 — E05S10).
 *
 * <p>Verifies the full HTTP stack: security, routing, JSON serialization, and
 * {@link GlobalExceptionHandler} 404/401 handling.
 *
 * <h2>Scenarios</h2>
 * <ul>
 *   <li>AC13 — all endpoints return 401 without credentials</li>
 *   <li>AC14 — all endpoints return 404 for an unknown phaseId (cross-tenant guard)</li>
 *   <li>AC1  — GET group table returns 200 with empty array for a phase with no avatars in group 1</li>
 *   <li>AC2  — GET all group tables returns 200 (empty map when no avatars)</li>
 *   <li>AC3  — GET lap matches returns 200 with empty matches list</li>
 *   <li>AC4  — GET current-lap returns 200 with phase status</li>
 * </ul>
 *
 * @see MonitoringController
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S10.story.md">Story E05S10</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                MonitoringControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e05s10monitordb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class MonitoringControllerIT {

    static final String TEST_PASSWORD = "MonitorCtrlTest01";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired private TenantContext tenantContext;
    @Autowired private DefaultTenantProvider defaultTenantProvider;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;

    private String baseUrl;
    private TestRestTemplate authed;
    private UUID defaultTenantId;

    /** UUID of a phase created in setUp() for positive tests. */
    private UUID existingPhaseId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD);
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
        existingPhaseId = createMinimalPhaseFixture();
        TenantContextTestHelper.clear(tenantContext);
    }

    @AfterEach
    void tearDown() {
        TenantContextTestHelper.clear(tenantContext);
    }

    // =========================================================================
    // AC13 — 401 without credentials
    // =========================================================================

    @Test
    void groupTable_returns401WithoutCredentials() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                baseUrl + "/api/phases/" + existingPhaseId + "/groups/1/table",
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allGroupTables_returns401WithoutCredentials() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                baseUrl + "/api/phases/" + existingPhaseId + "/groups",
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void lapMatches_returns401WithoutCredentials() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                baseUrl + "/api/phases/" + existingPhaseId + "/laps/1/matches",
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void currentLap_returns401WithoutCredentials() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                baseUrl + "/api/phases/" + existingPhaseId + "/current-lap",
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC14 — 404 for unknown phaseId (tenant isolation / info-leakage prevention)
    // =========================================================================

    @Test
    void groupTable_returns404ForUnknownPhase() {
        ResponseEntity<String> resp = authed.getForEntity(
                baseUrl + "/api/phases/" + UUID.randomUUID() + "/groups/1/table",
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void allGroupTables_returns404ForUnknownPhase() {
        ResponseEntity<String> resp = authed.getForEntity(
                baseUrl + "/api/phases/" + UUID.randomUUID() + "/groups",
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lapMatches_returns404ForUnknownPhase() {
        ResponseEntity<String> resp = authed.getForEntity(
                baseUrl + "/api/phases/" + UUID.randomUUID() + "/laps/1/matches",
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void currentLap_returns404ForUnknownPhase() {
        ResponseEntity<String> resp = authed.getForEntity(
                baseUrl + "/api/phases/" + UUID.randomUUID() + "/current-lap",
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC1–AC4 — functional: 200 responses with a seeded phase (AC14)
    // =========================================================================

    /** AC2 — all group tables returns 200 with empty map (no avatars seeded). */
    @Test
    void allGroupTables_returns200WithEmptyMap_forPhaseWithNoAvatars() {
        @SuppressWarnings("unchecked")
        ResponseEntity<Map> resp = authed.getForEntity(
                baseUrl + "/api/phases/" + existingPhaseId + "/groups",
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull().isEmpty();
    }

    /** AC4 — current-lap returns 200 with PENDING status (no matches started). */
    @Test
    void currentLap_returns200WithPendingStatus_forNewPhase() {
        ResponseEntity<CurrentLapSummaryResponse> resp = authed.getForEntity(
                baseUrl + "/api/phases/" + existingPhaseId + "/current-lap",
                CurrentLapSummaryResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().phaseStatus()).isEqualTo("PENDING");
        assertThat(resp.getBody().currentLapNumber()).isEqualTo(0);
        assertThat(resp.getBody().totalLapCount()).isEqualTo(0);
    }

    /** AC3 — lap matches returns 200 with empty list when no matches exist in lap 1. */
    @Test
    void lapMatches_returns200WithEmptyMatchList_forNewPhase() {
        ResponseEntity<LapMatchesResponse> resp = authed.getForEntity(
                baseUrl + "/api/phases/" + existingPhaseId + "/laps/1/matches",
                LapMatchesResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().matches()).isEmpty();
    }

    /** AC1 — single group table returns 200 with empty list when no avatars in group 1. */
    @Test
    @SuppressWarnings("unchecked")
    void groupTable_returns200WithEmptyList_forPhaseWithNoAvatarsInGroup1() {
        ResponseEntity<Object[]> resp = authed.getForEntity(
                baseUrl + "/api/phases/" + existingPhaseId + "/groups/1/table",
                Object[].class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull().isEmpty();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Seeds a minimal tournament + phase fixture directly via repositories for use
     * in the positive-path tests (AC1–AC4). Tournament is DRAFT status to avoid the
     * DEC-5 active_sentinel unique constraint.
     *
     * <p>The phase is in PENDING status with currentLapNumber=0 and no matches/avatars.
     *
     * @return the UUID of the created phase
     */
    private UUID createMinimalPhaseFixture() {
        UUID tournamentId = UUID.randomUUID();
        tournamentRepository.save(new Tournament(
                tournamentId, defaultTenantId,
                "E05S10 Monitoring IT Tournament " + tournamentId,
                MatchFormat.BEST_OF_3.name(), "threePoint", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now()));

        UUID phaseId = UUID.randomUUID();
        phaseRepository.save(new Phase(phaseId, defaultTenantId, tournamentId, 1,
                "Phase 1", Phase.PhaseStatus.PENDING.name(), 0, LocalDateTime.now()));

        return phaseId;
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
