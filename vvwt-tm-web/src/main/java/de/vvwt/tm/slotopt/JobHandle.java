package de.vvwt.tm.slotopt;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory handle for an active slot-optimization job (E27S02, AC-JOB-REGISTRY-AUTHORED, DEC-49
 * T-6).
 *
 * <p>Carries:
 *
 * <ul>
 *   <li>The {@link CancellationToken} for cooperative cancellation (checked between permutations).
 *   <li>An {@link AtomicReference} to the best result found so far ({@code null} until the first
 *       permutation is evaluated).
 *   <li>The start timestamp.
 * </ul>
 *
 * <p>Thread-safety: {@link CancellationToken} and {@link AtomicReference} provide lock-free
 * visibility between the compute thread and cancel callers.
 *
 * @see SlotOptimizationJobRegistry
 * @see CancellationToken
 * @see <a href="../../../../../../../../docs/governance/stories/E27S02.story.md">Story E27S02</a>
 */
public final class JobHandle {

    private final CancellationToken cancellationToken;
    private final AtomicReference<OptimizationResult> bestSoFar;
    private final Instant startedAt;

    /**
     * Constructs a new job handle.
     *
     * @param cancellationToken the cooperative cancellation flag for this job
     * @param startedAt the instant at which the optimization was started
     */
    public JobHandle(CancellationToken cancellationToken, Instant startedAt) {
        if (cancellationToken == null) {
            throw new IllegalArgumentException("cancellationToken must not be null");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt must not be null");
        }
        this.cancellationToken = cancellationToken;
        this.bestSoFar = new AtomicReference<>(null);
        this.startedAt = startedAt;
    }

    /**
     * Returns the cancellation token for this job.
     *
     * @return the cancellation token
     */
    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    /**
     * Returns the current best-so-far result, or {@code null} if no permutation has been evaluated
     * yet.
     *
     * @return the best result found so far, or {@code null}
     */
    public OptimizationResult getBestSoFar() {
        return bestSoFar.get();
    }

    /**
     * Updates the best-so-far result if the new result has a strictly lower score.
     *
     * <p>Thread-safe via compare-and-set loop. The compute thread calls this after evaluating each
     * permutation.
     *
     * @param candidate the candidate result to compare
     */
    public void updateBestSoFar(OptimizationResult candidate) {
        bestSoFar.updateAndGet(
                current ->
                        (current == null || candidate.bestScore() < current.bestScore())
                                ? candidate
                                : current);
    }

    /**
     * Returns the instant at which the optimization was started.
     *
     * @return the start time
     */
    public Instant getStartedAt() {
        return startedAt;
    }
}
