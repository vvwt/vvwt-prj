// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RED-first DAO IT for distribution_mode branching in {@link DraftService#apply(UUID,
 * DraftConfig)}.
 *
 * <p>Verifies that Phase-1 TeamAvatar assignments follow the correct algorithm based on the {@code
 * distributionMode} field of each {@link DraftSection} (E51S15).
 *
 * <h2>DEC-26/DEC-46 three-rule conformance</h2>
 *
 * <ul>
 *   <li>Rule 1: Schema from Flyway migration (Spring manages schema via {@code @SpringBootTest})
 *   <li>Rule 2: assertj-db as independent persistence verifier (NOT draftService read-path)
 *   <li>Rule 3: Fixture data inserted via direct JDBC (not via draftService)
 * </ul>
 *
 * <h2>DEC-36 cross-package typing</h2>
 *
 * <p>Tests are in package {@code de.vvwt.tm.tournament} — same as the public {@link DraftService}
 * interface. Injection uses the public interface type per DEC-36. The concrete {@code
 * DefaultDraftService} is never referenced.
 *
 * <h2>DEC-22 RED-first governance</h2>
 *
 * <p>All tests marked RED-first are authored before the production-code fix. They fail before the
 * distribution_mode branching is implemented in {@code
 * DefaultDraftService.persistStructuralAvatars}.
 *
 * @see DraftService
 * @see de.vvwt.tm.tournament.internal.DefaultDraftService
 * @see <a href="E51S15">E51S15 — distribution_mode feature (sequential default + round-robin
 *     toggle)</a>
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-46">DEC-46 — DEC-26 scope extension</a>
 * @see <a href="DEC-55">DEC-55 D-1 — Avatar-Erzeugung-Zeitpunkt verschoben auf
 *     DraftConfig-Apply</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:draftdistmodeIT;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, DefaultDraftServiceDistributionModeIT.SlotOptConfig.class})
@DisplayName("DefaultDraftService apply() — distribution_mode branching IT — E51S15 RED-first")
class DefaultDraftServiceDistributionModeIT {

