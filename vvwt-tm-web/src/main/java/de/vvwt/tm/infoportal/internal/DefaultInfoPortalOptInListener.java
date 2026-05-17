// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.infoportal.InfoPortalOptInEvent;
import de.vvwt.tm.infoportal.InfoPortalOptInListener;
import de.vvwt.tm.infoportal.InfoPortalPublisherService;
import de.vvwt.tm.infoportal.InfoPortalStateDao;
import de.vvwt.tm.infoportal.TournamentSnapshotBuilder;
import de.vvwt.tm.tournament.TeamRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Default implementation of {@link InfoPortalOptInListener}.
 *
 * <p>Processes {@link InfoPortalOptInEvent} after the originating transaction commits (AC5). Runs
 * the full registration chain asynchronously: registerTenant → registerTournament → postSnapshot.
 *
 * <p>Uses {@code CompletableFuture.runAsync()} since {@code @EnableAsync} is not active in this
 * application context.
 *
 * @since E62S02
 */
public class DefaultInfoPortalOptInListener implements InfoPortalOptInListener {

    private static final Logger log = LoggerFactory.getLogger(DefaultInfoPortalOptInListener.class);

    private final InfoPortalPublisherService publisherService;
    private final TeamRepository teamRepository;
    private final TournamentSnapshotBuilder snapshotBuilder;
    private final InfoPortalStateDao stateDao;
    private final ObjectMapper objectMapper;

    public DefaultInfoPortalOptInListener(
            InfoPortalPublisherService publisherService,
            TeamRepository teamRepository,
            TournamentSnapshotBuilder snapshotBuilder,
            InfoPortalStateDao stateDao) {
        this.publisherService = publisherService;
        this.teamRepository = teamRepository;
        this.snapshotBuilder = snapshotBuilder;
        this.stateDao = stateDao;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOptIn(InfoPortalOptInEvent event) {
        CompletableFuture.runAsync(() -> runRegistrationChain(event));
    }

    private void runRegistrationChain(InfoPortalOptInEvent event) {
        UUID tournamentId = event.tournamentId();
        String tenantId = event.tenantId();
        try {
            // Step 1: register tenant (idempotent by publisher impl)
            publisherService.registerTenant();

            // Step 2: collect team UUIDs and register tournament
            List<UUID> teamUuids =
                    teamRepository.findByTournamentId(tournamentId).stream()
                            .map(t -> t.getId())
                            .collect(Collectors.toList());
            publisherService.registerTournament(tournamentId.toString(), teamUuids);

            // Step 3: build snapshot and post initial snapshot
            var snapshot = snapshotBuilder.build(tournamentId, tenantId, 0L);
            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            publisherService.postSnapshot(tournamentId.toString(), snapshotJson);

            log.info("Info-Portal registration chain completed for tournament {}", tournamentId);
        } catch (JsonProcessingException e) {
            log.error(
                    "Failed to serialize snapshot for tournament {}: {}",
                    tournamentId,
                    e.getMessage());
        } catch (Exception e) {
            log.error(
                    "Info-Portal registration chain failed for tournament {}: {}",
                    tournamentId,
                    e.getMessage(),
                    e);
        }
    }
}
