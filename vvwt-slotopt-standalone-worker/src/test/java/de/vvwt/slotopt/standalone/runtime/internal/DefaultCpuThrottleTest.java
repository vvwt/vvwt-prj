package de.vvwt.slotopt.standalone.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.standalone.runtime.WorkerLoopException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Same-package white-box tests for {@link DefaultCpuThrottle}.
 *
 * <p>Per DEC-36 same-package carve-out: this test is in {@code runtime.internal} and may reference
 * {@link DefaultCpuThrottle} directly.
 *
 * <p>Story: E41S05 AC-CPU-THROTTLE.
 */
class DefaultCpuThrottleTest {

    /** TC-5: sleep with zero duration completes without throwing. */
    @Test
    void sleep_zeroMillis_completesNormally() throws WorkerLoopException {
        DefaultCpuThrottle throttle = new DefaultCpuThrottle();
        // Should complete immediately — no timing assertions to avoid flakiness
        throttle.sleep(Duration.ZERO);
    }

    /** TC-6: sleep with very short duration completes normally. */
    @Test
    void sleep_shortDuration_completesNormally() throws WorkerLoopException {
        DefaultCpuThrottle throttle = new DefaultCpuThrottle();
        throttle.sleep(Duration.ofMillis(1));
    }

    /**
     * TC-7: InterruptedException during sleep re-throws as WorkerLoopException with INTERRUPTED
     * exit code and restores the interrupt flag.
     *
     * <p>Verified by interrupting the calling thread before invoking sleep with a very long
     * duration — Thread.sleep will throw InterruptedException immediately.
     */
    @Test
    void sleep_interrupted_throwsWorkerLoopExceptionAndRestoresFlag() {
        DefaultCpuThrottle throttle = new DefaultCpuThrottle();

        AtomicBoolean interruptFlagRestored = new AtomicBoolean(false);

        Thread testThread =
                new Thread(
                        () -> {
                            Thread.currentThread().interrupt(); // pre-interrupt
                            try {
                                throttle.sleep(Duration.ofSeconds(60)); // long enough to not finish
                            } catch (WorkerLoopException e) {
                                // Thread.currentThread().isInterrupted() should be true after
                                interruptFlagRestored.set(Thread.currentThread().isInterrupted());
                                assertThat(e.getExitCode()).isEqualTo(130);
                                assertThat(e.getMessage()).contains("interrupted");
                                assertThat(e.getCause()).isInstanceOf(InterruptedException.class);
                            }
                        });
        testThread.start();
        try {
            testThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(interruptFlagRestored.get())
                .as("interrupt flag must be restored after WorkerLoopException")
                .isTrue();
    }
}