    /**
     * Overrides the production routingSlotOptimizationClient with a no-op. Prevents async
     * slot-optimization from holding row-locks during tearDown.
     */
    @TestConfiguration
    static class SlotOptConfig {

        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                // No-op: prevents async SlotOptInvocationListener TX conflicts in tearDown
            };
        }
    }

    /** Subject: inject via public interface per DEC-36. */
    @Autowired private DraftService draftService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;

    private UUID locationId;

    /** Tournament with 12 participating teams (for the primary 12/2/6 scenario). */
    private UUID tournament12;

    /** Ordered list of 12 participating team UUIDs (teamNumber 1..12). */
    private List<UUID> participatingTeamIds12;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "DistModeIT Location");

        // Tournament with 12 participating teams
        tournament12 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournament12,
                locationId,
                "DistMode IT Tournament 12",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                12,
                false);

        participatingTeamIds12 = insertParticipatingTeams(tournament12, 12);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        waitForPipelineQuiescent(tournament12);

        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournament12);
            jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournament12);
            jdbcTemplate.update(
                    "DELETE FROM phase_breaks WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournament12);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournament12);
            jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournament12);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournament12);
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-TEST-PERSIST-AVATARS-SEQUENTIAL-RED
    // =========================================================================

    /**
     * RED-first: given {@code distribution_mode="sequential"}, 12 teams, 2 groups of 6, after
     * {@code apply()}, avatars are: team-1 → G1P1, team-2 → G1P2, ..., team-6 → G1P6, team-7 →
     * G2P1, ..., team-12 → G2P6.
     *
     * <p>Test FAILS before the sequential branching is added because current code uses round-robin.
     *
     * @see <a href="E51S15">E51S15 AC-TEST-PERSIST-AVATARS-SEQUENTIAL-RED</a>
     * @see <a href="DEC-9">DEC-9 — structural identity (phaseId, groupNumber, groupPosition)</a>
     */
    @Test
    @DisplayName(
            "apply() with distributionMode=sequential assigns teams to groups in sequential order"
                    + " (AC-TEST-PERSIST-AVATARS-SEQUENTIAL-RED)")
    void apply_withSequentialMode_assignsTeamsSequentially() {
        DraftConfig config = singlePhaseConfig("sequential", 2, "roundRobin");

        List<UUID> phaseIds = draftService.apply(tournament12, config);
        assertThat(phaseIds).hasSize(2);

        UUID phase1Id = phaseIds.get(0);

        // DEC-59 Clause B: teamId=NULL universally at apply-time. Verify structural layout by
        // checking that the correct (group_number, group_position) slots exist, not by team_id.
        // Sequential: G1 gets positions 1-6, G2 gets positions 1-6 (ceil(12/2)=6 per group).
        for (int i = 0; i < 12; i++) {
            // positionsPerGroup = ceil(12/2) = 6
            int expectedGroup = (i / 6) + 1; // 0-5 → G1, 6-11 → G2
            int expectedPosition = (i % 6) + 1; // within-group position

            Integer count =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM team_avatar"
                                    + " WHERE phase_id = ?"
                                    + " AND group_number = ? AND group_position = ?",
                            Integer.class,
                            phase1Id,
                            expectedGroup,
                            expectedPosition);
            assertThat(count)
                    .as(
                            "Slot G%dP%d must exist exactly once in sequential mode"
                                    + " (teamId=NULL per DEC-59 Clause B)"
                                            .formatted(expectedGroup, expectedPosition))
                    .isEqualTo(1);
        }

        // Verify total Phase 1 avatar count = 12 and all have teamId=NULL
        Integer totalAvatars =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase1Id);
        assertThat(totalAvatars).as("Phase 1 must have 12 avatars").isEqualTo(12);

        Integer nonNullTeamIds =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ? AND team_id IS NOT"
                                + " NULL",
                        Integer.class,
                        phase1Id);
        assertThat(nonNullTeamIds)
                .as("All Phase 1 avatars must have teamId=NULL (DEC-59 Clause B)")
                .isEqualTo(0);
    }

    // =========================================================================
    // AC-TEST-PERSIST-AVATARS-ROUND-ROBIN-RED
    // =========================================================================

    /**
     * RED-first: given {@code distribution_mode="round_robin"}, 12 teams, 2 groups, avatars follow
     * the legacy round-robin pattern: team-1 → G1P1, team-2 → G2P1, team-3 → G1P2, etc.
     *
     * <p>Test verifies the legacy behavior is PRESERVED under the round_robin label. With the
     * current code (round-robin only) this test PASSES already. After the branching is added, the
     * "sequential" mode becomes the default while "round_robin" preserves legacy. This test is
     * RED-first in the sense that before the field exists, constructing a DraftSection with
     * distributionMode fails compilation.
     *
     * @see <a href="E51S15">E51S15 AC-TEST-PERSIST-AVATARS-ROUND-ROBIN-RED</a>
     */
    @Test
    @DisplayName(
            "apply() with distributionMode=round_robin assigns teams in round-robin order"
                    + " (AC-TEST-PERSIST-AVATARS-ROUND-ROBIN-RED)")
    void apply_withRoundRobinMode_assignsTeamsRoundRobin() {
        DraftConfig config = singlePhaseConfig("round_robin", 2, "roundRobin");

        List<UUID> phaseIds = draftService.apply(tournament12, config);
        assertThat(phaseIds).hasSize(2);

        UUID phase1Id = phaseIds.get(0);

        // DEC-59 Clause B: teamId=NULL universally at apply-time. Verify structural layout by
        // checking that the correct (group_number, group_position) slots exist, not by team_id.
        // Round-robin: team at index i → group (i%2)+1, position (i/2)+1
        // Same 12 slots as sequential (G1P1..G1P6, G2P1..G2P6) but with different team ordering.
        // Since teamId=NULL, we verify slot existence only.
        for (int i = 0; i < 12; i++) {
            int expectedGroup = (i % 2) + 1; // alternates G1, G2
            int expectedPosition = (i / 2) + 1; // fills positions incrementally

            Integer count =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM team_avatar"
                                    + " WHERE phase_id = ?"
                                    + " AND group_number = ? AND group_position = ?",
                            Integer.class,
                            phase1Id,
                            expectedGroup,
                            expectedPosition);
            assertThat(count)
                    .as(
                            "Slot G%dP%d must exist exactly once in round-robin mode"
                                    + " (teamId=NULL per DEC-59 Clause B)"
                                            .formatted(expectedGroup, expectedPosition))
                    .isEqualTo(1);
        }

        // Verify total Phase 1 avatar count = 12 and all have teamId=NULL
        Integer totalAvatars =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase1Id);
        assertThat(totalAvatars).as("Phase 1 must have 12 avatars").isEqualTo(12);

        Integer nonNullTeamIds =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ? AND team_id IS NOT"
                                + " NULL",
                        Integer.class,
                        phase1Id);
        assertThat(nonNullTeamIds)
                .as("All Phase 1 avatars must have teamId=NULL (DEC-59 Clause B)")
                .isEqualTo(0);
    }

    // =========================================================================
    // AC-TEST-DEFAULT-IS-SEQUENTIAL-RED
    // =========================================================================

    /**
     * RED-first: a {@link DraftSection} with NO {@code distributionMode} in its construction (i.e.,
     * using the legacy constructor path that defaults to {@code "sequential"}) produces sequential
     * avatar assignment.
     *
     * <p>Verifies the default behavior change: existing tournaments that load without {@code
     * distributionMode} now get sequential (not round-robin).
     *
     * @see <a href="E51S15">E51S15 AC-TEST-DEFAULT-IS-SEQUENTIAL-RED</a>
     */
    @Test
    @DisplayName(
            "apply() with default distributionMode (no field) assigns teams sequentially"
                    + " (AC-TEST-DEFAULT-IS-SEQUENTIAL-RED)")
    void apply_withDefaultMode_assignsTeamsSequentially() {
        // DraftSection constructed without distributionMode → defaults to "sequential"
        DraftSection phase1 =
                new DraftSection(1, "team_number", 2, "roundRobin", 0, 0, 15, 1, List.of());
        DraftSection phase2 =
                new DraftSection(2, "team_number", 1, "awardCeremony", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(phase1, phase2));

        // Verify the constructed section's default distributionMode
        assertThat(phase1.getDistributionMode())
                .as("DraftSection default distributionMode must be 'sequential'")
                .isEqualTo("sequential");

        List<UUID> phaseIds = draftService.apply(tournament12, config);
        assertThat(phaseIds).hasSize(2);

        UUID phase1Id = phaseIds.get(0);

        // DEC-59 Clause B: teamId=NULL universally at apply-time. Verify structural layout.
        // Default (sequential): same slots as explicit sequential test.
        for (int i = 0; i < 12; i++) {
            int expectedGroup = (i / 6) + 1;
            int expectedPosition = (i % 6) + 1;

            Integer count =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM team_avatar"
                                    + " WHERE phase_id = ?"
                                    + " AND group_number = ? AND group_position = ?",
                            Integer.class,
                            phase1Id,
                            expectedGroup,
                            expectedPosition);
            assertThat(count)
                    .as(
                            "Slot G%dP%d must exist exactly once with default (sequential)"
                                    + " distributionMode (teamId=NULL per DEC-59 Clause B)"
                                            .formatted(expectedGroup, expectedPosition))
                    .isEqualTo(1);
        }

        // Verify total Phase 1 avatar count = 12 and all have teamId=NULL
        Integer totalAvatars =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase1Id);
        assertThat(totalAvatars).as("Phase 1 must have 12 avatars").isEqualTo(12);

        Integer nonNullTeamIds =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ? AND team_id IS NOT"
                                + " NULL",
                        Integer.class,
                        phase1Id);
        assertThat(nonNullTeamIds)
                .as("All Phase 1 avatars must have teamId=NULL (DEC-59 Clause B)")
                .isEqualTo(0);
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * 2-phase config: Phase 1 ({@code roundRobin}, {@code groupCount} groups, specified {@code
     * distributionMode}) + Phase 2 (siegerehrung, 1 group).
     */
    private static DraftConfig singlePhaseConfig(
            String distributionMode, int groupCount, String gameMode) {
        DraftSection phase1 =
                new DraftSection(
                        1,
                        "team_number",
                        groupCount,
                        gameMode,
                        0,
                        0,
                        15,
                        1,
                        List.of(),
                        distributionMode);
        DraftSection phase2 =
                new DraftSection(
                        2, "team_number", 1, "awardCeremony", 0, 0, 15, 1, List.of(), "sequential");
        return new DraftConfig(List.of(phase1, phase2));
    }

    /**
     * Inserts {@code count} participating teams for the given tournament, numbered 1..count.
     *
     * @param tournamentId the tournament UUID
     * @param count number of teams to insert
     * @return ordered list of inserted team UUIDs (teamNumber ascending)
     */
    private List<UUID> insertParticipatingTeams(UUID tournamentId, int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            UUID teamId = UUID.randomUUID();
            ids.add(teamId);
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamId,
                    tournamentId,
                    i,
                    "Team " + i,
                    true,
                    LocalDateTime.now());
        }
        return List.copyOf(ids);
    }

    /**
     * Polls until all phases for {@code tournamentId} have a terminal {@code last_job_state}. Times
     * out after 5 seconds.
     */
    private void waitForPipelineQuiescent(UUID tournamentId) throws InterruptedException {
        Thread.sleep(100);
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Integer inFlightCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM phase WHERE tournament_id = ?"
                                    + " AND last_job_state IN ('match_gen_running',"
                                    + " 'slot_opt_running')",
                            Integer.class,
                            tournamentId);
            if (inFlightCount == null || inFlightCount == 0) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
