package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
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
}
