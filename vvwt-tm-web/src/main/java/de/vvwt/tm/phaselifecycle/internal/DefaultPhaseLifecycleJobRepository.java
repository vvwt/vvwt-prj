// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobDetails;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate-based implementation of {@link PhaseLifecycleJobRepository} (DEC-35, DEC-58, DEC-64
 * D-4, DEC-64 D-14, E55S02).
 *
 * <p>Implements the DB-durable {@code phase_lifecycle_job} queue with a portable atomic
 * Compare-and-Swap claim mechanism. The CAS is a single-statement {@code UPDATE WHERE id=? AND
 * status='PENDING'} whose affected-row count proves the claim — explicitly NOT {@code SELECT FOR
 * UPDATE SKIP LOCKED} per DEC-64 D-4 rationale (H2-version-portable; DEC-37 Clause B precedent
 * exercises only plain row-lock).
 *
 * <p>Uses Spring's {@link JdbcTemplate} per DEC-35 §Spring-Data-carve-out (custom hand-authored
 * repository, not a Spring Data interface auto-impl).
 *
 * <p>The per-tenant routing DataSource (DEC-20 DB-per-Tenant) is injected by Spring DI; the {@link
 * JdbcTemplate} is constructed from it in the constructor.
 *
 * <h2>markCompleted contract (AC-ERROR-HANDLING-MARK-COMPLETED-NOT-RUNNING)</h2>
 *
 * <p>Option (b) is chosen: if the row's status is not {@code 'RUNNING'}, {@link
 * #markCompleted(UUID)} is a no-op with a WARN log. See {@link PhaseLifecycleJobRepository} §
 * markCompleted Javadoc for the full rationale.
 *
 * <p>Authorizing decisions: DEC-64 D-4 (schema + CAS), DEC-35 (Default* in .internal, JdbcTemplate
 * not Spring Data), DEC-58 (universal interface mandate), DEC-22 (TDD Iron Law — GREEN commit).
 *
 * @since E55S02
 */
