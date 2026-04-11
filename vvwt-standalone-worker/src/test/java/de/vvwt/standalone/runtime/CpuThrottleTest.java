package de.vvwt.standalone.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link CpuThrottle} — sleep calculation (AC7 of E01S05).
 */
class CpuThrottleTest {

    @Test
    void at100Percent_neverSleeps() {
        CpuThrottle throttle = new CpuThrottle(100);
        assertThat(throttle.recordWorkAndGetSleepNanos(1_000_000L)).isZero();
        assertThat(throttle.recordWorkAndGetSleepNanos(5_000_000L)).isZero();
    }

    @Test
    void at50Percent_sleepsEqualToWorkTime() {
        CpuThrottle throttle = new CpuThrottle(50);
        long workNanos = 1_000_000_000L; // 1 second
        long sleepNanos = throttle.recordWorkAndGetSleepNanos(workNanos);

        // At 50%: work/(work+sleep) = 0.5 => sleep = work
        assertThat(sleepNanos).isCloseTo(workNanos, within(1L));
    }

    @Test
    void at1Percent_sleeps99xWorkTime() {
        CpuThrottle throttle = new CpuThrottle(1);
        long workNanos = 1_000_000L; // 1 ms
        long sleepNanos = throttle.recordWorkAndGetSleepNanos(workNanos);

        // At 1%: work/(work+sleep) = 0.01 => sleep = work * 99
        long expected = workNanos * 99;
        assertThat(sleepNanos).isCloseTo(expected, within(1L));
    }

    @Test
    void cumulativeTracking_multiplePackets_maintainsRatio() {
        CpuThrottle throttle = new CpuThrottle(50);
        long workNanos = 500_000L;

        long sleep1 = throttle.recordWorkAndGetSleepNanos(workNanos);
        long sleep2 = throttle.recordWorkAndGetSleepNanos(workNanos);

        // Total work = 1_000_000, total sleep should = 1_000_000
        long totalSleep = throttle.getTotalSleepNanos();
        long totalWork = throttle.getTotalWorkNanos();

        assertThat(totalWork).isEqualTo(2 * workNanos);
        assertThat(totalSleep).isCloseTo(2 * workNanos, within(2L));
        assertThat(sleep1).isPositive();
        assertThat(sleep2).isPositive();
    }

    @Test
    void invalidMaxCpuPercent_0_throws() {
        assertThatThrownBy(() -> new CpuThrottle(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCpuPercent");
    }

    @Test
    void invalidMaxCpuPercent_101_throws() {
        assertThatThrownBy(() -> new CpuThrottle(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCpuPercent");
    }

    @Test
    void negativeWorkNanos_throws() {
        CpuThrottle throttle = new CpuThrottle(50);
        assertThatThrownBy(() -> throttle.recordWorkAndGetSleepNanos(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workNanos");
    }

    @Test
    void zeroWorkNanos_returnsZeroSleep() {
        CpuThrottle throttle = new CpuThrottle(50);
        assertThat(throttle.recordWorkAndGetSleepNanos(0L)).isZero();
    }
}
