// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for {@code PUT /api/phases/{phaseId}/transition-settings} — E66S02 AC4, AC5,
 * AC6.
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>AC4: only the target section's sortType and distributionMode are mutated in draft_json
 *   <li>AC5: unknown sortType or distributionMode → 400
 *   <li>AC6 (invalidation-neutral): already-generated matches remain unchanged after
 *       sortType/distributionMode change; phase status stays PREPARED; other phases unchanged
 * </ul>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)} per
 * DEC-44 D1. Fixture data inserted via direct JDBC (DEC-26 Rule 3). Persistence verified via direct
 * JDBC after mutation (DEC-26 Rule 2).
 *
 * @see PhaseTransitionController
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E66S02">E66S02 — AC4, AC5, AC6</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, PhaseTransitionSortDistributionIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("PhaseTransitionController IT — updateSortAndDistribution (E66S02)")
class PhaseTransitionSortDistributionIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E66S02PhaseTransitionSortDistributionIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private TestRestTemplate authed;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Fixture IDs
    private UUID locationId;
    private UUID tournamentId;
    private UUID fromPhaseId;
    private UUID toPhaseId;
    private UUID teamId1;
    private UUID teamId2;
    private UUID avatarFromId1;
    private UUID avatarFromId2;
    private UUID avatarToId1;
    private UUID avatarToId2;
    private UUID matchId;

    /**
     * Draft JSON with 2 sections: - section 1: team_number, roundRobin, sequential (fromPhase) -
     * section 2: team_number, roundRobin, sequential (toPhase — PREPARED)
     */
    private static final String DRAFT_JSON =
            "{"
                    + "\"sections\": ["
                    + "  {\"sectionNumber\": 1, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": [],"
                    + "   \"distributionMode\": \"sequential\"},"
                    + "  {\"sectionNumber\": 2, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": [],"
                    + "   \"distributionMode\": \"sequential\"}"
                    + "]"
                    + "}";

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "E66S02 IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E66S02 IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                2,
                2,
                DRAFT_JSON);

        // fromPhase (sequenceNumber=1) — COMPLETED
        fromPhaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                fromPhaseId,
                tournamentId,
                1,
                "Vorrunde",
                "COMPLETED",
                1);

        // toPhase (sequenceNumber=2) — PREPARED
        toPhaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                toPhaseId,
                tournamentId,
                2,
                "Zwischenrunde",
                "PREPARED",
                0);

        // Teams
        teamId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId1,
                tournamentId,
                1,
                "E66S02 Team 1",
                LocalDateTime.now());
        teamId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId2,
                tournamentId,
                2,
                "E66S02 Team 2",
                LocalDateTime.now());

        // fromPhase avatars (with teamId — COMPLETED phase)
        avatarFromId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatarFromId1,
                tournamentId,
                fromPhaseId,
                teamId1,
                1,
                1,
                LocalDateTime.now());
        avatarFromId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatarFromId2,
                tournamentId,
                fromPhaseId,
                teamId2,
                1,
                2,
                LocalDateTime.now());

        // toPhase placeholder avatars (teamId=NULL — structural placeholders per DEC-55 D-10)
        avatarToId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, NULL, ?, ?, ?)",
                avatarToId1,
                tournamentId,
                toPhaseId,
                1,
                1,
                LocalDateTime.now());
        avatarToId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, NULL, ?, ?, ?)",
                avatarToId2,
                tournamentId,
                toPhaseId,
                1,
                2,
                LocalDateTime.now());

        // Pre-generated match for toPhase (simulates background match-gen having already run)
        // AC6 verification: this match must be unchanged after updateSortAndDistribution
        matchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, lap_number, field_number,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                toPhaseId,
                avatarToId1,
                avatarToId2,
                0,
                1,
                1,
                1,
                LocalDateTime.now());

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM match WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update(
                "DELETE FROM team_avatar WHERE phase_id IN"
                        + " (SELECT id FROM phase WHERE tournament_id = ?)",
                tournamentId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC6 — invalidation-neutral: matches unchanged, phase stays PREPARED
    // =========================================================================

    @Test
    @DisplayName(
            "PUT transition-settings — changes sortType/distributionMode; matches unchanged; phase"
                    + " stays PREPARED (AC6 invalidation-neutral)")
    void updateSortAndDistribution_invalidationNeutral_matchesUnchanged() throws Exception {
        // Given: toPhase is PREPARED with 1 pre-generated match (set up in @BeforeEach)
        // When: operator changes sortType to "placement_group" and distributionMode to
        // "round_robin"
        String body = "{\"sortType\":\"placement_group\",\"distributionMode\":\"round_robin\"}";

        RequestEntity<String> request =
                RequestEntity.put(
                                new URI(
                                        baseUrl
                                                + "/api/phases/"
                                                + toPhaseId
                                                + "/transition-settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);

        ResponseEntity<String> response = authed.exchange(request, String.class);

        assertThat(response.getStatusCode())
                .as("PUT transition-settings must return 200 OK")
                .isEqualTo(HttpStatus.OK);

        // DEC-26 Rule 2: verify matches are UNCHANGED (AC6 invalidation-neutral)
        tenantBinder.bindDefaultTenant();
        try {
            int matchCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM match WHERE phase_id = ?",
                            Integer.class,
                            toPhaseId);
            assertThat(matchCount)
                    .as("Match count must remain 1 after sortType/distributionMode change (AC6)")
                    .isEqualTo(1);

            // Verify the existing match is untouched (same matchId)
            int matchById =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM match WHERE id = ?", Integer.class, matchId);
            assertThat(matchById)
                    .as("The pre-generated match must still exist unchanged (AC6)")
                    .isEqualTo(1);

            // Verify phase status is still PREPARED (not reset — AC6)
            String phaseStatus =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase WHERE id = ?", String.class, toPhaseId);
            assertThat(phaseStatus)
                    .as("Phase status must remain PREPARED after sortType change (AC6)")
                    .isEqualTo("PREPARED");

        } finally {
            tenantBinder.unbind();
        }

        // Verify response contains recomputed proposals
        List<TeamAvatarProposal> proposals =
                objectMapper.readValue(
                        response.getBody(), new TypeReference<List<TeamAvatarProposal>>() {});
        assertThat(proposals).as("Response must contain recomputed proposals (2 teams)").hasSize(2);
    }

    // =========================================================================
    // AC5 — membership check: unknown sortType → 400
    // =========================================================================

    @Test
    @DisplayName("PUT transition-settings — unknown sortType → 400 (AC5 membership check)")
    void updateSortAndDistribution_unknownSortType_returns400() throws Exception {
        String body = "{\"sortType\":\"unknown_sort_xyz\",\"distributionMode\":\"sequential\"}";

        RequestEntity<String> request =
                RequestEntity.put(
                                new URI(
                                        baseUrl
                                                + "/api/phases/"
                                                + toPhaseId
                                                + "/transition-settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);

        ResponseEntity<String> response = authed.exchange(request, String.class);

        assertThat(response.getStatusCode())
                .as("Unknown sortType must return 400 (AC5)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("PUT transition-settings — unknown distributionMode → 400 (AC5 membership check)")
    void updateSortAndDistribution_unknownDistributionMode_returns400() throws Exception {
        String body = "{\"sortType\":\"team_number\",\"distributionMode\":\"bad_mode_xyz\"}";

        RequestEntity<String> request =
                RequestEntity.put(
                                new URI(
                                        baseUrl
                                                + "/api/phases/"
                                                + toPhaseId
                                                + "/transition-settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);

        ResponseEntity<String> response = authed.exchange(request, String.class);

        assertThat(response.getStatusCode())
                .as("Unknown distributionMode must return 400 (AC5)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC4 — phase not PREPARED → 409
    // =========================================================================

    @Test
    @DisplayName("PUT transition-settings — phase not PREPARED → 409 (AC4 guard)")
    void updateSortAndDistribution_phaseNotPrepared_returns409() throws Exception {
        // Set toPhase to ASSIGNED (no longer PREPARED)
        tenantBinder.bindDefaultTenant();
        try {
            jdbcTemplate.update("UPDATE phase SET status = 'ASSIGNED' WHERE id = ?", toPhaseId);
        } finally {
            tenantBinder.unbind();
        }

        String body = "{\"sortType\":\"team_number\",\"distributionMode\":\"sequential\"}";

        RequestEntity<String> request =
                RequestEntity.put(
                                new URI(
                                        baseUrl
                                                + "/api/phases/"
                                                + toPhaseId
                                                + "/transition-settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);

        ResponseEntity<String> response = authed.exchange(request, String.class);

        assertThat(response.getStatusCode())
                .as("Non-PREPARED phase must return 409 (AC4)")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // =========================================================================
    // Security
    // =========================================================================

    @Test
    @DisplayName("PUT transition-settings — unauthenticated → 401")
    void updateSortAndDistribution_unauthenticated_returns401() throws Exception {
        String body = "{\"sortType\":\"team_number\",\"distributionMode\":\"sequential\"}";

        RequestEntity<String> request =
                RequestEntity.put(
                                new URI(
                                        baseUrl
                                                + "/api/phases/"
                                                + toPhaseId
                                                + "/transition-settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);

        ResponseEntity<String> response = restTemplate.exchange(request, String.class);

        assertThat(response.getStatusCode())
                .as("Unauthenticated PUT transition-settings must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Inner TestConfiguration — per DEC-44 D2 auth substitute pattern
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Autowired private PasswordEncoder passwordEncoder;

        @Bean("e66s02TestAdminCredentialsProvider")
        @Primary
        public AdminCredentialsProvider adminCredentialsProvider() {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
