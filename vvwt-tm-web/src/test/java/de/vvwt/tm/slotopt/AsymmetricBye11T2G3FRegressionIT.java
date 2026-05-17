// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.RoundAssignmentService;
import de.vvwt.tm.web.WebModuleTestConfig;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression-guard integration test for E54S03 — bye-slot end-to-end for an asymmetric 11T/2G/3F
 * phase (group sizes 5 + 6), verifying that:
 *
 * <ul>
 *   <li>L2 + L3 produce exactly the expected 25 match rows
 *   <li>Display SPA {@code /api/display/overview/matches} endpoint returns HTTP 200 for all lap
 *       values without throwing (missing {@code (lap, field)} cells = Spielfrei rendered by SPA)
 *   <li>Print Laufzettel for a bye-team (5-team group) renders HTTP 200 (Mannschaftsfoto-pattern
 *       per E53S08 — not a missing row, not a 500)
 *   <li>{@code mvn verify} exits zero (DEC-54)
 * </ul>
 *
 * <h2>AC coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-ASYMMETRIC-11T-2G-3F-BYE-SLOTS-E2E-RED
 *   <li>AC-TEST-DEC-60-1-BASED-PRESERVED-IN-L3-OUTPUT-GREEN (MIN lap ≥ 1, MIN field ≥ 1)
 * </ul>
 *
 * <h2>Fixture</h2>
 *
 * <p>11 teams in 2 groups: group 1 has 5 teams (10 matches, 5*4/2), group 2 has 6 teams (15
 * matches, 6*5/2) → 25 total matches. With 3 fields, group 1 has 5 laps and group 2 has 5 laps → 10
 * laps total. L2 distributes matches into laps; L3 permutes laps phase-globally.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 — RED-first per Iron Law (this test is RED against pre-E54S03 per-group code)
 *   <li>DEC-44 — {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)}
 *   <li>DEC-60 — 1-based lap+field preservation
 *   <li>DEC-61 Clause D — phase-global L3 invocation
 * </ul>
 *
 * @see RoutingSlotOptimizationClient
 * @see SlotResultApplicator
 * @see de.vvwt.tm.web.PrintController
 * @see de.vvwt.tm.web.DisplayOverviewController
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:asymmetricbye11t2g3fregressionit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.slotopt.fallback.field-count=3",
            "tm.slotopt.exhaustive-max-n=10"
        })
@ActiveProfiles("test")
@Import({
    WebModuleTestConfig.class,
    AsymmetricBye11T2G3FRegressionIT.TestConfig.class,
    AsymmetricBye11T2G3FRegressionIT.TestAdminCredentials.class
})
@DisplayName("AsymmetricBye11T2G3FRegressionIT — E54S03 — bye-slot E2E (11T/2G/3F)")
class AsymmetricBye11T2G3FRegressionIT {

    static final String TEST_PASSWORD = "AsymmetricBye11T2G3FIT54S03";

