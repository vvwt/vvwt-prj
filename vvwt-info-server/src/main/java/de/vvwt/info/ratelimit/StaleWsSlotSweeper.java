package de.vvwt.info.ratelimit;

/**
 * Scheduled sweeper that releases stale per-tournament-token concurrency slots (E38S07 AC13).
 *
 * @see de.vvwt.info.ratelimit.internal.DefaultStaleWsSlotSweeper
 * @see <a href="../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC13</a>
 */
public interface StaleWsSlotSweeper {

    /** Releases slots for any WS sessions that are no longer open. */
    void sweepStaleSlots();
}
