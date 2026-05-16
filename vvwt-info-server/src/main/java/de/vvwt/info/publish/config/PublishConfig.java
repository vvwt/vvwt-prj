// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.publish.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.crypto.SignatureVerifierRegistry;
import de.vvwt.info.persistence.audit.AuditLogDao;
import de.vvwt.info.persistence.tenant.TenantDao;
import de.vvwt.info.persistence.tournament.TournamentDao;
import de.vvwt.info.persistence.tournament.TournamentDeltaDao;
import de.vvwt.info.publish.JcsCanonicalizer;
import de.vvwt.info.publish.PublishController;
import de.vvwt.info.publish.PublishService;
import de.vvwt.info.publish.internal.DefaultJcsCanonicalizer;
import de.vvwt.info.publish.internal.DefaultPublishService;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the publisher subsystem (E38S05).
 *
 * <p>Wires:
 *
 * <ul>
 *   <li>{@link JcsCanonicalizer} — RFC 8785 JCS canonical payload (AC8)
 *   <li>{@link PublishService} — core business logic for tournament-registration + delta/snapshot
 *   <li>{@link PublishController} — REST endpoints (AC7)
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05</a>
 */
@Configuration
@EnableConfigurationProperties(PublishProperties.class)
public class PublishConfig {

    @Bean
    public JcsCanonicalizer jcsCanonicalizer() {
        return new DefaultJcsCanonicalizer();
    }

    @Bean
    public PublishService publishService(
            TenantDao tenantDao,
            TournamentDao tournamentDao,
            TournamentDeltaDao tournamentDeltaDao,
            AuditLogDao auditLogDao,
            SignatureVerifierRegistry signatureVerifierRegistry,
            JcsCanonicalizer jcsCanonicalizer,
            PublishProperties publishProperties,
            Clock clock) {
        return new DefaultPublishService(
                tenantDao,
                tournamentDao,
                tournamentDeltaDao,
                auditLogDao,
                signatureVerifierRegistry,
                jcsCanonicalizer,
                publishProperties,
                clock);
    }

    @Bean
    public PublishController publishController(
            PublishService publishService, ObjectMapper objectMapper) {
        return new PublishController(publishService, objectMapper);
    }
}
