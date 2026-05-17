package de.vvwt.slotopt.standalone.runtime;

import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import de.vvwt.slotopt.worker.runtime.DispatcherException;
import de.vvwt.slotopt.worker.runtime.OutagePolicy;

/**
 * {@link OutagePolicy} for the standalone CLI worker.
 *
 * <p>On a dispatcher outage, the standalone worker terminates the polling loop by throwing a {@link
 * WorkerLoopException} with exit code {@link ExitCode#DISPATCHER_UNREACHABLE_RUNTIME} (75). This
 * preserves the CLI failure semantics — the process terminates so the supervisor can restart it
 * (AC-ERR-OUTAGE-SEAM-STANDALONE-UNCHANGED).
 *
 * <p>The embedded worker (E63S03) will provide its own implementation that returns normally,
 * allowing the loop to wait and resume when the dispatcher becomes reachable again
 * (AC-TEST-OUTAGE-SEAM-PLUGGABLE: the seam admits an alternative policy).
 *
 * <p>Story: E63S01 AC-TEST-OUTAGE-SEAM-PLUGGABLE, AC-ERR-OUTAGE-SEAM-STANDALONE-UNCHANGED.
 */
public class StandaloneOutagePolicy implements OutagePolicy {

    /**
     * Terminates the polling loop by throwing {@link WorkerLoopException}.
     *
     * @param cause the dispatcher exception that triggered the outage
     * @throws WorkerLoopException always — carries exit code {@link
     *     ExitCode#DISPATCHER_UNREACHABLE_RUNTIME} (75)
     */
    @Override
    public void onOutage(DispatcherException cause) {
        throw new WorkerLoopException(
                ExitCode.DISPATCHER_UNREACHABLE_RUNTIME,
                "dispatcher unreachable (standalone outage policy — terminate): "
                        + cause.getMessage(),
                cause);
    }
}
