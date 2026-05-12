package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * RED-first IT for E55S06 AC-TEST-ORCHESTRATOR-APPLY-ENQUEUES-N-JOBS-RED: {@link
 * DraftApplicationOrchestrator#applyDraft(UUID, DraftConfig)} must enqueue {@code
 * phase_lifecycle_job} rows via {@link de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository}
 * (Option C, DEC-64 D-11).
 *
 * <h2>Acceptance Criteria covered</h2>
 *
 * <ul>
 *   <li>AC-TEST-ORCHESTRATOR-APPLY-ENQUEUES-N-JOBS-RED — orchestrator-level IT: 3 PENDING rows,
 *       correct sequences + game_mode values, no deleted events published
 *   <li>AC-IMPL-DRAFT-APPLICATION-ORCHESTRATOR-WORKFLOW — orchestrator executes in single TX:
 *       DraftService.apply() + enqueueJob() + drainNext()
 *   <li>AC-ERROR-HANDLING-APPLY-ROLLBACK — applyDraft() failure rolls back all rows
 *   <li>AC-ERROR-HANDLING-NO-DOUBLE-EVENT-PUBLISH — no MatchGenJobScheduledEvent (deleted)
 *       published
 * </ul>
 *
 * <h2>DEC-22 RED-first governance</h2>
 *
 * <p>Before E55S06 production changes: {@link DraftApplicationOrchestrator} does not exist yet
 * (injection fails → RED). After E55S06 changes: GREEN.
 *
 * <h2>DEC-26/DEC-46 three-rule conformance</h2>
 *
 * <ul>
 *   <li>Rule 1: Schema from Flyway migration (phase_lifecycle_job table from E55S02)
 *   <li>Rule 2: assertj-db / JDBC count as independent persistence verifier
 *   <li>Rule 3: Fixture data inserted via direct JDBC
 * </ul>
 *
 * <h2>DEC-44 IT framework</h2>
 *
 * <p>Uses {@code @SpringBootTest(NONE)} (no web layer) — direct orchestrator invocation, not via
 * HTTP.
 *
 * @see DraftApplicationOrchestrator
 * @see de.vvwt.tm.phaselifecycle.internal.DefaultDraftApplicationOrchestrator
 * @see <a href="DEC-64">DEC-64 D-11 — Option C orchestrator</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @since E55S06
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:draftorchestratorit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, DraftApplicationOrchestratorIT.SlotOptConfig.class})
@DisplayName(
        "DraftApplicationOrchestrator — applyDraft() enqueues phase_lifecycle_job rows — E55S06")
class DraftApplicationOrchestratorIT {

    /**
     * Override slot-opt client with no-op to prevent async pipeline interference. Override drain
     * service with no-op to prevent PENDING rows from being claimed during assertion.
     */
    @TestConfiguration
    static class SlotOptConfig {

        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {};
        }

