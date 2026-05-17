// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.infoportal.internal.DefaultInfoPortalOptInService;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link InfoPortalOptInService} (E62S02 AC1, AC2, AC4, AC5, AC6).
 *
 * <p>DEC-22 Iron Law: tests authored RED-first before production class exists.
 *
 * <p>Same package as subject — white-box testing per DEC-36 permitted.
 *
 * @since E62S02
 */
class InfoPortalOptInServiceTest {

    private InfoPortalStateDao stateDao;
    private TeamRepository teamRepository;
    private TournamentRepository tournamentRepository;
    private InfoPortalProperties properties;
    private ApplicationEventPublisher eventPublisher;
    private InfoPortalOptInService service;

    @BeforeEach
    void setUp() {
        stateDao = mock(InfoPortalStateDao.class);
        teamRepository = mock(TeamRepository.class);
        tournamentRepository = mock(TournamentRepository.class);
        properties = new InfoPortalProperties();
        eventPublisher = mock(ApplicationEventPublisher.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // AC8 — disabled state when info-portal.url is not configured
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC8: getOptInStatus returns DISABLED when info-portal url not configured")
    void getOptInStatus_returnsDisabled_whenUrlNotConfigured() {
        // Arrange: url is null (default)
        properties.setUrl(null);
        service =
                new DefaultInfoPortalOptInService(
                        stateDao, teamRepository, tournamentRepository, properties, eventPublisher);
        UUID locationId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();

        // Act
        String status = service.getOptInStatus(locationId, tournamentId.toString());

        // Assert
        assertThat(status).isEqualTo("DISABLED");
        verify(stateDao, never()).findByTournament(any(), any());
    }

    @Test
    @DisplayName("AC8: optIn throws when info-portal url not configured")
    void optIn_throws_whenUrlNotConfigured() {
        // Arrange
        properties.setUrl(null);
        service =
                new DefaultInfoPortalOptInService(
                        stateDao, teamRepository, tournamentRepository, properties, eventPublisher);
        UUID locationId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();

        // Act + Assert
        assertThatThrownBy(() -> service.optIn(locationId, tournamentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // AC4 — idempotent: already registered returns REGISTERED, no re-registration
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC4: getOptInStatus returns REGISTERED when state row exists")
    void getOptInStatus_returnsRegistered_whenStateRowExists() {
        // Arrange
        properties.setUrl("http://info-server.example.com");
        properties.setLocationId("loc1");
        properties.setTenantId("tenant1");
        service =
                new DefaultInfoPortalOptInService(
                        stateDao, teamRepository, tournamentRepository, properties, eventPublisher);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String tournamentId = "tid1";

        InfoPortalStateRecord record =
                new InfoPortalStateRecord(
                        "loc1", tournamentId, 0L, "token", new byte[32], null, "REGISTERED");
        when(stateDao.findByTournament("loc1", tournamentId)).thenReturn(Optional.of(record));

        // Act
        String status = service.getOptInStatus(locationId, tournamentId);

        // Assert
        assertThat(status).isEqualTo("REGISTERED");
    }

    @Test
    @DisplayName("AC4: optIn throws when already REGISTERED (idempotent guard)")
    void optIn_throws_whenAlreadyRegistered() {
        // Arrange
        properties.setUrl("http://info-server.example.com");
        properties.setLocationId("loc1");
        properties.setTenantId("tenant1");
        service =
                new DefaultInfoPortalOptInService(
                        stateDao, teamRepository, tournamentRepository, properties, eventPublisher);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tournamentId = UUID.randomUUID();

        InfoPortalStateRecord record =
                new InfoPortalStateRecord(
                        "loc1",
                        tournamentId.toString(),
                        0L,
                        "token",
                        new byte[32],
                        null,
                        "REGISTERED");
        when(stateDao.findByTournament("loc1", tournamentId.toString()))
                .thenReturn(Optional.of(record));
        when(teamRepository.findByTournamentId(tournamentId))
                .thenReturn(List.of(makeTeam(tournamentId)));

        // Act + Assert
        assertThatThrownBy(() -> service.optIn(locationId, tournamentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // AC6 — teamless tournament rejection
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC6: optIn throws with actionable message when tournament has no teams")
    void optIn_throws_whenNoTeams() {
        // Arrange
        properties.setUrl("http://info-server.example.com");
        properties.setLocationId("loc1");
        properties.setTenantId("tenant1");
        service =
                new DefaultInfoPortalOptInService(
                        stateDao, teamRepository, tournamentRepository, properties, eventPublisher);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tournamentId = UUID.randomUUID();

        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(Collections.emptyList());
        when(stateDao.findByTournament("loc1", tournamentId.toString()))
                .thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.optIn(locationId, tournamentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("add teams before publishing");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // AC2 / AC5 — successful opt-in triggers async event publishing
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC2/AC5: optIn publishes InfoPortalOptInEvent after state checks pass")
    void optIn_publishesEvent_whenValid() {
        // Arrange
        properties.setUrl("http://info-server.example.com");
        properties.setLocationId("loc1");
        properties.setTenantId("tenant1");
        service =
                new DefaultInfoPortalOptInService(
                        stateDao, teamRepository, tournamentRepository, properties, eventPublisher);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tournamentId = UUID.randomUUID();

        when(stateDao.findByTournament("loc1", tournamentId.toString()))
                .thenReturn(Optional.empty());
        when(teamRepository.findByTournamentId(tournamentId))
                .thenReturn(List.of(makeTeam(tournamentId)));

        // Act
        service.optIn(locationId, tournamentId);

        // Assert: event was published (async trigger; actual registration is in listener)
        verify(eventPublisher).publishEvent(any(InfoPortalOptInEvent.class));
    }

    @Test
    @DisplayName("AC5: getOptInStatus returns NOT_REGISTERED when no state row exists")
    void getOptInStatus_returnsNotRegistered_whenNoStateRow() {
        // Arrange
        properties.setUrl("http://info-server.example.com");
        properties.setLocationId("loc1");
        properties.setTenantId("tenant1");
        service =
                new DefaultInfoPortalOptInService(
                        stateDao, teamRepository, tournamentRepository, properties, eventPublisher);
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String tournamentId = "tid-absent";

        when(stateDao.findByTournament("loc1", tournamentId)).thenReturn(Optional.empty());

        // Act
        String status = service.getOptInStatus(locationId, tournamentId);

        // Assert
        assertThat(status).isEqualTo("NOT_REGISTERED");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static Team makeTeam(UUID tournamentId) {
        Team t = new Team();
        t.setId(UUID.randomUUID());
        t.setTournamentId(tournamentId);
        t.setTeamNumber(1);
        t.setDescription("Team 1");
        t.setParticipate(true);
        return t;
    }
}
