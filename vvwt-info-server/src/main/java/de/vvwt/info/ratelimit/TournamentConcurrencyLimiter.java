package de.vvwt.info.ratelimit;

/**
 * Per-tournament-token WebSocket concurrency limiter (E38S07 AC3, AC10, AC13).
 *
 * <p>Attempts to acquire or release a concurrency slot per tournament token. Implementations must
 * be thread-safe.
 *
 * @see de.vvwt.info.ratelimit.internal.DefaultTournamentConcurrencyLimiter
 * @see <a href="../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC3, AC10,
 *     AC13</a>
 */
public interface TournamentConcurrencyLimiter {

    /**
     * Attempts to acquire a slot for the given tournament token.
     *
     * @param tournamentToken the per-tournament-token identifier
     * @return {@code true} if a slot was acquired; {@code false} if the maximum is already reached
     */
    boolean tryAcquireSlot(String tournamentToken);

    /**
     * Releases a slot for the given tournament token.
     *
     * <p>Safe to call after an abrupt connection close (AC13). Counter never goes below zero.
     *
     * @param tournamentToken the per-tournament-token identifier
     */
    void releaseSlot(String tournamentToken);

    /**
     * Returns the current slot count for the given tournament token (for observability).
     *
     * @param tournamentToken the tournament token
     * @return current concurrent connection count; 0 if none
     */
    int currentCount(String tournamentToken);
}
