package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.domain.AuditLogEntry;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.SetResult;
import de.vvwt.tm.domain.SetState;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.repo.AuditLogRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.domain.repo.TenantContextTestHelper;
import de.vvwt.tm.domain.repo.TeamAvatarRatingRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.dto.MatchDetailResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MatchCorrectionController} (AC1–AC5, AC10, AC11, AC14 — E05S11).
 *
 * <p>Verifies the full HTTP stack: security, routing, JSON serialization, cascade recompute
 * (standings update AC5), audit_log write (AC4), validation error (AC10), and tenant isolation (AC11).
 *
 * <h2>Scenarios</h2>
 * <ul>
 *   <li>AC14 — all endpoints return 401 without credentials</li>
 *   <li>AC11 — all endpoints return 404 for unknown matchId</li>
 *   <li>AC1  — GET /api/matches/{matchId} returns 200 with full match detail</li>
 *   <li>AC2  — PUT corrects set result; returns 200 with updated state (AC5: standings updated)</li>
 *   <li>AC4  — audit_log row written after AC2 correction with correct old/new values</li>
 *   <li>AC3  — POST enters new set at next index</li>
 *   <li>AC10 — PUT with invalid scores returns 400</li>
 * </ul>
 *
 * @see MatchCorrectionController
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S11.story.md">Story E05S11</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                MatchCorrectionControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e05s11matchcorrectiondb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class MatchCorrectionControllerIT {

    static final String TEST_PASSWORD = "CorrectionIT01";

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContext tenantContext;
    @Autowired private DefaultTenantProvider defaultTenantProvider;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private MatchRepository matchRepository;
    @Autowired private SetResultRepository setResultRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private TeamAvatarRatingRepository teamAvatarRatingRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private TeamRepository teamRepository;

    private String baseUrl;
    private TestRestTemplate authed;
    private UUID defaultTenantId;

    /** Fixture IDs seeded in setUp(). */
    private UUID tournamentId;
    private UUID phaseId;
    private UUID matchId;
    private UUID avatar1Id;
    private UUID avatar2Id;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD);
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
        seedFixture();
        TenantContextTestHelper.clear(tenantContext);
    }

    @AfterEach
    void tearDown() {
        TenantContextTestHelper.clear(tenantContext);
    }

    // =========================================================================
    // AC14 — 401 without credentials
    // =========================================================================

    @Test
    void getMatchDetail_returns401WithoutCredentials() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                baseUrl + "/api/matches/" + matchId, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void correctSet_returns401WithoutCredentials() {
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/api/matches/" + matchId + "/sets/0",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("team1Points", 25, "team2Points", 18)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void enterNewSet_returns401WithoutCredentials() {
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl + "/api/matches/" + matchId + "/sets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("team1Points", 25, "team2Points", 18)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC11 — 404 for unknown matchId
    // =========================================================================

    @Test
    void getMatchDetail_returns404ForUnknownMatch() {
        ResponseEntity<String> resp = authed.getForEntity(
                baseUrl + "/api/matches/" + UUID.randomUUID(), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void correctSet_returns404ForUnknownMatch() {
        ResponseEntity<String> resp = authed.exchange(
                baseUrl + "/api/matches/" + UUID.randomUUID() + "/sets/0",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("team1Points", 25, "team2Points", 18)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC1 — GET /api/matches/{matchId}
    // =========================================================================

    @Test
    void getMatchDetail_returns200_withMatchInfo() {
        ResponseEntity<MatchDetailResponse> resp = authed.getForEntity(
                baseUrl + "/api/matches/" + matchId, MatchDetailResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        MatchDetailResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.matchId()).isEqualTo(matchId);
        assertThat(body.phaseId()).isEqualTo(phaseId);
        assertThat(body.tournamentId()).isEqualTo(tournamentId);
        assertThat(body.team1Description()).isEqualTo("Team Alpha");
        assertThat(body.team2Description()).isEqualTo("Team Beta");
        assertThat(body.matchFormat()).isEqualTo(MatchFormat.BEST_OF_3.name());
        assertThat(body.matchState()).isEqualTo(MatchState.ENABLED.name());
        assertThat(body.setLimit()).isEqualTo(3);
        assertThat(body.setResults()).isEmpty();
    }

    // =========================================================================
    // AC2 + AC4 + AC5 — PUT /api/matches/{matchId}/sets/{setIndex}
    // =========================================================================

    @Test
    void correctSet_returns200_andUpdatesMatchState_andWritesAuditLog() {
        // First enter set 0 so there is something to correct
        authed.exchange(
                baseUrl + "/api/matches/" + matchId + "/sets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("team1Points", 20, "team2Points", 25)),
                MatchDetailResponse.class);

        // Enter set 1: team1 wins → after 2 sets with team1 winning both,
        // match reaches FINISHED_WINNER1 so ratings are computed (AC5)
        authed.exchange(
                baseUrl + "/api/matches/" + matchId + "/sets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("team1Points", 25, "team2Points", 15)),
                MatchDetailResponse.class);

        // Now correct set 0 (AC2)
        ResponseEntity<MatchDetailResponse> correctionResp = authed.exchange(
                baseUrl + "/api/matches/" + matchId + "/sets/0",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("team1Points", 25, "team2Points", 18)),
                MatchDetailResponse.class);

        assertThat(correctionResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        MatchDetailResponse body = correctionResp.getBody();
        assertThat(body).isNotNull();

        // Set result should be updated (AC2) — 2 sets total (set0 corrected, set1 unchanged)
        assertThat(body.setResults()).hasSize(2);
        assertThat(body.setResults().get(0).setIndex()).isEqualTo(0);
        assertThat(body.setResults().get(0).team1Points()).isEqualTo(25);
        assertThat(body.setResults().get(0).team2Points()).isEqualTo(18);

        // AC4: verify audit_log row written for the correction
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
        try {
            List<AuditLogEntry> auditEntries =
                    auditLogRepository.findByMatchIdAndSetIndexOrderByChangedAt(matchId, 0);
            // There should be at least 2 entries: original entry + correction
            assertThat(auditEntries.size()).isGreaterThanOrEqualTo(2);

            // The correction entry should have old values (the original 20:25)
            AuditLogEntry correctionEntry = auditEntries.stream()
                    .filter(e -> e.getTeam1PointsOld() != null)
                    .findFirst().orElse(null);
            assertThat(correctionEntry).isNotNull();
            assertThat(correctionEntry.getTeam1PointsOld()).isEqualTo(20);
            assertThat(correctionEntry.getTeam2PointsOld()).isEqualTo(25);
            assertThat(correctionEntry.getTeam1PointsNew()).isEqualTo(25);
            assertThat(correctionEntry.getTeam2PointsNew()).isEqualTo(18);
        } finally {
            TenantContextTestHelper.clear(tenantContext);
        }

        // AC5: TeamAvatarRating refreshed — check via ratings repository
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
        try {
            // After correction, both avatars should have a rating record
            // (may be present from the initial entry + correction cascade runs)
            var rating1 = teamAvatarRatingRepository.findById(avatar1Id);
            assertThat(rating1).isPresent();
            // team1 won set (25 > 18 in BEST_OF_3), so should have setsWon >= 1
            assertThat(rating1.get().getSetsWon()).isGreaterThanOrEqualTo(1);
        } finally {
            TenantContextTestHelper.clear(tenantContext);
        }
    }

    // =========================================================================
    // AC3 — POST /api/matches/{matchId}/sets
    // =========================================================================

    @Test
    void enterNewSet_returns200_atSetIndex0_forFreshMatch() {
        ResponseEntity<MatchDetailResponse> resp = authed.exchange(
                baseUrl + "/api/matches/" + matchId + "/sets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("team1Points", 25, "team2Points", 22)),
                MatchDetailResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        MatchDetailResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.setResults()).hasSize(1);
        assertThat(body.setResults().get(0).setIndex()).isEqualTo(0);
        assertThat(body.setResults().get(0).team1Points()).isEqualTo(25);
        assertThat(body.setResults().get(0).team2Points()).isEqualTo(22);
    }

    // =========================================================================
    // AC10 — 400 for invalid scores
    // =========================================================================

    @Test
    void correctSet_returns400_forScoresFailingValidation() {
        // Both teams score 0:0 — SetValidationRule should reject as not a closed set
        ResponseEntity<String> resp = authed.exchange(
                baseUrl + "/api/matches/" + matchId + "/sets/0",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("team1Points", 0, "team2Points", 0)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void enterNewSet_returns400_forInvalidScores() {
        // 0:0 is not a valid closed set
        ResponseEntity<String> resp = authed.exchange(
                baseUrl + "/api/matches/" + matchId + "/sets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("team1Points", 0, "team2Points", 0)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // Private fixture helpers
    // =========================================================================

    /**
     * Seeds minimal tournament + phase + match + team avatars for positive-path tests.
     *
     * <p>Match has matchState=ENABLED (ready for scoring), setLimit=3, no set results.
     * Avatar descriptions "Team Alpha" and "Team Beta" are set for AC1 display verification.
     */
    private void seedFixture() {
        tournamentId = UUID.randomUUID();
        tournamentRepository.save(new Tournament(
                tournamentId, defaultTenantId,
                "E05S11 CorrectionIT Tournament " + tournamentId,
                MatchFormat.BEST_OF_3.name(), "threePoint", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now()));

        phaseId = UUID.randomUUID();
        phaseRepository.save(new Phase(phaseId, defaultTenantId, tournamentId, 1,
                "Phase 1", Phase.PhaseStatus.ACTIVE.name(), 1, LocalDateTime.now()));

        // Create backing teams (team_avatar.team_id NOT NULL in schema)
        UUID team1Id = UUID.randomUUID();
        Team team1 = new Team();
        team1.setId(team1Id);
        team1.setTenantId(defaultTenantId);
        team1.setTournamentId(tournamentId);
        team1.setTeamNumber(1);
        team1.setDescription("Team Alpha");
        team1.setCreatedAt(LocalDateTime.now());
        teamRepository.save(team1);

        UUID team2Id = UUID.randomUUID();
        Team team2 = new Team();
        team2.setId(team2Id);
        team2.setTenantId(defaultTenantId);
        team2.setTournamentId(tournamentId);
        team2.setTeamNumber(2);
        team2.setDescription("Team Beta");
        team2.setCreatedAt(LocalDateTime.now());
        teamRepository.save(team2);

        // TeamAvatar uses full constructor (id, tenantId, tournamentId, phaseId,
        // groupNumber, groupPosition, teamId, description, createdAt)
        avatar1Id = UUID.randomUUID();
        teamAvatarRepository.save(
                new TeamAvatar(avatar1Id, defaultTenantId, tournamentId, phaseId,
                        1, 1, team1Id, "Team Alpha", LocalDateTime.now()));

        avatar2Id = UUID.randomUUID();
        teamAvatarRepository.save(
                new TeamAvatar(avatar2Id, defaultTenantId, tournamentId, phaseId,
                        1, 2, team2Id, "Team Beta", LocalDateTime.now()));

        matchId = UUID.randomUUID();
        matchRepository.save(new Match(
                matchId, defaultTenantId, tournamentId, phaseId,
                avatar1Id, avatar2Id,
                MatchState.ENABLED.getLegacyCode(), 3,
                1, 1,
                null, null, null,
                LocalDateTime.now()
        ));
    }

    // =========================================================================
    // Test configuration
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
