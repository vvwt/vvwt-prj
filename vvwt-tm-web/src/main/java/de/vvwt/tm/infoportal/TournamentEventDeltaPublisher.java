// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import de.vvwt.tm.tournament.events.DeviceRegisteredEvent;
import de.vvwt.tm.tournament.events.LapAdvancedEvent;
import de.vvwt.tm.tournament.events.MatchResultChangedEvent;
import de.vvwt.tm.tournament.events.PhaseStatusChangedEvent;

/**
 * Async after-commit event listener that bridges TM tournament-domain events to Info-Portal delta
 * publications (E62S03, AC2–AC6, DEC-21, DEC-58).
 *
 * <p>Subscribes to the {@code de.vvwt.tm.tournament.events} event set via the {@code
 * tournament::events} {@code allowedDependencies} edge declared in {@code package-info.java}
 * (E62S01, AC7).
 *
 * <p>Only participant-relevant events produce a delta publish via {@link
 * InfoPortalPublisherService#publishDelta}. Non-participant-relevant events ({@link
 * DeviceRegisteredEvent}, {@link PhaseStatusChangedEvent}) are accepted by the listener but produce
 * no publish call (AC3). Events for a non-opted-in tournament also produce no publish (AC3 a).
 *
 * <p>Publication is best-effort and asynchronous — failures must not propagate into the TM
 * tournament workflow (AC5). The async isolation is provided by {@code @ApplicationModuleListener}
 * (Spring Modulith's {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async} under the
 * hood).
 *
 * <h2>In/out partition of the event set (AC3 b)</h2>
 *
 * <ul>
 *   <li>Participant-relevant → publish delta: {@link MatchResultChangedEvent}, {@link
 *       LapAdvancedEvent}
 *   <li>Not participant-relevant → no publish: {@link DeviceRegisteredEvent}, {@link
 *       PhaseStatusChangedEvent}
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-58 Clause A + DEC-72: public interface in bounded-context root package; implementation
 *       in {@code de.vvwt.tm.infoportal.internal.DefaultTournamentEventDeltaPublisher}.
 *   <li>DEC-6 / DEC-43 (AC6): signing is delegated entirely to {@link InfoPortalPublisherService};
 *       this interface does not bypass or re-implement signing.
 *   <li>DEC-21 (AC7): consumes {@code tournament::events} via declared {@code allowedDependencies}
 *       edge.
 *   <li>DEC-22: every new production class authored RED-first.
 * </ul>
 *
 * @see de.vvwt.tm.infoportal.internal.DefaultTournamentEventDeltaPublisher
 * @since E62S03
 */
public interface TournamentEventDeltaPublisher {

    /**
     * Handles a {@link MatchResultChangedEvent}.
     *
     * <p>If the tournament is opted in, emits a {@code ScoreUpdated} delta (and additionally a
     * {@code MatchResultFinalized} delta when {@code event.getNewState()} is terminal).
     *
     * @param event the match result changed event
     */
    void onMatchResultChanged(MatchResultChangedEvent event);

    /**
     * Handles a {@link LapAdvancedEvent}.
     *
     * <p>If the tournament is opted in, emits a {@code RoundCompleted} delta.
     *
     * @param event the lap advanced event
     */
    void onLapAdvanced(LapAdvancedEvent event);

    /**
     * Handles a {@link PhaseStatusChangedEvent}.
     *
     * <p>Phase status changes have no participant-relevant {@code DomainEvent} subtype — no delta
     * is published (AC3 b).
     *
     * @param event the phase status changed event
     */
    void onPhaseStatusChanged(PhaseStatusChangedEvent event);

    /**
     * Handles a {@link DeviceRegisteredEvent}.
     *
     * <p>Device-registration is TM-internal with no participant-facing meaning — no delta is
     * published (AC3 b).
     *
     * @param event the device registered event
     */
    void onDeviceRegistered(DeviceRegisteredEvent event);
}
