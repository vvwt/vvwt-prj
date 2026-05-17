// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.runtime;

import java.time.Duration;

/**
 * Paces the polling loop by sleeping for the configured idle-poll interval.
 *
 * <p>Per E37S02 spec § (c) "CPU Throttle" and E01S05 AC7: enforces {@code --max-cpu-percent} by
 * sleeping between poll iterations when no packet is available.
 *
 * <p>DEC-35-by-analogy: public interface in the {@code runtime} package root; canonical
 * implementation in {@link de.vvwt.slotopt.standalone.runtime.internal.DefaultCpuThrottle}.
 *
 * <p>Story: E41S05 AC-CPU-THROTTLE.
 */
public interface CpuThrottle {

    /**
     * Sleeps for the given duration.
     *
     * <p>Implements cooperative interrupt handling: if the calling thread is interrupted during
     * sleep, the interrupt flag is restored and a {@link WorkerLoopException} is thrown with exit
     * code {@link de.vvwt.slotopt.standalone.bootstrap.ExitCode#INTERRUPTED}.
     *
     * @param duration the duration to sleep; must not be {@code null}
     * @throws WorkerLoopException if the calling thread is interrupted during sleep; carries exit
     *     code 130 (interrupted)
     */
    void sleep(Duration duration) throws WorkerLoopException;
}
