package de.vvwt.slotopt.standalone.runtime;

import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cross-package contract test for the {@link CpuThrottle} interface.
 *
 * <p>Per DEC-36: this test is in the {@code runtime} package, which is different from {@code
 * runtime.internal} — it references {@link CpuThrottle} exclusively via the public interface.
 *
 * <p>Story: E41S05 AC-CPU-THROTTLE.
 */
@ExtendWith(MockitoExtension.class)
class CpuThrottleTest {

    @Mock private CpuThrottle cpuThrottle;

    /** TC-4: CpuThrottle.sleep can be invoked with any Duration. */
    @Test
    void sleep_interfaceInvocable() throws WorkerLoopException {
        cpuThrottle.sleep(Duration.ofSeconds(1));
        verify(cpuThrottle).sleep(Duration.ofSeconds(1));
    }
}
