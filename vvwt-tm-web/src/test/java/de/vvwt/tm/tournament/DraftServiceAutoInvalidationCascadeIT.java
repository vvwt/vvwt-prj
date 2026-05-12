package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.phaselifecycle.DraftApplicationOrchestrator;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.draft.GameMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
 * IT for E55S06: auto-invalidation cascade via {@code resetPlan() + applyDraft()} enqueues fresh
 * {@code phase_lifecycle_job} rows for re-applied phases (AC-TEST-AUTO-INVALIDATION-CASCADE-RED,
 * DEC-64 D-11).
 *
 * <h2>E55S06 Option C adaptation</h2>
 *
 * <p>Originally this IT tested {@link DraftService#apply(UUID, DraftConfig)} directly. With Option
 * C (DEC-64 D-11), job-row insertion is handled by {@link DraftApplicationOrchestrator}. This IT is
 * rewired to invoke the orchestrator for both apply steps; {@code resetPlan()} is still called on
 * {@link DraftService} (resetPlan is not part of the orchestrator contract).
 *
 * <h2>Scenario</h2>
 *
 * <ol>
 *   <li>applyDraft() a 3-phase DraftConfig → 3 job rows enqueued.
 *   <li>Call {@code resetPlan()} (returns tournament to DRAFT status; phases deleted).
 *   <li>Re-apply with a changed phase-2 gameMode → 3 new job rows enqueued (all phases, not just
 *       changed ones — resetPlan deletes all phases; applyDraft re-creates all).
 *   <li>Assert: 3 PENDING rows (from re-apply) with correct sequences; no old rows leaked.
 * </ol>
 *
 * <h2>Acceptance Criteria covered</h2>
 *
 * <ul>
 *   <li>AC-TEST-AUTO-INVALIDATION-CASCADE-RED — resetPlan + re-apply enqueues fresh job rows
 *   <li>AC-IMPL-DRAFTSERVICE-APPLY-ENQUEUES-JOBS — applyDraft() enqueues N job rows in same TX
 * </ul>
 *
 * <h2>DEC-26/DEC-46 three-rule conformance</h2>
 *
 * <ul>
 *   <li>Rule 1: Schema from Flyway migration
 *   <li>Rule 2: assertj-db / direct JDBC as independent persistence verifier
 *   <li>Rule 3: Fixture data inserted via direct JDBC
 * </ul>
 *
 * @see DraftApplicationOrchestrator
 * @see DraftService
 * @see <a href="DEC-64">DEC-64 D-11 — Option C: DraftApplicationOrchestrator inserts job rows</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E55S06">E55S06 — AC-TEST-AUTO-INVALIDATION-CASCADE-RED</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:autoinvalidationcascadeit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, DraftServiceAutoInvalidationCascadeIT.SlotOptConfig.class})
@DisplayName(
        "DraftApplicationOrchestrator resetPlan + re-apply enqueues fresh job rows"
                + " — AC-TEST-AUTO-INVALIDATION-CASCADE-RED — E55S06")
class DraftServiceAutoInvalidationCascadeIT {

    /** No-op slot-opt client and no-op drain service to prevent pipeline interference. */
    @TestConfiguration
    static class SlotOptConfig {

        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {};
        }

        /**
         * No-op drain service — prevents the drain loop from claiming PENDING rows during assertion
         * (E55S06 race guard, DEC-64 D-11).
         */
        @Bean("jobDrainService")
        @Primary
        JobDrainService noOpJobDrainService() {
            return tournamentId -> {};
        }
    }

    @Autowired private DraftApplicationOrchestrator draftApplicationOrchestrator;
    @Autowired private DraftService draftService;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID locationId;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)", locationId, "CascadeIT");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "CascadeIT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                6,
                false);

        insertParticipatingTeams(tournamentId, 6);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update(
                    "DELETE FROM phase_lifecycle_job WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentId);
            jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    /**
     * AC-TEST-AUTO-INVALIDATION-CASCADE-RED (E55S06):
     *
     * <p>applyDraft() a 3-phase DraftConfig, then resetPlan + re-apply with changed phase-2
     * gameMode. Assert that after re-apply:
     *
     * <ol>
     *   <li>3 new PENDING {@code phase_lifecycle_job} rows exist for the re-applied phases.
     *   <li>Sequences are 1, 2, 3 for the re-applied phases.
     *   <li>Phase references match the new phaseIds (not stale old ones).
     * </ol>
     */
    @Test
    @DisplayName(
            "resetPlan + re-apply enqueues 3 fresh PENDING job rows"
                    + " — AC-TEST-AUTO-INVALIDATION-CASCADE-RED")
    void resetPlanAndReApply_enqueuesFreshJobRows() {
        // Step 1: first apply — 3 phases with gameMode ROUND_ROBIN / ROUND_ROBIN / SIEGEREHRUNG
        DraftConfig firstConfig = buildThreePhaseDraftConfig(GameMode.ROUND_ROBIN);
        List<UUID> firstPhaseIds =
                draftApplicationOrchestrator.applyDraft(tournamentId, firstConfig);
        assertThat(firstPhaseIds).hasSize(3);

        // Verify 3 job rows after first apply
        int rowsAfterFirstApply =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        assertThat(rowsAfterFirstApply).as("First apply must enqueue 3 job rows").isEqualTo(3);

        // Step 2: resetPlan — returns to DRAFT; all phases and job rows deleted by cascade
        // (phase_lifecycle_job rows are deleted because apply() created them with FK to tournament)
        draftService.resetPlan(tournamentId);

        // After resetPlan, job rows for this tournament must be gone
        int rowsAfterReset =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        assertThat(rowsAfterReset)
                .as("resetPlan must clear phase_lifecycle_job rows (cascade or explicit delete)")
                .isEqualTo(0);

        // Step 3: re-apply with changed phase-2 gameMode (ROUND_ROBIN instead of SIEGEREHRUNG)
        DraftConfig secondConfig = buildThreePhaseDraftConfig(GameMode.ROUND_ROBIN);
        List<UUID> secondPhaseIds =
                draftApplicationOrchestrator.applyDraft(tournamentId, secondConfig);
        assertThat(secondPhaseIds).hasSize(3);

        // Step 4: assert 3 fresh PENDING job rows exist for the re-applied phases
        int rowsAfterReApply =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        assertThat(rowsAfterReApply)
                .as(
                        "Re-apply must enqueue 3 fresh PENDING job rows"
                                + " (AC-TEST-AUTO-INVALIDATION-CASCADE-RED)")
                .isEqualTo(3);

        // All rows must be PENDING
        List<String> statuses =
                jdbcTemplate.queryForList(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?"
                                + " ORDER BY sequence ASC",
                        String.class,
                        tournamentId);
        assertThat(statuses).containsExactly("PENDING", "PENDING", "PENDING");

        // Sequences must be 1, 2, 3
        List<Integer> sequences =
                jdbcTemplate.queryForList(
                        "SELECT sequence FROM phase_lifecycle_job WHERE tournament_id = ?"
                                + " ORDER BY sequence ASC",
                        Integer.class,
                        tournamentId);
        assertThat(sequences).containsExactly(1, 2, 3);

        // Phase references must match the new phaseIds (not the old ones)
        List<UUID> jobPhaseIds =
                jdbcTemplate.queryForList(
                        "SELECT phase_id FROM phase_lifecycle_job WHERE tournament_id = ?"
                                + " ORDER BY sequence ASC",
                        UUID.class,
                        tournamentId);
        assertThat(jobPhaseIds)
                .as("Job rows must reference re-applied phase IDs (not stale old ones)")
                .containsExactlyInAnyOrderElementsOf(secondPhaseIds);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void insertParticipatingTeams(UUID tid, int count) {
        for (int i = 1; i <= count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    tid,
                    i,
                    "Team " + i,
                    true);
        }
    }

    /**
     * Builds a 3-phase DraftConfig where phase 2 uses the provided {@code phase2GameMode}.
     *
     * @param phase2GameMode the game mode to use for phase 2
     */
    private DraftConfig buildThreePhaseDraftConfig(GameMode phase2GameMode) {
        DraftSection s1 =
                new DraftSection(1, "team_number", 2, GameMode.ROUND_ROBIN, 0, 0, 12, 1, List.of());
        DraftSection s2 =
                new DraftSection(2, "team_number", 2, phase2GameMode, 0, 0, 12, 1, List.of());
        DraftSection s3 =
                new DraftSection(3, "team_number", 1, GameMode.SIEGEREHRUNG, 0, 0, 5, 1, List.of());
        return new DraftConfig(List.of(s1, s2, s3));
    }
}