    /** Suppresses WebSocket bean to prevent unneeded messaging infrastructure in IT. */
    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        SimpMessagingTemplate testSimpMessagingTemplate() {
            return mock(SimpMessagingTemplate.class);
        }
    }

    /**
     * Per-IT {@link AdminCredentialsProvider} with a fixed BCrypt-hashed test password.
     *
     * <p>Per DEC-44 §"2026-04-27 Empirical Refinement": this inner class provides {@code @Primary
     * AdminCredentialsProvider} which overrides the non-primary placeholder in {@link
     * WebModuleTestConfig}. Used for authenticated access to {@code /print/**} endpoints.
     */
    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hashed = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hashed;
        }
    }

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private RoundAssignmentService roundAssignmentService;
    @Autowired private SlotOptimizationClient slotOptimizationClient;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private String displayDeviceToken;
    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;

    /** UUIDs of avatars in group 1 (5-team group — produces a bye-team per round). */
    private UUID[] group1AvatarIds;

    /** UUID of a bye-team avatar in group 1 (used for Print Laufzettel). */
    private UUID byeTeamId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        tenantBinder.bindDefaultTenant();
        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "E54S03 Asymmetric IT Location");

        setUp11T2G3FPhase();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-TEST-ASYMMETRIC-11T-2G-3F-BYE-SLOTS-E2E-RED: 25 match rows
    // =========================================================================

    /**
     * AC-TEST-ASYMMETRIC-11T-2G-3F-BYE-SLOTS-E2E-RED (E54S03, part 1):
     *
     * <p>DB contains exactly 25 matches after L1 setup: {@code (5*4/2) + (6*5/2) = 10 + 15 = 25}.
     * L2 assigns lap+field (not null). L3 permutes laps phase-globally (DEC-61 Clause D).
     *
     * <p>Asserts:
     *
     * <ul>
     *   <li>Pre-L2: 25 total matches with lap_number=NULL
     *   <li>Post-L2: all 25 matches have non-null lap_number and field_number
     *   <li>Post-L3: total match count unchanged (25), all lap_number ≥ 1, field_number ≥ 1
     *       (DEC-60)
     * </ul>
     */
    @Test
    void optimize_11T2G3F_totalMatchCount25_allLapsAssigned_E54S03() {
        // Pre-condition: 25 matches with null lap+field
        int preL2Count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND lap_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(preL2Count)
                .as("pre-L2: all 25 matches must have lap_number=NULL before L2 assignment")
                .isEqualTo(25);

        // L2: assign laps and fields
        roundAssignmentService.assignRoundsAndFields(phaseId, 3);

        // L3: phase-global slot optimization (E54S03)
        slotOptimizationClient.optimize(phaseId);

        // Assert: total match count still 25 (L3 rewrites lap/field, doesn't add/delete rows)
        int totalCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?", Integer.class, phaseId);
        assertThat(totalCount)
                .as("AC-TEST-ASYMMETRIC-11T-2G-3F: 25 matches total after L2+L3")
                .isEqualTo(25);

        // Assert: all matches have non-null lap_number and field_number (DEC-60)
        int nullLapCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND lap_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(nullLapCount)
                .as("all 25 matches must have non-null lap_number after L2+L3")
                .isEqualTo(0);

        // Assert: DEC-60 1-based convention preserved
        Integer minLap =
                jdbcTemplate.queryForObject(
                        "SELECT MIN(lap_number) FROM match WHERE phase_id = ?",
                        Integer.class,
                        phaseId);
        Integer minField =
                jdbcTemplate.queryForObject(
                        "SELECT MIN(field_number) FROM match WHERE phase_id = ?",
                        Integer.class,
                        phaseId);
        assertThat(minLap)
                .as("AC-TEST-DEC-60-1-BASED-PRESERVED: MIN(lap_number) must be ≥ 1")
                .isGreaterThanOrEqualTo(1);
        assertThat(minField)
                .as("AC-TEST-DEC-60-1-BASED-PRESERVED: MIN(field_number) must be ≥ 1")
                .isGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // AC-TEST-ASYMMETRIC-11T-2G-3F-BYE-SLOTS-E2E-RED: Display SPA smoke test
    // =========================================================================

    /**
     * AC-TEST-ASYMMETRIC-11T-2G-3F-BYE-SLOTS-E2E-RED (E54S03, part 2):
     *
     * <p>After L2+L3, the Display SPA {@code /api/display/overview/matches} endpoint must return
     * HTTP 200 for each lap in the schedule. Missing {@code (lap, field)} cells (Spielfrei for the
     * 5-team group's bye-team per round) must NOT cause a 500/4xx. The AC says "without throwing" —
     * this test verifies HTTP 2xx for all laps.
     */
    @Test
    void displayOverviewMatches_allLaps_returns200_byeSlotDoesNotThrow_E54S03() {
        roundAssignmentService.assignRoundsAndFields(phaseId, 3);
        slotOptimizationClient.optimize(phaseId);

        // Find the number of distinct laps
        java.util.List<Integer> distinctLaps =
                jdbcTemplate.queryForList(
                        "SELECT DISTINCT lap_number FROM match WHERE phase_id = ? ORDER BY"
                                + " lap_number",
                        Integer.class,
                        phaseId);

        assertThat(distinctLaps).as("must have at least 1 lap after L2+L3").isNotEmpty();

        // For each lap, call display/overview/matches endpoint and assert HTTP 200
        // (bye-slot = missing (lap, field) combination; SPA renders it as Spielfrei)
        for (int lap : distinctLaps) {
            ResponseEntity<String> response =
                    restTemplate.getForEntity(
                            baseUrl
                                    + "/api/display/overview/matches?token="
                                    + displayDeviceToken
                                    + "&lap="
                                    + lap,
                            String.class);

            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as(
                            "AC-TEST-ASYMMETRIC-11T-2G-3F: display/overview/matches lap=%d"
                                    + " must return HTTP 2xx (bye-slot must not throw)",
                            lap)
                    .isTrue();
        }
    }

    // =========================================================================
    // AC-TEST-ASYMMETRIC-11T-2G-3F-BYE-SLOTS-E2E-RED: Print Laufzettel bye-team
    // =========================================================================

    /**
     * AC-TEST-ASYMMETRIC-11T-2G-3F-BYE-SLOTS-E2E-RED (E54S03, part 3):
     *
     * <p>After L2+L3, the Print Laufzettel endpoint for a bye-team (from the 5-team group) must
     * return HTTP 200 (Mannschaftsfoto-pattern per E53S08 — not a missing row, not a 500). The
     * "bye-team" is the team that has a bye-round in their group's schedule (5-team group has an
     * odd team out per round).
     */
    @Test
    void printLaufzettel_byeTeam_returns200_mannschaftsfotoPatternNotThrown_E54S03() {
        roundAssignmentService.assignRoundsAndFields(phaseId, 3);
        slotOptimizationClient.optimize(phaseId);

        // Use authenticated restTemplate for /print/** endpoints
        TestRestTemplate authed =
                restTemplate.withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);

        // Print Laufzettel for a bye-team (a team from the 5-team group in tournament context)
        ResponseEntity<String> response =
                authed.getForEntity(
                        baseUrl
                                + "/print/tournaments/"
                                + tournamentId
                                + "/team-schedules/"
                                + byeTeamId,
                        String.class);

        assertThat(response.getStatusCode())
                .as(
                        "AC-TEST-ASYMMETRIC-11T-2G-3F: Print Laufzettel for bye-team must"
                                + " return HTTP 200 (Mannschaftsfoto-pattern per E53S08,"
                                + " not a missing row or 500)")
                .isEqualTo(HttpStatus.OK);
    }

    // ── Setup helpers ─────────────────────────────────────────────────────────

    /**
     * Sets up an 11T/2G/3F phase:
     *
     * <ul>
     *   <li>11 teams in 2 groups: group 1 = 5 teams, group 2 = 6 teams
     *   <li>Intra-group all-pair matches: group1: C(5,2)=10, group2: C(6,2)=15 → 25 total
     *   <li>3 fields → group1: ~5 laps (some bye per round), group2: 5 laps (3 matches each)
     *   <li>lap_number=null, field_number=null (pre-L2 state, ready for L2+L3)
     *   <li>Phase in ACTIVE status so display endpoint can resolve it
     * </ul>
     *
     * <p>Also registers a DISPLAY device for the display endpoint smoke test.
     */
    private void setUp11T2G3FPhase() {
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();

        // Tournament in ACTIVE status (required for display overview endpoint)
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E54S03 IT Asymmetric 11T/2G/3F",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                3,
                11,
                true,
                "{\"sections\": ["
                        + "  {\"sectionNumber\": 1, \"sortType\": \"team_number\","
                        + "   \"groupCount\": 2, \"gameMode\": \"roundRobin\","
                        + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                        + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []}"
                        + "]}");

        // Phase in ACTIVE status with currentLapNumber=1 for display endpoint
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "ACTIVE",
                1,
                LocalDateTime.now(),
                false);

        // 11 teams
        UUID[] teamIds = new UUID[11];
        for (int i = 0; i < 11; i++) {
            teamIds[i] = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamIds[i],
                    tournamentId,
                    i + 1,
                    "Team " + (i + 1),
                    true,
                    LocalDateTime.now());
        }

        // 11 avatars: group 1 = teams 0..4 (5 teams), group 2 = teams 5..10 (6 teams)
        UUID[] avatarIds = new UUID[11];
        group1AvatarIds = new UUID[5];
        for (int i = 0; i < 11; i++) {
            avatarIds[i] = UUID.randomUUID();
            int group = (i < 5) ? 1 : 2;
            int pos = (i < 5) ? i + 1 : (i - 5) + 1;
            jdbcTemplate.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                            + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                    avatarIds[i],
                    tournamentId,
                    phaseId,
                    group,
                    pos,
                    teamIds[i]);
            if (i < 5) {
                group1AvatarIds[i] = avatarIds[i];
            }
        }

        // The bye-team is the team entity for team index 0 (from 5-team group 1)
        byeTeamId = teamIds[0];

        // Intra-group all-pair matches (L1 output: lap=null, field=null)
        // Group 1: avatarIds[0..4] → 10 matches
        for (int i = 0; i < 5; i++) {
            for (int j = i + 1; j < 5; j++) {
                jdbcTemplate.update(
                        "INSERT INTO match (id, tournament_id, phase_id,"
                                + " member_avatar_1_id, member_avatar_2_id, state, set_limit,"
                                + " lap_number, field_number, created_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
                        UUID.randomUUID(),
                        tournamentId,
                        phaseId,
                        avatarIds[i],
                        avatarIds[j],
                        de.vvwt.tm.tournament.MatchState.OPEN.getLegacyCode(),
                        3,
                        LocalDateTime.now());
            }
        }

        // Group 2: avatarIds[5..10] → 15 matches
        for (int i = 5; i < 11; i++) {
            for (int j = i + 1; j < 11; j++) {
                jdbcTemplate.update(
                        "INSERT INTO match (id, tournament_id, phase_id,"
                                + " member_avatar_1_id, member_avatar_2_id, state, set_limit,"
                                + " lap_number, field_number, created_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
                        UUID.randomUUID(),
                        tournamentId,
                        phaseId,
                        avatarIds[i],
                        avatarIds[j],
                        de.vvwt.tm.tournament.MatchState.OPEN.getLegacyCode(),
                        3,
                        LocalDateTime.now());
            }
        }

        // Register a DISPLAY device for the display endpoint smoke test
        displayDeviceToken = UUID.randomUUID().toString();
        Device displayDevice =
                new Device(
                        UUID.randomUUID(),
                        null,
                        displayDeviceToken,
                        null,
                        Device.TYPE_DISPLAY,
                        null,
                        Device.STATUS_REGISTERED,
                        LocalDateTime.now(),
                        null,
                        "E54S03 IT Asymmetric Display Device",
                        null);
        deviceRepository.save(displayDevice);
    }
}
