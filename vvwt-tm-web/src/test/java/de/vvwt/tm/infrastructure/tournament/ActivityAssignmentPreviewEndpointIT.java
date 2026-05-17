// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infrastructure.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityAssignmentPreviewResponse;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Minimalist integration tests for {@link ActivityAssignmentPreviewController} (E20S02, AC4 —
 * Approach C).
 *
 * <h2>Approach C minimalist-IT (AC4)</h2>
 *
 * <p>Exactly 2 {@code @Test} methods per controller:
 *
 * <ol>
 *   <li>Happy-path authenticated GET → 200 with empty preview (no activity types / no phase)
 *   <li>Unauthenticated request → 401 (AC9)
 * </ol>
 *
 * <p>Slice tests ({@link ActivityAssignmentPreviewControllerTest}) cover all other scenarios.
 *
 * @see ActivityAssignmentPreviewController
 * @see ActivityAssignmentPreviewControllerTest
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            ActivityAssignmentPreviewEndpointIT.TestAdminCredentials.class
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("ActivityAssignmentPreviewController IT — E20S02 AC4 Approach C (2-test minimalist)")
class ActivityAssignmentPreviewEndpointIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E20S02PreviewIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private TournamentRepository tournamentRepository;

    private String baseUrl;
    private TestRestTemplate authed;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        tenantBinder.bindDefaultTenant();
        UUID defaultLocationId = tenantBinder.getDefaultLocationId();

        tournamentId = UUID.randomUUID();
        Tournament t =
                new Tournament(
                        tournamentId,
                        "Preview IT Tournament " + tournamentId,
                        MatchFormat.BEST_OF_3.name(),
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        "DRAFT",
                        LocalDateTime.now());
        // E45S06: location_id NOT NULL (DEC-39 D2)
        t.setLocationId(defaultLocationId);
        tournamentRepository.save(t);

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        // no-op — isolated in-memory H2 per TenantContextTestSupport
    }

    // =========================================================================
    // AC4 — authenticated GET → 200 empty preview (AC6 — no phase)
    // =========================================================================

    @Test
    @DisplayName("AC4/AC6: authenticated GET returns 200 empty preview when no phase configured")
    void authenticatedGetReturnsEmptyPreviewWhenNoPhase() throws Exception {
        ResponseEntity<ActivityAssignmentPreviewResponse> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/api/tournaments/"
                                        + tournamentId
                                        + "/activity-assignments"),
                        ActivityAssignmentPreviewResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4: GET preview must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().assignments())
                .as("AC6: assignments must be empty when no phase is prepared")
                .isEmpty();
        assertThat(response.getBody().unassigned())
                .as("AC6: unassigned must be empty when no phase is prepared")
                .isEmpty();
    }

    // =========================================================================
    // AC9 — unauthenticated request → 401
    // =========================================================================

    @Test
    @DisplayName("AC9: unauthenticated GET returns 401")
    void unauthenticatedGetReturns401() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(
                                baseUrl
                                        + "/api/tournaments/"
                                        + tournamentId
                                        + "/activity-assignments"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC9: unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Test-local AdminCredentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
