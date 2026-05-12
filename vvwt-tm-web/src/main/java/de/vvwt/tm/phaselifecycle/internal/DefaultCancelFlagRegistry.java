package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.CancelFlagRegistry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Thread-safe in-memory mirror of the DB {@code phase_lifecycle_job.cancelled} flag (DEC-64 D-10,
 * DEC-35, DEC-58, E55S05).
 *
 * <p>Maintains a {@link ConcurrentHashMap}{@code <UUID, Boolean>} keyed by tournament UUID. The map
 * entry is set by {@link #requestCancel(UUID)} (called from the cancel handler), checked by {@link
 * #isCancelled(UUID)} (polled by the L3 permutation loop via the orchestrator's CancellationToken
 * bridge), and cleared by {@link #clear(UUID)} (called by the orchestrator's step-B after
 * Best-So-Far has been applied and the job is COMPLETED).
 *
 * <h2>Thread-safety</h2>
 *
 * <p>{@link ConcurrentHashMap} provides atomic put/get/remove with happens-before guarantees
 * between the cancel-handler thread and the orchestrator worker thread. No additional
 * synchronization is needed for the single-entry-per-tournament invariant (DEC-64 D-3 ensures at
 * most one RUNNING job per tournament, hence at most one cancel-flag entry per tournament at any
 * point in time).
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>Flag SET by {@link #requestCancel(UUID)} — cancel handler signals the L3 loop.
 *   <li>Flag OBSERVED by {@link #isCancelled(UUID)} — L3 permutation loop polls at each iteration
 *       (via {@link de.vvwt.tm.slotopt.CancellationToken} bridge in the orchestrator or direct poll
 *       by pre-cancel check in {@link
 *       de.vvwt.tm.slotopt.internal.DefaultCancelableInProcessSlotOptimizationService}).
 *   <li>Flag CLEARED by {@link #clear(UUID)} — orchestrator step-B calls this after Best-So-Far
 *       apply + job COMPLETED, so a subsequent job enqueued under the same tournament is NOT
 *       falsely cancelled (AC-TEST-CANCEL-CLEAR-ON-COMPLETION).
 * </ol>
 *
 * <p>Authorizing decisions: DEC-35 (Default* impl in .internal), DEC-49 D-11 (per-tournament cancel
 * scope), DEC-49 D-11a (Best-So-Far on cancel), DEC-58 (universal interface mandate), DEC-64 D-10
 * (cooperative cancel flag), DEC-64 D-14 (bean enumeration), DEC-64 D-16 (cancel-completion
 * invariant).
 *
 * @since E55S05
 */
@Service("cancelFlagRegistry")
public class DefaultCancelFlagRegistry implements CancelFlagRegistry {

    /**
     * Per-tournament cancel flags. Entry present with value {@code TRUE} means a cancel has been
     * requested for that tournament. Entry absent (or {@code null} — not used) means no cancel
     * pending.
     */
    private final ConcurrentHashMap<UUID, Boolean> flags = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     *
     * <p>Atomically records a cancel request for the given tournament by inserting {@code
     * Boolean.TRUE} into the flags map. Idempotent: calling this multiple times for the same
     * tournament has the same effect as calling it once.
     */
    @Override
    public void requestCancel(UUID tournamentId) {
        flags.put(tournamentId, Boolean.TRUE);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code true} iff a cancel has been requested for the given tournament and not yet
     * cleared. Thread-safe: {@link ConcurrentHashMap#get(Object)} provides a consistent snapshot
     * for the polling L3 thread.
     */
    @Override
    public boolean isCancelled(UUID tournamentId) {
        return Boolean.TRUE.equals(flags.get(tournamentId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removes the cancel flag for the given tournament. Called by the orchestrator's step-B
     * after the cancelled job completes (Best-So-Far applied + job COMPLETED per DEC-64 D-16).
     * After this call, {@link #isCancelled(UUID)} returns {@code false} for the same tournament,
     * allowing subsequent jobs to proceed without false-cancel detection.
     */
    @Override
    public void clear(UUID tournamentId) {
        flags.remove(tournamentId);
    }
}
