// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.events;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Spring {@link ApplicationEvent} published when a partial (in-progress) score is recorded for a
 * match during active play (E65S02, AC2, AC4).
 *
 * <p>Published by {@code DefaultScoreEntryService.handlePartialScore()} after the OPEN {@code
 * set_result} row is persisted. Listened to by {@code DomainEventBridge}, which broadcasts a {@code
 * PARTIAL_SCORE_UPDATED} WebSocket event to the tenant-scoped Display topic {@code
 * /topic/display/{tenantId}/events}.
 *
 * <p>Tenant scoping: the {@code tenantId} field is taken from {@code TenantContext.current()} at
 * publication time and is used by the bridge to route the event to the correct Display clients only
 * (AC4, DEC-20).
 *
 * <h2>MUST NOT carry sensitive payloads</h2>
 *
 * <p>This event MUST NOT embed credentials, session tokens, or raw SQL.
 *
 * @since E65S02
 */
public class PartialScoreUpdatedEvent extends ApplicationEvent {

    private final UUID tenantId;
    private final UUID matchId;

    /**
     * Constructs a {@code PartialScoreUpdatedEvent}.
     *
     * @param source the object on which the event initially occurred (must not be {@code null})
     * @param tenantId the tenant in whose context the partial score was recorded (DEC-20)
     * @param matchId the match for which the partial score was updated
     */
    public PartialScoreUpdatedEvent(Object source, UUID tenantId, UUID matchId) {
        super(source);
        this.tenantId = tenantId;
        this.matchId = matchId;
    }

    /**
     * @return the tenant in whose context the partial score was recorded
     */
    public UUID getTenantId() {
        return tenantId;
    }

    /**
     * @return the match for which the partial score was updated
     */
    public UUID getMatchId() {
        return matchId;
    }
}
