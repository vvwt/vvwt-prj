// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.infoportal.InfoPortalOptInService;
import de.vvwt.tm.infoportal.InfoPortalProperties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration tests for {@link InfoPortalOptInController} (E62S02 AC7 — auth gate).
 *
 * <p>DEC-22 Iron Law: authored RED-first before production controller exists.
 *
 * <p>DEC-44 D1: {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)}.
 *
 * @since E62S02
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e62s02optinit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.main.allow-bean-definition-overriding=true"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, InfoPortalOptInControllerIT.TestAdminCredentials.class})
@DisplayName("InfoPortalOptInController IT — E62S02 (AC7 auth + AC8 disabled state)")
class InfoPortalOptInControllerIT {

    static final String TEST_PASSWORD = "InfoPortalOptInIT62S02";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    /** MockitoBean replaces the real InfoPortalOptInService in the application context. */
    @MockitoBean private InfoPortalOptInService optInService;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        UUID tournamentId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        baseUrl = "http://localhost:" + port + "/api/tournaments/" + tournamentId + "/info-portal";
        // Reset mock and configure default behaviour
        Mockito.reset(optInService);
        Mockito.when(optInService.getOptInStatus(Mockito.any(), Mockito.anyString()))
                .thenReturn("DISABLED");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // AC7 — unauthenticated request → 401
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC7: GET /api/tournaments/{id}/info-portal unauthenticated → 401")
    void getStatus_unauthenticated_returns401() {
        // No credentials — apiFetch without basic auth
        ResponseEntity<String> response =
                new TestRestTemplate().getForEntity(baseUrl, String.class);

        assertThat(response.getStatusCode())
                .as("Unauthenticated GET must return 401 (AC7)")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC7: POST /api/tournaments/{id}/info-portal unauthenticated → 401")
    void postOptIn_unauthenticated_returns401() {
        ResponseEntity<String> response =
                new TestRestTemplate()
                        .exchange(baseUrl, HttpMethod.POST, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode())
                .as("Unauthenticated POST must return 401 (AC7)")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // AC8 — authenticated + DISABLED state
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC8: GET /api/tournaments/{id}/info-portal authenticated returns status JSON")
    void getStatus_authenticated_returnsStatusJson() {
        TestRestTemplate authed = restTemplate.withBasicAuth("admin", TEST_PASSWORD);

        ResponseEntity<String> response = authed.getForEntity(baseUrl, String.class);

        assertThat(response.getStatusCode())
                .as("Authenticated GET must return 200 (AC8)")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("Response body must contain status field")
                .contains("status");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test infrastructure
    // ─────────────────────────────────────────────────────────────────────────────

    @TestConfiguration
    static class TestAdminCredentials {

        private final PasswordEncoder encoder;

        TestAdminCredentials(PasswordEncoder encoder) {
            this.encoder = encoder;
        }

        @Bean
        @Primary
        public AdminCredentialsProvider testAdminCredentialsProvider() {
            return () -> encoder.encode(TEST_PASSWORD);
        }

        /** Properties with url not set (DISABLED state). */
        @Bean
        @Primary
        public InfoPortalProperties infoPortalProperties() {
            return new InfoPortalProperties();
        }
    }
}
