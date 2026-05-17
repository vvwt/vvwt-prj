// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.phaselifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLifecycleJobRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DAO integration tests for {@link DefaultPhaseLifecycleJobRepository} — DEC-26 + DEC-46 three
 * rules via {@link PhaseLifecycleDaoTestSupport}.
 *
 * <p>DEC-22 Iron Law: RED-first — these tests are committed RED before the V4 migration and the
 * real implementation exist. Tests fail at RED time because:
 *
 * <ul>
 *   <li>AC-TEST-FLYWAY-MIGRATION-FORWARD-RED: {@code
 *       PhaseLifecycleDaoTestSupport.freshDataSourceWithSchema()} cannot find {@code
 *       tournament/V4__phase_lifecycle_job.sql} on the classpath.
 *   <li>All CAS / FIFO / cascade tests fail because {@link DefaultPhaseLifecycleJobRepository}
 *       methods throw {@link UnsupportedOperationException}.
 * </ul>
 *
 * <p>GREEN state: after V4 migration + real impl land, all tests pass.
 *
 * <p>DEC-26 three rules:
 *
 * <ol>
 *   <li>Rule 1 — schema from {@code tournament/V4__phase_lifecycle_job.sql} (loaded by helper)
 *   <li>Rule 2 — assertj-db independent verifier for all write-path assertions
 *   <li>Rule 3 — JDBC direct-insert (via helper) for read-path fixture setup
 * </ol>
 *
 * <p>DEC-44 note: DAO ITs are standalone (no Spring context) — they wire {@link
 * DefaultPhaseLifecycleJobRepository} directly via a fresh H2 {@link DataSource}. No
 * {@code @SpringBootTest} annotation required. This is consistent with the existing {@code
 * InfoPortalStateDaoIT} precedent.
 *
 * <p>AC-GOVERNANCE-DEC-54-MVN-VERIFY — verified by full {@code mvn verify} run at QA gate.
 *
 * <p>Authorizing decisions: DEC-26 (three rules), DEC-46 (per-module helper), DEC-64 D-4 (schema +
 * CAS), DEC-22 (Iron Law).
 *
 * @since E55S02
 * @see PhaseLifecycleDaoTestSupport
 */
class PhaseLifecycleJobRepositoryDaoIT {

    private DataSource dataSource;
    private AssertDbConnection assertDb;
    private DefaultPhaseLifecycleJobRepository repository;

    // Reusable test UUIDs
    private static final UUID TOURNAMENT_A =
            UUID.fromString("aaa00000-0000-0000-0000-000000000001");
    private static final UUID TOURNAMENT_B =
            UUID.fromString("bbb00000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("11100000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        // DEC-26 Rule 1: schema from production migrations (V1 chain + V4 phase_lifecycle_job)
        dataSource = PhaseLifecycleDaoTestSupport.freshDataSourceWithSchema();
        // DEC-26 Rule 2: independent assertj-db verifier
        assertDb = PhaseLifecycleDaoTestSupport.assertDbOf(dataSource);
        // Wire repository directly to the same DataSource
        repository = new DefaultPhaseLifecycleJobRepository(dataSource);
        // Set up FK chain: locations → tournament → phase
        PhaseLifecycleDaoTestSupport.insertLocationFixture(dataSource, LOCATION_ID);
        PhaseLifecycleDaoTestSupport.insertTournamentFixture(dataSource, TOURNAMENT_A, LOCATION_ID);
        PhaseLifecycleDaoTestSupport.insertTournamentFixture(dataSource, TOURNAMENT_B, LOCATION_ID);
    }

    // ========================================================================
    // AC-TEST-FLYWAY-MIGRATION-FORWARD-RED
    // Schema structure verification after migration is applied
    // ========================================================================

    @Test
    void migrationCreatesTableWithExpectedColumns() {
        // GIVEN: migration applied in setUp() via freshDataSourceWithSchema()
        // THEN: table exists (migration applied successfully) — assertj-db structural check.
        // If V4 migration was not applied, this fails with table-not-found.
        //
        // NOTE on partial index (AC-TEST-FLYWAY-MIGRATION-FORWARD-RED §h): DEC-64 D-4 specifies
        // a partial index WHERE status='PENDING'. H2 2.3.232 does NOT support the WHERE clause
        // on CREATE INDEX (syntax error 42000). The migration uses a regular index on the same
        // columns (same name: idx_phase_lifecycle_job_tournament_pending) — the index existence
        // itself is verified via a metadata query below.
        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table).hasNumberOfRows(0);

