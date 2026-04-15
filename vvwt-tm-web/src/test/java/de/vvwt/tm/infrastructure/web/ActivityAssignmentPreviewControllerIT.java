package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.domain.AssignmentRule;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.dto.ActivityAssignmentPreviewResponse;
import de.vvwt.tm.infrastructure.web.dto.ActivityTypeCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.ActivityTypeResponse;
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

import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ActivityAssignmentPreviewController} (E08S06, AC2, AC6).
 *
 * <h2>Test scenarios</h2>
 * <ul>
 *   <li>AC6 — returns empty preview when no activity types configured</li>
 *   <li>AC6 — returns empty preview when no phase / no slotted matches</li>
 *   <li>AC2 — returns structured preview when matches + activity types are present</li>
 *   <li>AC2 — unassigned teams listed in unassigned section</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S06.story.md">Story E08S06</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                ActivityAssignmentPreviewControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e08s06prevdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class ActivityAssignmentPreviewControllerIT {

    static final String TEST_PASSWORD = "E08S06PrevTest01";

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

    @Autowired
    private PhaseRepository phaseRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamAvatarRepository teamAvatarRepository;

    private String baseUrl;
    private TestRestTemplate authed;
    private UUID tournamentId;
    private UUID defaultTenantId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD);
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        tenantContext.set(defaultTenantId);

        tournamentId = UUID.randomUUID();
        Tournament t = new Tournament(
                tournamentId, defaultTenantId,
                "Preview Test Tournament " + tournamentId,
                MatchFormat.BEST_OF_3.name(), "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now());
        tournamentRepository.save(t);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    // =========================================================================
    // AC6 — empty preview when no activity types and no phase
    // =========================================================================

    @Test
    void returnsEmptyPreviewWhenNoActivityTypesAndNoPhase() throws Exception {
        ResponseEntity<ActivityAssignmentPreviewResponse> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-assignments"),
                ActivityAssignmentPreviewResponse.class);

        assertThat(response.getStatusCode())
                .as("AC6 — preview endpoint must return 200 even with no data")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().assignments())
                .as("AC6 — assignments must be empty when no activity types / no phase")
                .isEmpty();
        assertThat(response.getBody().unassigned())
                .as("AC6 — unassigned must be empty")
                .isEmpty();
    }

    // =========================================================================
    // AC6 — empty preview when phase exists but no matches slotted
    // =========================================================================

    @Test
    void returnsEmptyPreviewWhenPhaseExistsButNoMatchesSlotted() throws Exception {
        // Create a phase with no matches
        UUID phaseId = UUID.randomUUID();
        Phase phase = new Phase(phaseId, defaultTenantId, tournamentId, 1,
                "Phase 1", Phase.PhaseStatus.PENDING.name(), 0, LocalDateTime.now());
        phaseRepository.save(phase);

        // Create an activity type
        authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                new ActivityTypeCreateRequest("Mannschaftsfoto", AssignmentRule.FIRST_FREE_ROUND.name(), null, 1),
                ActivityTypeResponse.class);

        ResponseEntity<ActivityAssignmentPreviewResponse> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-assignments"),
                ActivityAssignmentPreviewResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().phaseId()).isEqualTo(phaseId);
        assertThat(response.getBody().assignments())
                .as("AC6 — no slotted matches means empty assignments")
                .isEmpty();
    }

    // =========================================================================
    // AC2 — structured preview with matches + activity type
    // =========================================================================

    @Test
    void returnsStructuredPreviewWithMatchesAndActivityType() throws Exception {
        // Set up: 4 teams, 1 phase, 2 laps (lap 1: T1 vs T2, T3 refs; lap 2: T3 vs T4, T1 refs)
        UUID[] teamIds = createTeams(4);
        UUID phaseId = createPhaseWithMatches(teamIds);

        // Create activity type (unlimited capacity)
        authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-types"),
                new ActivityTypeCreateRequest("Mannschaftsfoto", AssignmentRule.FIRST_FREE_ROUND.name(), null, 1),
                ActivityTypeResponse.class);

        ResponseEntity<ActivityAssignmentPreviewResponse> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/activity-assignments"),
                ActivityAssignmentPreviewResponse.class);

        assertThat(response.getStatusCode())
                .as("AC2 — preview with matches must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().phaseId()).isEqualTo(phaseId);
        assertThat(response.getBody().assignments())
                .as("AC2 — must have one entry per activity type")
                .hasSize(1);
        assertThat(response.getBody().assignments().get(0).activityTypeName())
                .isEqualTo("Mannschaftsfoto");
        // With the test schedule:
        //   Lap 1: Team[0] plays, Team[1] plays, Team[2] refs → Team[3] free → 1 assigned
        //   Lap 2: Team[2] plays, Team[3] plays, Team[0] refs → Team[1] free → 1 assigned
        //   Team[0] and Team[2] are busy in all their laps → 2 unassigned
        // 2 teams assigned, 2 teams unassigned (unlimited capacity does not help if team is busy)
        long totalAssigned = response.getBody().assignments().stream()
                .flatMap(a -> a.entries().stream())
                .mapToLong(e -> e.teams().size())
                .sum();
        assertThat(totalAssigned)
                .as("AC2 — 2 teams can be assigned (Teams 1 and 3 have one free lap each)")
                .isEqualTo(2);
        // 2 teams unassigned
        assertThat(response.getBody().unassigned().get(0).teams())
                .as("AC2 — 2 teams cannot be assigned (busy in every lap)")
                .hasSize(2);
    }

    // =========================================================================
    // AC2 — tournament not found returns 404
    // =========================================================================

    @Test
    void unknownTournamentReturns404() throws Exception {
        ResponseEntity<String> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + UUID.randomUUID() + "/activity-assignments"),
                String.class);

        assertThat(response.getStatusCode())
                .as("AC2 — unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // Helper: create teams and return their IDs
    // =========================================================================

    private UUID[] createTeams(int count) {
        UUID[] ids = new UUID[count];
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids[i] = id;
            // Team(id, tenantId, tournamentId, teamNumber, description, participate, refereeAssignment, withoutAssessment, createdAt)
            Team team = new Team(id, defaultTenantId, tournamentId, i + 1,
                    "Team " + (i + 1), true, false, false, LocalDateTime.now());
            teamRepository.save(team);
        }
        return ids;
    }

    /**
     * Creates a phase with 2 slotted matches:
     * <ul>
     *   <li>Lap 1: Team 0 vs Team 1; referee Team 2</li>
     *   <li>Lap 2: Team 2 vs Team 3; referee Team 0</li>
     * </ul>
     * Teams 1 and 3 are free in laps 2 and 1 respectively (earliest free round for each).
     */
    private UUID createPhaseWithMatches(UUID[] teamIds) {
        UUID phaseId = UUID.randomUUID();
        // Phase(id, tenantId, tournamentId, sequenceNumber, description, status,
        //       currentLapNumber, createdAt)
        Phase phase = new Phase(phaseId, defaultTenantId, tournamentId, 1,
                "Phase 1", Phase.PhaseStatus.ACTIVE.name(), 0, LocalDateTime.now());
        phaseRepository.save(phase);

        // Create team avatars (groupNumber=1, positions 1-4)
        // TeamAvatar(id, tenantId, tournamentId, phaseId, groupNumber, groupPosition, teamId, description, createdAt)
        UUID[] avatarIds = new UUID[teamIds.length];
        for (int i = 0; i < teamIds.length; i++) {
            UUID avId = UUID.randomUUID();
            avatarIds[i] = avId;
            TeamAvatar av = new TeamAvatar(avId, defaultTenantId, tournamentId, phaseId,
                    1, i + 1, teamIds[i], null, LocalDateTime.now());
            teamAvatarRepository.save(av);
        }

        // Match(id, tenantId, tournamentId, phaseId, memberAvatar1Id, memberAvatar2Id,
        //       state(int), setLimit, lapNumber, fieldNumber, refereeTeamId, refDesc, refPref, createdAt)
        // state=0 = OPEN (legacy int code), setLimit=3 (BEST_OF_3), lapNumber, fieldNumber, refereeTeamId
        // Lap 1: avatar[0] vs avatar[1], referee = team[2]
        UUID m1 = UUID.randomUUID();
        Match match1 = new Match(m1, defaultTenantId, tournamentId, phaseId,
                avatarIds[0], avatarIds[1],
                0, 3,
                1, 1,
                teamIds[2], null, null, LocalDateTime.now());
        matchRepository.save(match1);

        // Lap 2: avatar[2] vs avatar[3], referee = team[0]
        UUID m2 = UUID.randomUUID();
        Match match2 = new Match(m2, defaultTenantId, tournamentId, phaseId,
                avatarIds[2], avatarIds[3],
                0, 3,
                2, 1,
                teamIds[0], null, null, LocalDateTime.now());
        matchRepository.save(match2);

        return phaseId;
    }

    // =========================================================================
    // Test-local AdminCredentials
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
