// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import de.vvwt.tm.infoportal.internal.DefaultInfoPortalOptInListener;
import de.vvwt.tm.infoportal.internal.DefaultInfoPortalOptInService;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the per-tournament opt-in control (E62S02).
 *
 * <p>This configuration is not conditional — {@link InfoPortalOptInService} is always registered
 * and returns {@code "DISABLED"} when {@code info-portal.url} is not configured.
 *
 * <p>{@link InfoPortalOptInListener} is only registered when {@link InfoPortalPublisherService} is
 * present in the context (i.e., {@code info-portal.url} is configured).
 *
 * @since E62S02
 */
@Configuration
@EnableConfigurationProperties(InfoPortalProperties.class)
public class InfoPortalOptInConfig {

    @Bean
    public InfoPortalOptInService infoPortalOptInService(
            InfoPortalStateDao stateDao,
            TeamRepository teamRepository,
            TournamentRepository tournamentRepository,
            InfoPortalProperties properties,
            ApplicationEventPublisher eventPublisher) {
        return new DefaultInfoPortalOptInService(
                stateDao, teamRepository, tournamentRepository, properties, eventPublisher);
    }

    @Bean
    @ConditionalOnBean(InfoPortalPublisherService.class)
    public InfoPortalOptInListener infoPortalOptInListener(
            InfoPortalPublisherService publisherService,
            TeamRepository teamRepository,
            TournamentSnapshotBuilder snapshotBuilder,
            InfoPortalStateDao stateDao) {
        return new DefaultInfoPortalOptInListener(
                publisherService, teamRepository, snapshotBuilder, stateDao);
    }
}
