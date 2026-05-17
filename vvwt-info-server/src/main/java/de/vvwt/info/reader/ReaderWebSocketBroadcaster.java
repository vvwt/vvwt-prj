// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.reader;

import de.vvwt.info.reader.internal.TournamentDeltaPublishedEvent;
import org.springframework.context.event.EventListener;

/**
 * Broadcasts delta frames to all subscribed WebSocket sessions (E38S06 AC2).
 *
 * @see de.vvwt.info.reader.internal.DefaultReaderWebSocketBroadcaster
 * @see <a href="../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC2</a>
 */
public interface ReaderWebSocketBroadcaster {

    /**
     * Handles a {@link TournamentDeltaPublishedEvent}: serialises the delta as {@code
     * Envelope<DomainEvent>} and sends it to all subscribed sessions.
     *
     * @param event the published delta event
     */
    @EventListener
    void onDeltaPublished(TournamentDeltaPublishedEvent event);
}
