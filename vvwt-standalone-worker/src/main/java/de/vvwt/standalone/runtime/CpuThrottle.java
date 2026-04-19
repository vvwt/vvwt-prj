package de.vvwt.standalone.runtime;

/**
 * Enforces a maximum CPU utilisation percentage by tracking the ratio of work time to total elapsed
 * time (work + sleep) and computing how long to sleep after each packet.
 *
 * <p>The implementation tracks cumulative nanoseconds spent working vs. sleeping and returns the
 * sleep duration needed to keep the observed ratio at or below the target. It does NOT use
 * thread-pinning, cgroups, or any platform-specific mechanism — this ensures portability (AC7 of
 * E01S05).
 *
 * <h2>Steady-state behaviour</h2>
 *
 * <p>At 50%: after solving a packet that took W nanoseconds, sleep W nanoseconds. At 100%: never
 * sleep. At 1%: sleep approximately 99W nanoseconds.
 *
 * <p>Implements Story E01S05 AC7.
 */
public final class CpuThrottle {

    private final double maxCpuFraction;

    private long totalWorkNanos;
    private long totalSleepNanos;

    /**
     * Constructs a {@code CpuThrottle}.
     *
     * @param maxCpuPercent maximum CPU utilisation percentage, 1–100
     * @throws IllegalArgumentException if the value is out of range
     */
    public CpuThrottle(int maxCpuPercent) {
        if (maxCpuPercent < 1 || maxCpuPercent > 100) {
            throw new IllegalArgumentException(
                    "maxCpuPercent must be in [1, 100], got: " + maxCpuPercent);
        }
        this.maxCpuFraction = maxCpuPercent / 100.0;
        this.totalWorkNanos = 0L;
        this.totalSleepNanos = 0L;
    }

    /**
     * Records work time and computes the required sleep duration to maintain the target ratio.
     *
     * <p>Call this after each packet solve, passing the actual wall-clock nanoseconds spent
     * solving. Caller is responsible for sleeping the returned duration.
     *
     * @param workNanos wall-clock nanoseconds spent on this work unit (must be &gt;= 0)
     * @return nanoseconds to sleep (0 if no sleep is needed)
     */
    public long recordWorkAndGetSleepNanos(long workNanos) {
        if (workNanos < 0) {
            throw new IllegalArgumentException("workNanos must be >= 0, got: " + workNanos);
        }
        totalWorkNanos += workNanos;

        // At 100%, never sleep
        if (maxCpuFraction >= 1.0) {
            return 0L;
        }

        // Required: workNanos / (workNanos + sleepNanos) <= maxCpuFraction
        // => required totalSleepNanos >= totalWorkNanos * (1 - fraction) / fraction
        double requiredSleepNanos = totalWorkNanos * (1.0 - maxCpuFraction) / maxCpuFraction;
        long sleepNeeded = (long) requiredSleepNanos - totalSleepNanos;

        if (sleepNeeded <= 0) {
            return 0L;
        }
        totalSleepNanos += sleepNeeded;
        return sleepNeeded;
    }

    /** Returns total cumulative work time in nanoseconds. */
    public long getTotalWorkNanos() {
        return totalWorkNanos;
    }

    /** Returns total cumulative sleep time in nanoseconds. */
    public long getTotalSleepNanos() {
        return totalSleepNanos;
    }
}
