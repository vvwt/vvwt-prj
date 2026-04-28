package de.vvwt.slotopt.standalone.runtime.internal;

import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import de.vvwt.slotopt.standalone.runtime.CpuThrottle;
import de.vvwt.slotopt.standalone.runtime.WorkerLoopException;
import java.time.Duration;

/**
 * Default implementation of {@link CpuThrottle} using {@link Thread#sleep(long)}.
 *
 * <p>Cooperatively handles {@link InterruptedException}: restores the thread interrupt flag and
 * re-throws as {@link WorkerLoopException} with exit code {@link ExitCode#INTERRUPTED} (130) so the
 * polling loop terminates cleanly per spec.
 *
 * <p>DEC-35-by-analogy: implementation in {@code runtime.internal}; public interface {@link
 * CpuThrottle} in {@code runtime}.
 *
 * <p>Story: E41S05 AC-CPU-THROTTLE.
 */
public class DefaultCpuThrottle implements CpuThrottle {

    /** {@inheritDoc} */
    @Override
    public void sleep(Duration duration) throws WorkerLoopException {
        long millis = duration.toMillis();
        if (millis <= 0) {
            return; // no-op for zero/negative duration
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore interrupt flag
            throw new WorkerLoopException(
                    ExitCode.INTERRUPTED, "CpuThrottle.sleep interrupted: " + e.getMessage(), e);
        }
    }
}
