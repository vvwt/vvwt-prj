// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation flag shared between the compute thread and the cancel caller (E27S02,
 * AC-COOPERATIVE-CANCELLATION-TESTED, DEC-49 D-11).
 *
 * <p>The compute loop checks {@link #isCancelled()} between permutations. The cancel caller invokes
 * {@link #cancel()} from a different thread. The {@link AtomicBoolean} backing field provides
 * correct visibility without synchronization overhead.
 *
 * @see CancelableInProcessSlotOptimizationService
 * @see <a href="../../../../../../../../docs/governance/stories/E27S02.story.md">Story E27S02</a>
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Package-private constructor — use {@link #create()}. */
    CancellationToken() {}

    /**
     * Creates a new, non-cancelled {@link CancellationToken}.
     *
     * @return a fresh token
     */
    public static CancellationToken create() {
        return new CancellationToken();
    }

    /**
     * Returns {@code true} if cancellation has been requested.
     *
     * <p>The compute loop checks this flag between permutations; the flag is set by {@link
     * #cancel()} which may be called from any thread.
     *
     * @return {@code true} if cancelled
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Requests cancellation. Idempotent. Thread-safe.
     *
     * <p>After this returns, the compute loop will observe {@link #isCancelled()} == {@code true}
     * on the next inter-permutation check.
     */
    public void cancel() {
        cancelled.set(true);
    }
}
