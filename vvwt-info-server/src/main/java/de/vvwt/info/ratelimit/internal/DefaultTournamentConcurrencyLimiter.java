package de.vvwt.info.ratelimit.internal;

import de.vvwt.info.ratelimit.TournamentConcurrencyLimiter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-tournament-token WebSocket concurrency limiter backed by a {@link ConcurrentHashMap} of
 * {@link AtomicInteger} counters (E38S07 AC3, AC10, AC13).
 *
 * <p>Maintains an {@link AtomicInteger} slot counter per tournament token. {@link
 * #tryAcquireSlot(String)} increments the counter if below the configured maximum and returns
 * {@code true}; {@link #releaseSlot(String)} decrements it (never below zero — spurious releases
 * are safe).
 *
 * <p>This class is thread-safe. The CAS loop in {@link #tryAcquireSlot(String)} ensures that the
 * limit is never exceeded under concurrent access.
 *
 * @see <a href="../../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC3,
 *     AC10, AC13</a>
 */
public class DefaultTournamentConcurrencyLimiter implements TournamentConcurrencyLimiter {

    private final int maxConcurrentWs;
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public DefaultTournamentConcurrencyLimiter(int maxConcurrentWs) {
        this.maxConcurrentWs = maxConcurrentWs;
    }

    /**
     * Attempts to acquire a slot for the given tournament token.
     *
     * @param tournamentToken the per-tournament-token identifier
     * @return {@code true} if a slot was acquired; {@code false} if the maximum is already reached
     */
    @Override
    public boolean tryAcquireSlot(String tournamentToken) {
        AtomicInteger counter =
                counters.computeIfAbsent(tournamentToken, k -> new AtomicInteger(0));
        int current;
        do {
            current = counter.get();
            if (current >= maxConcurrentWs) {
                return false;
            }
        } while (!counter.compareAndSet(current, current + 1));
        return true;
    }

    /**
     * Releases a slot for the given tournament token.
     *
     * <p>Safe to call after an abrupt connection close (AC13). Counter never goes below zero —
     * spurious releases on an empty counter are no-ops.
     *
     * @param tournamentToken the per-tournament-token identifier
     */
    @Override
    public void releaseSlot(String tournamentToken) {
        AtomicInteger counter = counters.get(tournamentToken);
        if (counter == null) {
            return; // never acquired — no-op
        }
        // Decrement, but never below 0
        int current;
        do {
            current = counter.get();
            if (current <= 0) {
                return;
            }
        } while (!counter.compareAndSet(current, current - 1));
    }

    /**
     * Returns the current slot count for the given tournament token (for observability).
     *
     * @param tournamentToken the tournament token
     * @return current concurrent connection count; 0 if none
     */
    @Override
    public int currentCount(String tournamentToken) {
        AtomicInteger counter = counters.get(tournamentToken);
        return counter == null ? 0 : counter.get();
    }
}