        /**
         * No-op drain service — prevents the drain loop from claiming PENDING rows during the
         * enqueue-status assertion window (E55S06 race guard, DEC-64 D-11).
         */
        @Bean("jobDrainService")
        @Primary
        JobDrainService noOpJobDrainService() {
            return tournamentId -> {};
        }
    }

    @Autowired private DraftApplicationOrchestrator draftApplicationOrchestrator;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;
    private UUID locationId;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "OrchestratorIT");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "OrchestratorIT Tournament",
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
     * AC-TEST-ORCHESTRATOR-APPLY-ENQUEUES-N-JOBS-RED (E55S06):
     *
     * <p>applyDraft() a 3-phase DraftConfig; assert (a) 3 rows in {@code phase_lifecycle_job} with
     * {@code status='PENDING'}, (b) sequences 1, 2, 3, (c) correct game_mode values.
     */
    @Test
    @DisplayName(
            "applyDraft() 3-phase config enqueues 3 PENDING phase_lifecycle_job rows"
                    + " — AC-TEST-ORCHESTRATOR-APPLY-ENQUEUES-N-JOBS-RED")
    void applyDraft_threePhaseDraftConfig_enqueuesThreePendingJobRows() {
        DraftConfig config = buildThreePhaseDraftConfig();

        List<UUID> phaseIds = draftApplicationOrchestrator.applyDraft(tournamentId, config);

        assertThat(phaseIds).hasSize(3);

        // (a) 3 rows in phase_lifecycle_job
        Integer rowCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        assertThat(rowCount).as("3 job rows enqueued for tournament").isEqualTo(3);

        // (b) all rows PENDING
        List<String> statuses =
                jdbcTemplate.queryForList(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?"
                                + " ORDER BY sequence ASC",
                        String.class,
                        tournamentId);
        assertThat(statuses).containsExactly("PENDING", "PENDING", "PENDING");

        // sequences 1, 2, 3
        List<Integer> sequences =
                jdbcTemplate.queryForList(
                        "SELECT sequence FROM phase_lifecycle_job WHERE tournament_id = ?"
                                + " ORDER BY sequence ASC",
                        Integer.class,
                        tournamentId);
        assertThat(sequences).containsExactly(1, 2, 3);

        // (c) correct game_mode values
        List<String> gameModes =
                jdbcTemplate.queryForList(
                        "SELECT game_mode FROM phase_lifecycle_job WHERE tournament_id = ?"
                                + " ORDER BY sequence ASC",
                        String.class,
                        tournamentId);
        assertThat(gameModes.get(0)).isEqualToIgnoringCase("roundRobin");
        assertThat(gameModes.get(1)).isEqualToIgnoringCase("roundRobin");
        assertThat(gameModes.get(2)).isEqualToIgnoringCase("siegerehrung");
    }

    /**
     * AC-ERROR-HANDLING-APPLY-ROLLBACK (E55S06): if applyDraft() fails mid-way (tournament not in
     * DRAFT status), the entire TX rolls back — zero phases and zero job rows persist.
     */
    @Test
    @DisplayName(
            "applyDraft() on non-DRAFT tournament rolls back — zero job rows persist"
                    + " — AC-ERROR-HANDLING-APPLY-ROLLBACK")
    void applyDraft_onPlannedTournament_rollsBackIncludingJobRows() {
        // First apply to get to PLANNED
        DraftConfig config = buildThreePhaseDraftConfig();
        draftApplicationOrchestrator.applyDraft(tournamentId, config);

        // Count rows after first apply
        Integer rowsAfterFirstApply =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        assertThat(rowsAfterFirstApply).isEqualTo(3);

        // Second applyDraft on PLANNED → should throw (rolls back)
        assertThatThrownBy(() -> draftApplicationOrchestrator.applyDraft(tournamentId, config))
                .isInstanceOf(de.vvwt.tm.tournament.exceptions.TournamentNotInDraftException.class);

        // Job row count unchanged (3 from first apply; no new rows from failed second apply)
        Integer rowsAfterFailedApply =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        assertThat(rowsAfterFailedApply)
                .as("Failed applyDraft must not add additional job rows")
                .isEqualTo(3);
    }

    /**
     * AC-ERROR-HANDLING-NO-DOUBLE-EVENT-PUBLISH (E55S06): verifies that only {@code
     * PhaseStatusChangedEvent} (and possibly {@code PhaseInputsChangedEvent} if present) are
     * published during applyDraft() — none of the deleted events appear.
     *
     * <p>Since {@code MatchGenJobScheduledEvent}, {@code SlotOptJobScheduledEvent}, {@code
     * OptimizePhaseRequestedEvent}, {@code SlotOptJobCompletedEvent} are DELETED in E55S06, this is
     * vacuously satisfied at compile time. The test verifies the pipeline path via DB rows.
     */
    @Test
    @DisplayName(
            "applyDraft() creates job rows via enqueueJob() — no deleted event classes published"
                    + " — AC-ERROR-HANDLING-NO-DOUBLE-EVENT-PUBLISH")
    void applyDraft_usesEnqueueJobPath_notEventPublishPath() {
        DraftConfig config = buildThreePhaseDraftConfig();

        draftApplicationOrchestrator.applyDraft(tournamentId, config);

        // DB rows present confirms enqueueJob path was taken (not a publishEvent-only path)
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        assertThat(count)
                .as(
                        "phase_lifecycle_job rows present confirms enqueueJob path"
                                + " (deleted event classes cannot be published)")
                .isGreaterThan(0);
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

    private DraftConfig buildThreePhaseDraftConfig() {
        DraftSection s1 =
                new DraftSection(1, "team_number", 2, GameMode.ROUND_ROBIN, 0, 0, 12, 1, List.of());
        DraftSection s2 =
                new DraftSection(2, "team_number", 2, GameMode.ROUND_ROBIN, 0, 0, 12, 1, List.of());
        DraftSection s3 =
                new DraftSection(3, "team_number", 1, GameMode.SIEGEREHRUNG, 0, 0, 5, 1, List.of());
        return new DraftConfig(List.of(s1, s2, s3));
    }
}
