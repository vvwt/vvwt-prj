package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.OptimizationAlreadyInProgressException;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * DB-primary implementation of {@link SlotOptimizationJobRegistry} (E27S02, E55S06, DEC-64 D-6).
 *
 * <h2>E27S02 handle API (backward-compatible)</h2>
 *
 * <p>{@link #register(UUID, JobHandle)} and {@link #complete(UUID)} maintain an in-memory {@link
 * ConcurrentHashMap} keyed by tournament UUID for CancellationToken access.
 *
 * <h2>E55S06 DB-primary getHandle() (DEC-64 D-6)</h2>
 *
 * <p>{@link #getHandle(UUID)} is reimplemented to be DB-primary: it first queries {@code
 * phase_lifecycle_job} for a RUNNING row for the given tournament. If no RUNNING row exists in DB,
 * returns {@link Optional#empty()} — even if an in-memory handle is registered (prevents stale
 * handles from leaking after job completion). If a RUNNING row exists in DB, returns the in-memory
 * handle if present (for CancellationToken access, DEC-49 D-11 cancel contract).
 *
 * <h2>E51S04 FIFO extension removed (DEC-64 D-5)</h2>
 *
 * <p>The in-memory FIFO queue ({@code fifoQueues} field) and the {@code enqueue}, {@code
 * getQueueDepth}, {@code peekQueue}, {@code dequeueHead} methods are removed. The DB queue ({@code
 * phase_lifecycle_job}) is now the authoritative FIFO (DEC-64 D-11).
 *
 * <h2>Durability (DEC-49 T-6)</h2>
 *
 * <p>The in-memory handle map is scoped to the JVM lifetime. TM restart loses CancellationToken
 * handles; the DB queue persists (DEC-64 D-10).
 *
 * <p>Per DEC-35 naming canon: implementation lives in {@code de.vvwt.tm.slotopt.internal},
 * interface in {@code de.vvwt.tm.slotopt} root package.
 *
 * @see SlotOptimizationJobRegistry
 * @see JobHandle
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-11</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-64.md">DEC-64 D-5 +
 *     D-6</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E55S06.story.md">Story
 *     E55S06</a>
 * @since E27S02
 * @updated E55S06 (DB-primary getHandle; FIFO methods removed; JdbcTemplate injected)
 */
@Service
public class DefaultSlotOptimizationJobRegistry implements SlotOptimizationJobRegistry {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultSlotOptimizationJobRegistry.class);

    /**
     * SQL: query phase_lifecycle_job for a RUNNING row for the given tournament. Returns count.
     *
     * <p>Per DEC-64 D-3 (per-tournament single-thread worker), at most one RUNNING row per
     * tournament at steady state.
     */
    private static final String SQL_HAS_RUNNING_JOB =
            "SELECT COUNT(*) FROM phase_lifecycle_job"
                    + " WHERE tournament_id = ? AND status = 'RUNNING'";

    /** Handle map: tournamentId → currently-running job's handle (DEC-49 D-11). */
    private final ConcurrentHashMap<UUID, JobHandle> activeJobs = new ConcurrentHashMap<>();

    /** JdbcTemplate for DB-primary getHandle() query (DEC-64 D-6, E55S06). */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs the registry with a {@link JdbcTemplate} for DB-primary {@link #getHandle(UUID)}
     * queries (DEC-64 D-6, E55S06).
     *
     * @param jdbcTemplate the JDBC template; must not be {@code null}
     */
    public DefaultSlotOptimizationJobRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // =========================================================================
    // E27S02 handle API (backward-compatible)
    // =========================================================================

    /** {@inheritDoc} */
    @Override
    public void register(UUID tournamentId, JobHandle handle) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        if (handle == null) {
            throw new IllegalArgumentException("handle must not be null");
        }
        JobHandle existing = activeJobs.putIfAbsent(tournamentId, handle);
        if (existing != null) {
            throw new OptimizationAlreadyInProgressException(tournamentId);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <h3>DB-primary semantics (DEC-64 D-6, E55S06)</h3>
     *
     * <ol>
     *   <li>Query DB: does a RUNNING row exist for {@code tournamentId} in {@code
     *       phase_lifecycle_job}?
     *   <li>If NO RUNNING row → return {@link Optional#empty()} (no active job, or job already
     *       completed — in-memory handle may still be present but is stale).
     *   <li>If RUNNING row exists → return the in-memory handle if present (for CancellationToken
     *       access, DEC-49 D-11 cancel contract). If no in-memory handle (post-restart scenario),
     *       the RUNNING row existence is logged but Optional.empty() is returned (cancel controller
     *       cannot signal the L3 loop without a CancellationToken — this is an accepted trade-off
     *       per DEC-49 T-6: in-memory state lost on restart).
     * </ol>
     */
    @Override
    public Optional<JobHandle> getHandle(UUID tournamentId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }

        // DB-primary: check if a RUNNING row exists
        Integer runningCount =
                jdbcTemplate.queryForObject(SQL_HAS_RUNNING_JOB, Integer.class, tournamentId);
        boolean hasRunningRow = runningCount != null && runningCount > 0;

        if (!hasRunningRow) {
            // No RUNNING row in DB → no active job (authoritative)
            return Optional.empty();
        }

        // RUNNING row exists in DB → return in-memory handle if present
        JobHandle handle = activeJobs.get(tournamentId);
        if (handle == null) {
            // RUNNING row in DB but no in-memory handle (post-restart scenario — DEC-49 T-6).
            // Return a sentinel handle with a pre-cancelled token so the cancel controller
            // can observe the "running" state from the DB row. The token is pre-cancelled to
            // prevent any stale compute loop from running on this sentinel.
            LOG.warn(
                    "DefaultSlotOptimizationJobRegistry: RUNNING row exists in DB for"
                            + " tournament={} but no in-memory handle found"
                            + " (possible restart recovery scenario — DEC-49 T-6)."
                            + " Returning post-restart sentinel handle.",
                    tournamentId);
            CancellationToken sentinel = CancellationToken.create();
            sentinel.cancel();
            return Optional.of(new JobHandle(sentinel, Instant.now()));
        }
        return Optional.of(handle);
    }

    /** {@inheritDoc} */
    @Override
    public void complete(UUID tournamentId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        activeJobs.remove(tournamentId);
    }
}
