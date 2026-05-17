// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.event.DomainEvent;
import de.vvwt.tm.infoportal.InfoPortalProperties;
import de.vvwt.tm.infoportal.InfoPortalPublisherService;
import de.vvwt.tm.infoportal.InfoPortalStateDao;
import de.vvwt.tm.infoportal.InfoPortalStateRecord;
import de.vvwt.tm.infoportal.TournamentEventDeltaPublisher;
import de.vvwt.tm.infoportal.TournamentSnapshotBuilder;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.SetResult;
import de.vvwt.tm.tournament.SetResultRepository;
import de.vvwt.tm.tournament.SetState;
import de.vvwt.tm.tournament.events.DeviceRegisteredEvent;
import de.vvwt.tm.tournament.events.LapAdvancedEvent;
import de.vvwt.tm.tournament.events.MatchResultChangedEvent;
import de.vvwt.tm.tournament.events.PhaseStatusChangedEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Default implementation of {@link TournamentEventDeltaPublisher}.
 *
 * <p>Subscribes to {@code de.vvwt.tm.tournament.events} domain events via {@link
 * ApplicationModuleListener} (Spring Modulith's {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Async} — async isolation ensures the tournament workflow is never blocked by Info-Portal
 * failures, per AC5).
 *
 * <p>Bean registration is via {@code InfoPortalOptInConfig#tournamentEventDeltaPublisher()} —
 * conditional on {@link InfoPortalPublisherService} being present (i.e., {@code info-portal.url}
 * configured). When the publisher is absent (feature disabled), no listener bean is created and no
 * events are processed.
 *
 * <p>This class carries no {@code @Component} annotation — DEC-70: no test-only or duplicate
 * wiring.
 *
 * @see TournamentEventDeltaPublisher
 * @see de.vvwt.tm.infoportal.InfoPortalOptInConfig
 * @since E62S03
 */
public class DefaultTournamentEventDeltaPublisher implements TournamentEventDeltaPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultTournamentEventDeltaPublisher.class);

    private final InfoPortalPublisherService publisherService;
    private final InfoPortalStateDao stateDao;
    private final TournamentSnapshotBuilder snapshotBuilder;
    private final SetResultRepository setResultRepository;
    private final InfoPortalProperties properties;
    private final ObjectMapper objectMapper;

    public DefaultTournamentEventDeltaPublisher(
            InfoPortalPublisherService publisherService,
            InfoPortalStateDao stateDao,
            TournamentSnapshotBuilder snapshotBuilder,
            SetResultRepository setResultRepository,
            InfoPortalProperties properties,
            ObjectMapper objectMapper) {
        this.publisherService = publisherService;
        this.stateDao = stateDao;
        this.snapshotBuilder = snapshotBuilder;
        this.setResultRepository = setResultRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Participant-relevant event: MatchResultChangedEvent
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    @ApplicationModuleListener
    public void onMatchResultChanged(MatchResultChangedEvent event) {
        UUID tournamentId = event.getTournamentId();
        if (!isOptedIn(tournamentId)) {
            return;
        }
        try {
            UUID matchId = event.getMatchId();
            List<SetResult> sets = setResultRepository.findByMatchId(matchId);
            int homeScore = countWins(sets, SetState.WINNER1);
            int awayScore = countWins(sets, SetState.WINNER2);

            // Always emit ScoreUpdated
            publishEvent(
                    tournamentId,
                    new DomainEvent.ScoreUpdated(matchId.toString(), homeScore, awayScore));

            // Also emit MatchResultFinalized when the match is terminal
            MatchState newState = event.getNewState();
            if (isTerminal(newState)) {
                publishEvent(
                        tournamentId,
                        new DomainEvent.MatchResultFinalized(
                                matchId.toString(), homeScore, awayScore));
            }
        } catch (Exception e) {
            log.error(
                    "[InfoPortal] Error processing MatchResultChangedEvent for tournament '{}': {}",
                    tournamentId,
                    e.getMessage(),
                    e);
        }
    }

    // -------------------------------------------------------------------------
    // Participant-relevant event: LapAdvancedEvent
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    @ApplicationModuleListener
    public void onLapAdvanced(LapAdvancedEvent event) {
        UUID tournamentId = event.getTournamentId();
        if (!isOptedIn(tournamentId)) {
            return;
        }
        try {
            publishEvent(tournamentId, new DomainEvent.RoundCompleted(event.getNewLapNumber()));
        } catch (Exception e) {
            log.error(
                    "[InfoPortal] Error processing LapAdvancedEvent for tournament '{}': {}",
                    tournamentId,
                    e.getMessage(),
                    e);
        }
    }

    // -------------------------------------------------------------------------
    // Non-participant-relevant: PhaseStatusChangedEvent — no publish (AC3 b)
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    @ApplicationModuleListener
    public void onPhaseStatusChanged(PhaseStatusChangedEvent event) {
        // Phase status changes have no participant-relevant DomainEvent subtype (AC3 b).
        log.debug(
                "[InfoPortal] PhaseStatusChangedEvent for tournament '{}' — no delta published"
                        + " (not participant-relevant)",
                event.getTournamentId());
    }

    // -------------------------------------------------------------------------
    // Non-participant-relevant: DeviceRegisteredEvent — no publish (AC3 b)
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    @ApplicationModuleListener
    public void onDeviceRegistered(DeviceRegisteredEvent event) {
        // Device registration is TM-internal with no participant-facing meaning (AC3 b).
        log.debug(
                "[InfoPortal] DeviceRegisteredEvent for device '{}' — no delta published"
                        + " (TM-internal)",
                event.getDeviceId());
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when a row with {@code registration_status = 'REGISTERED'} exists for
     * the given tournament (opt-in gate, AC2 / AC3 a).
     */
    private boolean isOptedIn(UUID tournamentId) {
        Optional<InfoPortalStateRecord> state =
                stateDao.findByTournament(properties.getLocationId(), tournamentId.toString());
        return state.filter(r -> "REGISTERED".equals(r.registrationStatus())).isPresent();
    }

    /**
     * Serializes the given {@link DomainEvent} to JSON and delegates to {@link
     * InfoPortalPublisherService#publishDelta} (AC6 — existing signed path, no new signing code).
     */
    private void publishEvent(UUID tournamentId, DomainEvent domainEvent) {
        try {
            String eventJson = objectMapper.writeValueAsString(domainEvent);
            publisherService.publishDelta(tournamentId.toString(), eventJson);
        } catch (JsonProcessingException e) {
            log.error(
                    "[InfoPortal] Failed to serialize DomainEvent for tournament '{}': {}",
                    tournamentId,
                    e.getMessage());
        }
        // RuntimeException from publishDelta (5xx, network error) propagates up to the
        // caller (onMatchResultChanged / onLapAdvanced) which catches and logs it (AC5).
    }

    /**
     * Counts the number of non-cancelled set results won by the given side (WINNER1 = home, WINNER2
     * = away).
     */
    private int countWins(List<SetResult> sets, SetState winner) {
        return (int) sets.stream().filter(s -> s.getSetState() == winner).count();
    }

    /**
     * Returns {@code true} when {@code matchState} is one of the three terminal states ({@code
     * FINISHED_WINNER1}, {@code FINISHED_WINNER2}, {@code FINISHED_STANDOFF}).
     */
    private boolean isTerminal(MatchState matchState) {
        return matchState == MatchState.FINISHED_WINNER1
                || matchState == MatchState.FINISHED_WINNER2
                || matchState == MatchState.FINISHED_STANDOFF;
    }
}
