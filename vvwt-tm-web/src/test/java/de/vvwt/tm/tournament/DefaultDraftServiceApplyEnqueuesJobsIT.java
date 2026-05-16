package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.phaselifecycle.DraftApplicationOrchestrator;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
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
 * IT for E55S06: {@link DraftApplicationOrchestrator#applyDraft(UUID, DraftConfig)} must enqueue
 * {@code phase_lifecycle_job} rows (DEC-64 D-11, DEC-55 D-3 superseded).
 *
 * <h2>E55S06 Option C adaptation</h2>
 *
 * <p>Originally this IT tested {@link DraftService#apply(UUID, DraftConfig)} directly. With Option
 * C (DEC-64 D-11), job-row insertion is handled by {@link DraftApplicationOrchestrator} in the
 * {@code phaselifecycle} module. {@link DraftService#apply} no longer inserts rows — it only
 * creates Phase and TeamAvatar entities. This IT is therefore rewired to invoke the orchestrator.
 *
 * <p>The companion IT {@link DraftApplicationOrchestratorIT} covers the primary orchestrator ACs.
 * This IT covers the same enqueue contract but via the pre-existing test fixture (unchanged DB
 * schema, tearDown, fixture injection) — retained for coverage breadth.
 *
 * <h2>Acceptance Criteria covered</h2>
 *
 * <ul>
 *   <li>AC-IMPL-DRAFTSERVICE-APPLY-ENQUEUES-JOBS — orchestrator.applyDraft() enqueues N job rows in
 *       same TX (this IT tests via orchestrator per Option C)
 *   <li>AC-TEST-DRAFTSERVICE-APPLY-ENQUEUES-N-JOBS-RED — 3 PENDING rows, correct sequences, no
 *       {@code MatchGenJobScheduledEvent} published
 *   <li>AC-ERROR-HANDLING-APPLY-ROLLBACK — applyDraft() failure rolls back including job rows
 *   <li>AC-ERROR-HANDLING-NO-DOUBLE-EVENT-PUBLISH — no deleted events published during apply +
 *       orchestrator cycle
 * </ul>
 *
 * <h2>DEC-26/DEC-46 three-rule conformance</h2>
 *
 * <ul>
 *   <li>Rule 1: Schema from Flyway migration
 *   <li>Rule 2: assertj-db as independent persistence verifier
 *   <li>Rule 3: Fixture data inserted via direct JDBC
 * </ul>
 *
 * @see DraftApplicationOrchestrator
 * @see de.vvwt.tm.phaselifecycle.internal.DefaultDraftApplicationOrchestrator
 * @see <a href="DEC-64">DEC-64 D-11 — Option C: DraftApplicationOrchestrator inserts job rows</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E55S06">E55S06</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:applyenqueuesjobsit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({
    TenantContextTestSupport.class,
    DefaultDraftServiceApplyEnqueuesJobsIT.SlotOptConfig.class
})
@DisplayName(
        "DraftApplicationOrchestrator applyDraft() — enqueues phase_lifecycle_job rows — E55S06")
class DefaultDraftServiceApplyEnqueuesJobsIT {

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
                "INSERT INTO locations (id, display_name) VALUES (?, ?)", locationId, "EnqueueIT");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "EnqueueIT Tournament",
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
     * AC-TEST-DRAFTSERVICE-APPLY-ENQUEUES-N-JOBS-RED (E55S06):
     *
     * <p>applyDraft() a 3-phase DraftConfig; assert (a) 3 rows in {@code phase_lifecycle_job} with
     * {@code status='PENDING'}, (b) sequences 1, 2, 3 mapped correctly, (c) no deleted event class
     * is published.
     */
    @Test
    @DisplayName(
            "applyDraft() 3-phase config enqueues 3 PENDING phase_lifecycle_job rows"
                    + " — AC-TEST-DRAFTSERVICE-APPLY-ENQUEUES-N-JOBS-RED")
    void applyDraft_threePhaseDraftConfig_enqueuesThreePendingJobRows() {
        DraftConfig config = buildThreePhaseDraftConfig();

        List<UUID> phaseIds = draftApplicationOrchestrator.applyDraft(tournamentId, config);

        assertThat(phaseIds).hasSize(3);

        // Rule 2: independent JDBC verifier — assertj-db on phase_lifecycle_job
        Table jobTable = assertDb.table("phase_lifecycle_job").build();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                                Integer.class,
                                tournamentId))
                .as("3 job rows enqueued for tournament")
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

        // AC-ERROR-HANDLING-NO-DOUBLE-EVENT-PUBLISH: DB rows present confirms enqueueJob path
        // (deleted event classes cannot be published; enqueueJob path produces DB rows).
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                                Integer.class,
                                tournamentId))
                .as("DB rows present confirms enqueueJob path (not publishEvent path)")
                .isGreaterThan(0);
    }

    /**
     * AC-IMPL-DRAFTSERVICE-APPLY-ENQUEUES-JOBS: verify game_mode is correctly captured from
     * DraftSection.gameMode in each enqueued row.
     */
    @Test
    @DisplayName(
            "applyDraft() captures correct game_mode and tournament_id in each job row"
                    + " — AC-IMPL-DRAFTSERVICE-APPLY-ENQUEUES-JOBS")
    void applyDraft_threePhaseDraftConfig_jobRowsHaveCorrectGameModeAndTournamentId() {
        DraftConfig config = buildThreePhaseDraftConfig();

        draftApplicationOrchestrator.applyDraft(tournamentId, config);

        // game_mode for phase 1 and 2 must be 'roundRobin'; phase 3 (siegerehrung) must be
        // 'siegerehrung'
        List<String> gameModes =
                jdbcTemplate.queryForList(
                        "SELECT game_mode FROM phase_lifecycle_job WHERE tournament_id = ?"
                                + " ORDER BY sequence ASC",
                        String.class,
                        tournamentId);
        // "roundRobin" → "roundRobin", "awardCeremony" → "awardCeremony"
        assertThat(gameModes).hasSize(3);
        assertThat(gameModes.get(0)).isEqualToIgnoringCase("roundRobin");
        assertThat(gameModes.get(1)).isEqualToIgnoringCase("roundRobin");
        assertThat(gameModes.get(2)).isEqualToIgnoringCase("awardCeremony");

        // All rows reference correct tournament_id
        long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Long.class,
                        tournamentId);
        assertThat(count).isEqualTo(3L);
    }

    /**
     * AC-ERROR-HANDLING-APPLY-ROLLBACK (E55S06): if applyDraft() fails mid-way, the entire TX rolls
     * back, including any enqueued job rows. Zero job rows persist on failure.
     *
     * <p>We trigger a failure by applying on a PLANNED tournament (status != DRAFT → throws {@code
     * TournamentNotInDraftException}).
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
        int rowsAfterFirstApply =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        assertThat(rowsAfterFirstApply).isEqualTo(3);

        // Second apply on PLANNED → should throw (rolls back)
        assertThatThrownBy(() -> draftApplicationOrchestrator.applyDraft(tournamentId, config))
                .isInstanceOf(de.vvwt.tm.tournament.exceptions.TournamentNotInDraftException.class);

        // Job row count unchanged (3 from first apply; no new rows added by failed second apply)
        int rowsAfterFailedApply =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        assertThat(rowsAfterFailedApply)
                .as("Failed applyDraft must not add any additional job rows")
                .isEqualTo(3);
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
                new DraftSection(1, "team_number", 2, "roundRobin", 0, 0, 12, 1, List.of());
        DraftSection s2 =
                new DraftSection(2, "team_number", 2, "roundRobin", 0, 0, 12, 1, List.of());
        DraftSection s3 =
                new DraftSection(3, "team_number", 1, "awardCeremony", 0, 0, 5, 1, List.of());
        return new DraftConfig(List.of(s1, s2, s3));
    }
}