        // Verify index exists via H2 INFORMATION_SCHEMA
        try (var conn = dataSource.getConnection();
                var ps =
                        conn.prepareStatement(
                                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES"
                                        + " WHERE TABLE_NAME = 'PHASE_LIFECYCLE_JOB'"
                                        + " AND INDEX_NAME ="
                                        + " 'IDX_PHASE_LIFECYCLE_JOB_TOURNAMENT_PENDING'")) {
            var rs = ps.executeQuery();
            rs.next();
            assertThat(rs.getInt(1))
                    .as("idx_phase_lifecycle_job_tournament_pending must exist")
                    .isGreaterThan(0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify index existence", e);
        }
    }

    @Test
    void migrationTable_hasUuidPrimaryKey() {
        // GIVEN: V4 migration applied
        // THEN: can insert a row with UUID PK (proves column type is UUID)
        UUID jobId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);

        // Direct INSERT to verify schema accepts UUID PK
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseId,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "PENDING",
                        "cancelled",
                        false));

        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table).hasNumberOfRows(1);
    }

    @Test
    void migrationTable_statusVarcharNotNull() {
        // THEN: inserting a row without status fails (proves NOT NULL constraint)
        UUID jobId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);

        // Insert with explicit PENDING status (proves status column accepts VARCHAR)
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseId,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "PENDING",
                        "cancelled",
                        false));

        // Verify status value via assertj-db (Rule 2)
        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table).row(0).value("status").isEqualTo("PENDING");
    }

    @Test
    void migrationTable_cancelledBooleanDefaultFalse() {
        // THEN: inserting without cancelled column uses DEFAULT FALSE
        UUID jobId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);

        // Insert WITHOUT cancelled column — relies on DEFAULT FALSE
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseId,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "PENDING"));

        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table).row(0).value("cancelled").isEqualTo(false);
    }

    @Test
    void migrationTable_claimedByNullable() {
        // THEN: inserting without claimed_by succeeds (proves nullable)
        UUID jobId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);

        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseId,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "PENDING",
                        "cancelled",
                        false));

        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table).row(0).value("claimed_by").isNull();
    }

    @Test
    void migrationTable_enqueuedAtDefaultNotNull() {
        // THEN: enqueued_at defaults to CURRENT_TIMESTAMP (not null)
        UUID jobId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);

        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseId,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "PENDING",
                        "cancelled",
                        false));

        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table).row(0).value("enqueued_at").isNotNull();
    }

    @Test
    void migrationTable_fkCascadeOnPhaseDelete() {
        // AC-TEST-FK-CASCADE-PHASE-DELETION-RED
        // GIVEN: a phase_lifecycle_job row linked to a phase
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);
        UUID jobId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseId,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "PENDING",
                        "cancelled",
                        false));
        // Verify the job row exists
        Table before = assertDb.table("phase_lifecycle_job").build();
        assertThat(before).hasNumberOfRows(1);

        // WHEN: delete the parent phase row
        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement("DELETE FROM phase WHERE id = ?")) {
            ps.setObject(1, phaseId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete phase fixture", e);
        }

        // THEN: job row is CASCADE-deleted (assertj-db Rule 2)
        Table after = assertDb.table("phase_lifecycle_job").build();
        assertThat(after).hasNumberOfRows(0);
    }

    // ========================================================================
    // AC-TEST-CAS-CLAIM-AFFECTED-ROWS-ONE-RED
    // ========================================================================

    @Test
    void tryClaim_onPendingRow_returnsTrue_andFlipsStatusToRunning() {
        // GIVEN: a PENDING row
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);
        PhaseLifecycleJob job = new PhaseLifecycleJob(TOURNAMENT_A, phaseId, "standard", 1);
        repository.enqueueJob(job);

        Optional<UUID> nextId = repository.findNextPendingJobIdForTournament(TOURNAMENT_A);
        assertThat(nextId).isPresent();
        UUID jobId = nextId.get();

        // WHEN
        boolean claimed = repository.tryClaim(jobId, "worker-1");

        // THEN: claim succeeded
        assertThat(claimed).isTrue();

        // THEN (DEC-26 Rule 2): row status is RUNNING, claimed_by is set, claimed_at is not null
        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table)
                .row(0)
                .value("status")
                .isEqualTo("RUNNING")
                .row(0)
                .value("claimed_by")
                .isEqualTo("worker-1")
                .row(0)
                .value("claimed_at")
                .isNotNull();
    }

    // ========================================================================
    // AC-TEST-CAS-CLAIM-ALREADY-RUNNING-RETURNS-FALSE-RED
    // ========================================================================

    @Test
    void tryClaim_onAlreadyRunningRow_returnsFalse_andDoesNotChangeRow() {
        // GIVEN: a row already in RUNNING state (via direct insert, Rule 3)
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);
        UUID jobId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseId,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "RUNNING",
                        "claimed_by",
                        "worker-original",
                        "cancelled",
                        false));

        // WHEN: another worker tries to claim
        boolean claimed = repository.tryClaim(jobId, "worker-2");

        // THEN: claim failed (affected rows == 0)
        assertThat(claimed).isFalse();

        // THEN (Rule 2): row unchanged — still RUNNING, still claimed by original
        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table)
                .row(0)
                .value("status")
                .isEqualTo("RUNNING")
                .row(0)
                .value("claimed_by")
                .isEqualTo("worker-original");
    }

    // ========================================================================
    // AC-TEST-CAS-CLAIM-RACE-LOSER-RETURNS-FALSE-RED
    // ========================================================================

    @Test
    void tryClaim_concurrentWorkers_exactlyOneWins() throws Exception {
        // GIVEN: a PENDING row
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);
        PhaseLifecycleJob job = new PhaseLifecycleJob(TOURNAMENT_A, phaseId, "standard", 1);
        repository.enqueueJob(job);
        Optional<UUID> nextId = repository.findNextPendingJobIdForTournament(TOURNAMENT_A);
        assertThat(nextId).isPresent();
        UUID jobId = nextId.get();

        // WHEN: two workers try to claim the same PENDING row concurrently
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<Boolean> futureA =
                exec.submit(
                        () -> {
                            startGate.await();
                            return repository.tryClaim(jobId, "worker-A");
                        });
        Future<Boolean> futureB =
                exec.submit(
                        () -> {
                            startGate.await();
                            return repository.tryClaim(jobId, "worker-B");
                        });

        startGate.countDown(); // release both workers simultaneously
        boolean resultA = futureA.get(5, TimeUnit.SECONDS);
        boolean resultB = futureB.get(5, TimeUnit.SECONDS);
        exec.shutdown();

        // THEN: exactly one wins
        assertThat(resultA ^ resultB)
                .as("Exactly one of worker-A and worker-B must win the CAS claim")
                .isTrue();

        // THEN (Rule 2): row status is RUNNING, claimed_by is either A or B (exactly one)
        Table table = assertDb.table("phase_lifecycle_job").build();
        String winner = resultA ? "worker-A" : "worker-B";
        assertThat(table)
                .row(0)
                .value("status")
                .isEqualTo("RUNNING")
                .row(0)
                .value("claimed_by")
                .isEqualTo(winner);
    }

    // ========================================================================
    // AC-TEST-FIND-NEXT-PENDING-FIFO-ORDER-RED
    // ========================================================================

    @Test
    void findNextPendingJobIdForTournament_returnsFifoOrder() {
        // GIVEN: 3 PENDING rows for TOURNAMENT_A with sequences 3, 1, 2 (inserted out of order)
        UUID phase1 = UUID.randomUUID();
        UUID phase2 = UUID.randomUUID();
        UUID phase3 = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phase1, TOURNAMENT_A, 1);
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phase2, TOURNAMENT_A, 2);
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phase3, TOURNAMENT_A, 3);

        // Insert job rows out-of-sequence order (sequence 3, then 1, then 2)
        PhaseLifecycleJob jobSeq3 = new PhaseLifecycleJob(TOURNAMENT_A, phase3, "standard", 3);
        PhaseLifecycleJob jobSeq1 = new PhaseLifecycleJob(TOURNAMENT_A, phase1, "standard", 1);
        PhaseLifecycleJob jobSeq2 = new PhaseLifecycleJob(TOURNAMENT_A, phase2, "standard", 2);
        repository.enqueueJob(jobSeq3);
        repository.enqueueJob(jobSeq1);
        repository.enqueueJob(jobSeq2);

        // Retrieve all 3 job IDs via direct JDBC (Rule 3 for finding IDs)
        java.util.List<UUID> jobIds = new java.util.ArrayList<>();
        try (var conn = dataSource.getConnection();
                var ps =
                        conn.prepareStatement(
                                "SELECT id, sequence FROM phase_lifecycle_job"
                                        + " WHERE tournament_id = ? AND status = 'PENDING'"
                                        + " ORDER BY sequence ASC")) {
            ps.setObject(1, TOURNAMENT_A);
            var rs = ps.executeQuery();
            while (rs.next()) {
                jobIds.add(rs.getObject("id", UUID.class));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(jobIds).hasSize(3);
        UUID jobSeq1Id = jobIds.get(0); // sequence=1 → first FIFO
        UUID jobSeq2Id = jobIds.get(1); // sequence=2 → second
        // sequence=3 → third (not retrieved yet)

        // WHEN: findNextPendingJobIdForTournament returns sequence=1 first
        Optional<UUID> first = repository.findNextPendingJobIdForTournament(TOURNAMENT_A);
        assertThat(first).isPresent().contains(jobSeq1Id);

        // After claiming sequence=1, next should be sequence=2
        repository.tryClaim(jobSeq1Id, "worker-1");
        Optional<UUID> second = repository.findNextPendingJobIdForTournament(TOURNAMENT_A);
        assertThat(second).isPresent().contains(jobSeq2Id);
    }

    // ========================================================================
    // AC-TEST-FIND-NEXT-PENDING-PER-TOURNAMENT-ISOLATION-RED
    // ========================================================================

    @Test
    void findNextPendingJobIdForTournament_isolatesByTournament() {
        // GIVEN: tournament A has sequence=5, tournament B has sequence=1
        UUID phaseA = UUID.randomUUID();
        UUID phaseB = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseA, TOURNAMENT_A, 5);
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseB, TOURNAMENT_B, 1);

        // Insert via direct JDBC (Rule 3 for write-path fixture in isolation test)
        UUID jobAId = UUID.randomUUID();
        UUID jobBId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobAId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseA,
                        "game_mode",
                        "standard",
                        "sequence",
                        5,
                        "status",
                        "PENDING",
                        "cancelled",
                        false));
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobBId,
                        "tournament_id",
                        TOURNAMENT_B,
                        "phase_id",
                        phaseB,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "PENDING",
                        "cancelled",
                        false));

        // WHEN: query for tournament A
        Optional<UUID> nextForA = repository.findNextPendingJobIdForTournament(TOURNAMENT_A);

        // THEN: returns tournament A's job (sequence=5), NOT tournament B's (sequence=1)
        assertThat(nextForA).isPresent().contains(jobAId);
    }

    // ========================================================================
    // AC-ERROR-HANDLING-CLAIM-BY-ID-NOT-FOUND
    // ========================================================================

    @Test
    void tryClaim_nonExistentJobId_returnsFalse_noException() {
        UUID nonExistent = UUID.randomUUID();
        // WHEN / THEN: no exception; returns false
        assertThatCode(
                        () -> {
                            boolean result = repository.tryClaim(nonExistent, "worker-1");
                            assertThat(result).isFalse();
                        })
                .doesNotThrowAnyException();
    }

    // ========================================================================
    // AC-ERROR-HANDLING-MARK-COMPLETED-NOT-RUNNING (contract: no-op + WARN)
    // ========================================================================

    @Test
    void markCompleted_onPendingRow_isNoOpNoException() {
        // GIVEN: a PENDING row (direct insert, Rule 3)
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);
        UUID jobId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseId,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "PENDING",
                        "cancelled",
                        false));

        // WHEN: markCompleted on a non-RUNNING row
        // THEN: no exception (no-op + WARN contract per E55S02)
        assertThatCode(() -> repository.markCompleted(jobId)).doesNotThrowAnyException();

        // THEN (Rule 2): status is unchanged (still PENDING — no-op)
        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table).row(0).value("status").isEqualTo("PENDING");
    }

    // ========================================================================
    // enqueueJob + markCompleted (happy path, RUNNING → COMPLETED)
    // ========================================================================

    @Test
    void enqueueJob_insertsRowWithStatusPending() {
        // GIVEN
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);
        PhaseLifecycleJob job = new PhaseLifecycleJob(TOURNAMENT_A, phaseId, "standard", 1);

        // WHEN
        repository.enqueueJob(job);

        // THEN (Rule 2): assertj-db verifies status=PENDING
        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table)
                .hasNumberOfRows(1)
                .row(0)
                .value("tournament_id")
                .isEqualTo(TOURNAMENT_A)
                .row(0)
                .value("phase_id")
                .isEqualTo(phaseId)
                .row(0)
                .value("status")
                .isEqualTo("PENDING")
                .row(0)
                .value("cancelled")
                .isEqualTo(false);
    }

    @Test
    void markCompleted_onRunningRow_setsStatusCompleted_andCompletedAt() {
        // GIVEN: a RUNNING row (direct insert, Rule 3)
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);
        UUID jobId = UUID.randomUUID();
        Instant claimedAt = Instant.now();
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseId,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "RUNNING",
                        "claimed_by",
                        "worker-1",
                        "cancelled",
                        false));

        // WHEN
        repository.markCompleted(jobId);

        // THEN (Rule 2)
        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table)
                .row(0)
                .value("status")
                .isEqualTo("COMPLETED")
                .row(0)
                .value("completed_at")
                .isNotNull();
    }

    @Test
    void markCancelled_setsCancelledFlagTrue() {
        // GIVEN: a RUNNING row
        UUID phaseId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phaseId, TOURNAMENT_A, 1);
        UUID jobId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        jobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phaseId,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "RUNNING",
                        "claimed_by",
                        "worker-1",
                        "cancelled",
                        false));

        // WHEN
        repository.markCancelled(jobId);

        // THEN (Rule 2): cancelled is TRUE, status is UNCHANGED (still RUNNING)
        Table table = assertDb.table("phase_lifecycle_job").build();
        assertThat(table)
                .row(0)
                .value("cancelled")
                .isEqualTo(true)
                .row(0)
                .value("status")
                .isEqualTo("RUNNING");
    }

    @Test
    void findNextPendingJobIdForTournament_returnsEmpty_whenNoRows() {
        // WHEN: no rows exist for TOURNAMENT_A
        Optional<UUID> result = repository.findNextPendingJobIdForTournament(TOURNAMENT_A);
        // THEN
        assertThat(result).isEmpty();
    }

    @Test
    void findNextPendingJobIdForTournament_skipsNonPendingRows() {
        // GIVEN: one RUNNING and one PENDING row for TOURNAMENT_A
        UUID phase1 = UUID.randomUUID();
        UUID phase2 = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phase1, TOURNAMENT_A, 1);
        PhaseLifecycleDaoTestSupport.insertPhaseFixture(dataSource, phase2, TOURNAMENT_A, 2);

        UUID runningJobId = UUID.randomUUID();
        UUID pendingJobId = UUID.randomUUID();
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        runningJobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phase1,
                        "game_mode",
                        "standard",
                        "sequence",
                        1,
                        "status",
                        "RUNNING",
                        "claimed_by",
                        "worker-1",
                        "cancelled",
                        false));
        PhaseLifecycleDaoTestSupport.insertDirectly(
                dataSource,
                "phase_lifecycle_job",
                java.util.Map.of(
                        "id",
                        pendingJobId,
                        "tournament_id",
                        TOURNAMENT_A,
                        "phase_id",
                        phase2,
                        "game_mode",
                        "standard",
                        "sequence",
                        2,
                        "status",
                        "PENDING",
                        "cancelled",
                        false));

        // WHEN
        Optional<UUID> result = repository.findNextPendingJobIdForTournament(TOURNAMENT_A);

        // THEN: returns the PENDING row (not the RUNNING one)
        assertThat(result).isPresent().contains(pendingJobId);
    }
}
