// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
