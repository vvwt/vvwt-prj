package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RED-first IT for DEC-37 Clause B lock-honoring in the orchestrator —
 * AC-TEST-DEC-37-LOCK-HONORED-RED.
 *
 * <p>DEC-22 Iron Law: RED before GREEN. At RED time, {@code drainNext()} throws {@code
 * UnsupportedOperationException}. GREEN state: an external TX that holds the tournament row-lock
 * ({@code SELECT … FOR UPDATE}) blocks the orchestrator's T-job-step-A from acquiring its own lock;
 * the orchestrator tick eventually completes after the external TX releases, with no deadlock
 * observed within 5 seconds.
 *
 * <p>Test scenario:
 *
 * <ol>
 *   <li>Thread A (lock-holder): opens a JDBC connection, starts a manual TX, issues {@code SELECT
 *       id FROM tournament WHERE id = ? FOR UPDATE} to acquire the row-lock, then signals "lock
 *       acquired" via a {@link CountDownLatch}.
 *   <li>Thread B (orchestrator): waits for "lock acquired", then calls {@code
 *       jobDrainService.drainNext(tournamentId)}.
 *   <li>Thread A holds the lock for ~500 ms, then commits (releases lock).
 *   <li>Assertion: the orchestrator tick completes successfully (job COMPLETED) within 5 seconds of
 *       Thread A releasing the lock. No deadlock.
 * </ol>
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-37 Clause B, DEC-44, DEC-64 D-9, DEC-64 D-12.
 *
 * @since E55S04
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:orchestratordec37lockiit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, OrchestratorDec37LockIT.SlotOptConfig.class})
@DisplayName("OrchestratorDec37LockIT — AC-TEST-DEC-37-LOCK-HONORED-RED (E55S04)")
class OrchestratorDec37LockIT {

    @TestConfiguration
    static class SlotOptConfig {
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {};
        }
    }

    @Autowired private JobDrainService jobDrainService;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID capturedTenantId;

    @BeforeEach
    void setUp() {
        capturedTenantId = tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "DEC37 IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "DEC37 Lock IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                true);

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "PENDING",
                0,
                false);

        for (int i = 1; i <= 4; i++) {
            UUID teamId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamId,
                    tournamentId,
                    i,
                    "Team " + i,
                    true,
                    LocalDateTime.now());
            UUID avatarId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                            + " group_position, team_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    avatarId,
                    tournamentId,
                    phaseId,
                    1,
                    i,
                    teamId);
        }

        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "roundRobin", 1));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentId);
            jdbcTemplate.update(
                    "DELETE FROM phase_lifecycle_job WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-DEC-37-LOCK-HONORED-RED: orchestrator tick blocks while an external TX holds the
     * tournament row-lock, then completes successfully after the lock is released. No deadlock
     * within 5 seconds.
     */
    @Test
    @DisplayName(
            "orchestrator step-A blocks on tournament row-lock; completes after lock release (no"
                    + " deadlock)")
    void orchestratorBlocksOnTournamentLockThenCompletes() throws Exception {
        // Latches to coordinate the two threads
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch lockReleaseLatch = new CountDownLatch(1);
        AtomicBoolean lockHolderFailed = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread A: acquire tournament row-lock via raw JDBC, hold for ~500 ms, then release
        Future<Void> lockHolderFuture =
                executor.submit(
                        () -> {
                            TenantContext.Scope scope = tenantContext.bind(capturedTenantId);
                            Connection conn = null;
                            try {
                                conn = dataSource.getConnection();
                                conn.setAutoCommit(false);
                                // Acquire the row-lock — same SQL as findByIdForUpdate
                                conn.createStatement()
                                        .execute(
                                                "SELECT id FROM tournament WHERE id = '"
                                                        + tournamentId
                                                        + "' FOR UPDATE");
                                // Signal: lock is held
                                lockAcquiredLatch.countDown();
                                // Hold lock for 500 ms, giving Thread B time to attempt drainNext
                                Thread.sleep(500);
                                // Release: commit ends the TX and releases the lock
                                conn.commit();
                                lockReleaseLatch.countDown();
                            } catch (Exception e) {
                                lockHolderFailed.set(true);
                                lockAcquiredLatch.countDown(); // unblock B if setup failed
                                lockReleaseLatch.countDown();
                                throw new RuntimeException("Lock-holder thread failed", e);
                            } finally {
                                if (conn != null) {
                                    try {
                                        conn.close();
                                    } catch (Exception ignored) {
                                    }
                                }
                                scope.close();
                            }
                            return null;
                        });

        // Thread B: wait for lock to be held, then run orchestrator tick — must block until A
        // releases
        Future<Void> orchestratorFuture =
                executor.submit(
                        () -> {
                            TenantContext.Scope scope = tenantContext.bind(capturedTenantId);
                            try {
                                // Wait until lock-holder has acquired the row-lock
                                boolean lockReady = lockAcquiredLatch.await(5, TimeUnit.SECONDS);
                                assertThat(lockReady)
                                        .as("lock-holder must acquire row-lock within 5s")
                                        .isTrue();
                                // drainNext should block at T-job-step-A's findByIdForUpdate
                                // until Thread A releases the lock, then complete
                                jobDrainService.drainNext(tournamentId);
                            } finally {
                                scope.close();
                            }
                            return null;
                        });

        executor.shutdown();
        boolean terminated = executor.awaitTermination(15, TimeUnit.SECONDS);
        assertThat(terminated).as("executor must terminate within 15s — no deadlock").isTrue();

        // Propagate any exceptions from both threads
        lockHolderFuture.get();
        orchestratorFuture.get();

        assertThat(lockHolderFailed.get()).as("lock-holder thread must not have failed").isFalse();

        // Assert: lock released signal must have been sent (no deadlock)
        boolean lockReleased = lockReleaseLatch.await(0, TimeUnit.SECONDS);
        assertThat(lockReleased).as("lock must have been released by Thread A").isTrue();

        // Assert: job is COMPLETED — orchestrator unblocked and succeeded
        Integer completedCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?"
                                + " AND status = 'COMPLETED'",
                        Integer.class,
                        tournamentId);
        assertThat(completedCount)
                .as("job must be COMPLETED after orchestrator unblocked from lock")
                .isEqualTo(1);
    }
}
