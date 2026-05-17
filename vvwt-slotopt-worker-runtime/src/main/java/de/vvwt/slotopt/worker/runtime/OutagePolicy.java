package de.vvwt.slotopt.worker.runtime;

/**
 * Strategy for reacting to a sustained dispatcher outage.
 *
 * <p>The standalone worker terminates the loop on a dispatcher outage (CLI semantics). The TM
 * embedded worker (E63S03) survives the outage and resumes when the dispatcher becomes reachable
 * again. This interface is the pluggable seam that allows both behaviours from the same base loop.
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@code StandaloneOutagePolicy} (in {@code vvwt-slotopt-standalone-worker}) — terminates the
 *       loop by throwing {@code WorkerLoopException} with exit code {@code
 *       DISPATCHER_UNREACHABLE_RUNTIME}. Preserves CLI failure semantics.
 *   <li>{@code EmbeddedOutagePolicy} (in E63S03, TM module) — logs the outage and returns normally,
 *       allowing the loop to wait and retry.
 * </ul>
 *
 * <p>DEC-35-by-analogy: public interface in the {@code runtime} root package.
 *
 * <p>Story: E63S01 AC-TEST-OUTAGE-SEAM-PLUGGABLE, AC-ERR-OUTAGE-SEAM-STANDALONE-UNCHANGED.
 */
public interface OutagePolicy {

    /**
     * Called when a sustained dispatcher outage is detected (e.g., {@link DispatcherException}
     * during pull-packet or submit-result).
     *
     * <p>Implementations may:
     *
     * <ul>
     *   <li>Throw a runtime exception to terminate the loop (standalone behaviour).
     *   <li>Return normally to allow the loop to back off and retry (embedded behaviour).
     * </ul>
     *
     * @param cause the dispatcher exception that triggered the outage detection
     */
    void onOutage(DispatcherException cause);
}
