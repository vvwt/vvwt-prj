// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal.internal;

import de.vvwt.tm.infoportal.InfoPortalOptInEvent;
import de.vvwt.tm.infoportal.InfoPortalOptInService;
import de.vvwt.tm.infoportal.InfoPortalProperties;
import de.vvwt.tm.infoportal.InfoPortalStateDao;
import de.vvwt.tm.infoportal.InfoPortalStateRecord;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Default implementation of {@link InfoPortalOptInService}.
 *
 * <p>When {@code info-portal.url} is not configured, returns {@code "DISABLED"} status and throws
 * on opt-in attempts.
 *
 * @since E62S02
 */
public class DefaultInfoPortalOptInService implements InfoPortalOptInService {

    private final InfoPortalStateDao stateDao;
    private final TeamRepository teamRepository;
    private final TournamentRepository tournamentRepository;
    private final InfoPortalProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public DefaultInfoPortalOptInService(
            InfoPortalStateDao stateDao,
            TeamRepository teamRepository,
            TournamentRepository tournamentRepository,
            InfoPortalProperties properties,
            ApplicationEventPublisher eventPublisher) {
        this.stateDao = stateDao;
        this.teamRepository = teamRepository;
        this.tournamentRepository = tournamentRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String getOptInStatus(UUID locationId, String tournamentId) {
        if (!properties.isEnabled()) {
            return "DISABLED";
        }
        Optional<InfoPortalStateRecord> record =
                stateDao.findByTournament(properties.getLocationId(), tournamentId);
        return record.map(InfoPortalStateRecord::registrationStatus).orElse("NOT_REGISTERED");
    }

    @Override
    public void optIn(UUID locationId, UUID tournamentId) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "Info-Portal feature not configured: info-portal.url is not set");
        }
        // AC4: idempotent guard — check if already registered
        Optional<InfoPortalStateRecord> existing =
                stateDao.findByTournament(properties.getLocationId(), tournamentId.toString());
        if (existing.isPresent() && "REGISTERED".equals(existing.get().registrationStatus())) {
            throw new IllegalStateException("Tournament is already registered with Info-Portal");
        }
        // AC6: teamless rejection
        var teams = teamRepository.findByTournamentId(tournamentId);
        if (teams.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot publish to Info-Portal: add teams before publishing");
        }
        // AC2/AC5: publish event for async after-commit processing
        eventPublisher.publishEvent(
                new InfoPortalOptInEvent(locationId, tournamentId, properties.getTenantId()));
    }
}