@Repository("phaseLifecycleJobRepository")
public class DefaultPhaseLifecycleJobRepository implements PhaseLifecycleJobRepository {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultPhaseLifecycleJobRepository.class);

    // -------------------------------------------------------------------------
    // SQL constants (DEC-64 D-4 verbatim — two-step CAS protocol)
    // -------------------------------------------------------------------------

    /** Step 1 of the CAS two-step: find next PENDING job for the given tournament, FIFO order. */
    private static final String SQL_FIND_NEXT_PENDING =
            "SELECT id FROM phase_lifecycle_job"
                    + " WHERE tournament_id = ? AND status = 'PENDING'"
                    + " ORDER BY sequence ASC, enqueued_at ASC"
                    + " LIMIT 1";

    /**
     * Step 2 of the CAS two-step: atomically claim the row. Affected-rows count proves the claim
     * (DEC-64 D-4: portable across any isolation level that supports atomic single-statement
     * UPDATE). NOT SELECT FOR UPDATE SKIP LOCKED per DEC-64 D-4.
     */
    private static final String SQL_TRY_CLAIM =
            "UPDATE phase_lifecycle_job"
                    + " SET status = 'RUNNING', claimed_by = ?, claimed_at = CURRENT_TIMESTAMP"
                    + " WHERE id = ? AND status = 'PENDING'";

    /**
     * Mark a RUNNING job as COMPLETED. The WHERE clause restricts to RUNNING rows only —
     * affectedRows==0 on a non-RUNNING row signals the no-op case.
     */
    private static final String SQL_MARK_COMPLETED =
            "UPDATE phase_lifecycle_job"
                    + " SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP"
                    + " WHERE id = ? AND status = 'RUNNING'";

    /** Cooperative cancel: set cancelled=TRUE without changing status. */
    private static final String SQL_MARK_CANCELLED =
            "UPDATE phase_lifecycle_job SET cancelled = TRUE WHERE id = ?";

    /** Load the execution-time projection (jobId, phaseId, gameMode, tournamentId) for a job. */
    private static final String SQL_FIND_JOB_DETAILS =
            "SELECT id, phase_id, game_mode, tournament_id FROM phase_lifecycle_job WHERE id = ?";

    /**
     * Find the RUNNING job for a given tournament (cancel-handler lookup, per-tournament FIFO
     * invariant per DEC-64 D-3 ensures at most one RUNNING row per tournament).
     */
    private static final String SQL_FIND_RUNNING_FOR_TOURNAMENT =
            "SELECT id FROM phase_lifecycle_job"
                    + " WHERE tournament_id = ? AND status = 'RUNNING'"
                    + " ORDER BY claimed_at ASC LIMIT 1";

    /**
     * Reset stale RUNNING rows (claimed_by != currentJvmId) to PENDING for restart-recovery (DEC-64
     * D-7).
     */
    private static final String SQL_RESET_STALE_RUNNING =
            "UPDATE phase_lifecycle_job"
                    + " SET status = 'PENDING', claimed_by = NULL, claimed_at = NULL"
                    + " WHERE status = 'RUNNING' AND claimed_by != ?";

    /**
     * Find all distinct tournament IDs with at least one non-COMPLETED job (PENDING or RUNNING).
     * Used at startup to spawn per-tournament workers.
     */
    private static final String SQL_FIND_TOURNAMENTS_WITH_NON_COMPLETED =
            "SELECT DISTINCT tournament_id FROM phase_lifecycle_job"
                    + " WHERE status IN ('PENDING', 'RUNNING')";

    /**
     * Detect corrupt RUNNING rows: status=RUNNING AND claimed_by IS NULL. Returns id +
     * tournament_id for WARN logging.
     */
    private static final String SQL_FIND_CORRUPT_RUNNING =
            "SELECT id, tournament_id FROM phase_lifecycle_job"
                    + " WHERE status = 'RUNNING' AND claimed_by IS NULL";

    /** Mark a corrupt (status=RUNNING, claimed_by=NULL) row as FAILED. */
    private static final String SQL_MARK_CORRUPT_FAILED =
            "UPDATE phase_lifecycle_job SET status = 'FAILED'"
                    + " WHERE id = ? AND status = 'RUNNING' AND claimed_by IS NULL";

    /** Enqueue a new PENDING job row. */
    private static final String SQL_ENQUEUE =
            "INSERT INTO phase_lifecycle_job"
                    + " (id, tournament_id, phase_id, game_mode, sequence, status, cancelled)"
                    + " VALUES (?, ?, ?, ?, ?, 'PENDING', FALSE)";

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Spring-injection constructor. Receives the per-tenant routing DataSource (DEC-20).
     *
     * @param dataSource the per-tenant routing DataSource; must not be {@code null}
     */
    @Autowired
    public DefaultPhaseLifecycleJobRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    // -------------------------------------------------------------------------
    // PhaseLifecycleJobRepository implementation
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Executes CAS step 1: {@code SELECT id FROM phase_lifecycle_job WHERE tournament_id=? AND
     * status='PENDING' ORDER BY sequence ASC, enqueued_at ASC LIMIT 1}.
     *
     * <p>AC-IMPL-NO-SELECT-FOR-UPDATE-SKIP-LOCKED: uses plain {@code SELECT ... ORDER BY ... LIMIT
     * 1} (NOT {@code SELECT FOR UPDATE SKIP LOCKED}) per DEC-64 D-4.
     */
    @Override
    public Optional<UUID> findNextPendingJobIdForTournament(UUID tournamentId) {
        List<UUID> results =
                jdbcTemplate.query(
                        SQL_FIND_NEXT_PENDING,
                        (rs, rowNum) -> rs.getObject(1, UUID.class),
                        tournamentId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes CAS step 2: {@code UPDATE phase_lifecycle_job SET status='RUNNING', claimed_by=?,
     * claimed_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'}.
     *
     * <p>Returns {@code true} iff {@code affectedRows == 1} (claim succeeded). Returns {@code
     * false} if the row was already RUNNING (another worker won the race) or does not exist.
     */
    @Override
    public boolean tryClaim(UUID jobId, String claimedBy) {
        int affectedRows = jdbcTemplate.update(SQL_TRY_CLAIM, claimedBy, jobId);
        return affectedRows == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Contract: no-op + WARN if the row is not RUNNING (option b per
     * AC-ERROR-HANDLING-MARK-COMPLETED-NOT-RUNNING). The WHERE clause {@code status='RUNNING'}
     * ensures idempotency for restart-recovery: marking an already-COMPLETED row is a safe no-op.
     */
    @Override
    public void markCompleted(UUID jobId) {
        int affectedRows = jdbcTemplate.update(SQL_MARK_COMPLETED, jobId);
        if (affectedRows == 0) {
            log.warn(
                    "markCompleted called on job {} but affected 0 rows"
                            + " — row may not be in RUNNING state (no-op per E55S02 contract)",
                    jobId);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets {@code cancelled=TRUE} on the row regardless of status. The orchestrator observes
     * this flag at its next poll point and applies Best-So-Far semantics per DEC-64 D-10 + D-16.
     */
    @Override
    public void markCancelled(UUID jobId) {
        jdbcTemplate.update(SQL_MARK_CANCELLED, jobId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Loads {@code (id, phase_id, game_mode, tournament_id)} from a single row. Returns empty
     * {@code Optional} if the row does not exist.
     */
    @Override
    public Optional<PhaseLifecycleJobDetails> findJobDetailsById(UUID jobId) {
        List<PhaseLifecycleJobDetails> results =
                jdbcTemplate.query(
                        SQL_FIND_JOB_DETAILS,
                        (rs, rowNum) ->
                                new PhaseLifecycleJobDetails(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3),
                                        rs.getObject(4, UUID.class)),
                        jobId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Inserts a new row with {@code status='PENDING', cancelled=FALSE}. Generates a new UUID for
     * the {@code id} column. The {@code enqueued_at} column defaults to {@code CURRENT_TIMESTAMP}
     * per the migration DDL.
     */
    @Override
    public void enqueueJob(PhaseLifecycleJob job) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                SQL_ENQUEUE, id, job.tournamentId(), job.phaseId(), job.gameMode(), job.sequence());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes: {@code SELECT id FROM phase_lifecycle_job WHERE tournament_id=? AND
     * status='RUNNING' ORDER BY claimed_at ASC LIMIT 1}.
     *
     * <p>Per DEC-64 D-3 (per-tournament single-thread worker), at most one row can be RUNNING per
     * tournament at any time in steady state. The ORDER BY + LIMIT 1 is defensive (no-op for the
     * common case; prevents returning multiple rows if a data anomaly exists).
     *
     * <p>Returns empty {@code Optional} if no RUNNING row exists (cancel arrived after completion,
     * or no job was ever running).
     *
     * @since E55S05
     */
    @Override
    public Optional<UUID> findRunningJobIdForTournament(UUID tournamentId) {
        List<UUID> results =
                jdbcTemplate.query(
                        SQL_FIND_RUNNING_FOR_TOURNAMENT,
                        (rs, rowNum) -> rs.getObject(1, UUID.class),
                        tournamentId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resets all RUNNING rows with {@code claimed_by != currentJvmId} back to PENDING (DEC-64
     * D-7 restart-recovery). Single-statement UPDATE; returns affected-row count.
     *
     * @since E55S07
     */
    @Override
    public int resetStaleRunningJobs(String currentJvmId) {
        return jdbcTemplate.update(SQL_RESET_STALE_RUNNING, currentJvmId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns all distinct tournament IDs with PENDING or RUNNING rows. Used at startup to
     * determine which per-tournament workers must be (re-)spawned.
     *
     * @since E55S07
     */
    @Override
    public List<UUID> findTournamentsWithNonCompletedJobs() {
        return jdbcTemplate.query(
                SQL_FIND_TOURNAMENTS_WITH_NON_COMPLETED,
                (rs, rowNum) -> rs.getObject(1, UUID.class));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Detects RUNNING rows with NULL {@code claimed_by} (corrupt state — not reachable through
     * normal flow), marks them FAILED, and logs a WARN per
     * AC-ERROR-HANDLING-RECOVERY-STALE-CLAIM-CORRUPT-STATE. No exception is thrown.
     *
     * @since E55S07
     */
    @Override
    public int handleCorruptRunningRows() {
        // Step 1: find all corrupt rows (RUNNING + claimed_by IS NULL) for WARN logging
        List<UUID[]> corruptRows = new ArrayList<>();
        jdbcTemplate.query(
                SQL_FIND_CORRUPT_RUNNING,
                rs -> {
                    UUID id = rs.getObject(1, UUID.class);
                    UUID tournamentId = rs.getObject(2, UUID.class);
                    corruptRows.add(new UUID[] {id, tournamentId});
                });

        int count = 0;
        for (UUID[] row : corruptRows) {
            UUID id = row[0];
            UUID tournamentId = row[1];
            // Step 2: atomically mark FAILED (WHERE clause ensures idempotency)
            int affected = jdbcTemplate.update(SQL_MARK_CORRUPT_FAILED, id);
            if (affected > 0) {
                count++;
                log.warn(
                        "handleCorruptRunningRows: jobId={} tournamentId={} — status=RUNNING"
                                + " with claimed_by=NULL (corrupt state; marked FAILED)."
                                + " Operator action required: inspect phase_lifecycle_job"
                                + " row id={} for tournament {} and re-trigger if needed"
                                + " (E55S07, AC-ERROR-HANDLING-RECOVERY-STALE-CLAIM-CORRUPT-STATE)",
                        id,
                        tournamentId,
                        id,
                        tournamentId);
            }
        }
        return count;
    }
}
