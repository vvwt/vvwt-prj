// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
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
 * Integration tests for crop configuration fields exposed by {@link SettingsController} via {@code
 * GET /api/settings} (E71S01 AC3).
 *
 * <p>DEC-22 Q-1a: authored RED-first before crop fields were added to {@code SettingsController}
 * and {@code SettingsResponse}. Verifies:
 *
 * <ul>
 *   <li>AC3a: {@code cropAspectRatioWidth} is present with default value 11
 *   <li>AC3b: {@code cropAspectRatioHeight} is present with default value 5
 *   <li>AC3c: {@code cropMaxLongEdge} is present with default value 2200
 * </ul>
 *
 * <p>DEC-44 D1 — {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)}
 * is the web-module IT canon for {@code @SpringBootTest}.<br>
 * DEC-44 D2 — {@code @Import({WebModuleTestConfig.class, TestAdminCredentials.class})}; single
 * {@code @Primary AdminCredentialsProvider} via inner {@code TestAdminCredentials}.
 *
 * @see SettingsController
 * @see WebModuleTestConfig
 * @since E71S01
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e71s01settingscropitdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.main.allow-bean-definition-overriding=true"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, SettingsCropConfigIT.TestAdminCredentials.class})
@DisplayName("SettingsController — crop config fields (E71S01 AC3)")
class SettingsCropConfigIT {

    static final String TEST_PASSWORD = "SettingsCropConfigIT-E71S01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
    }

    // =========================================================================
    // Helper — authenticated GET /api/settings → SettingsResponse
    // =========================================================================

    private ResponseEntity<SettingsController.SettingsResponse> getSettings() {
        return restTemplate
                .withBasicAuth("admin", TEST_PASSWORD)
                .getForEntity(base + "/api/settings", SettingsController.SettingsResponse.class);
    }

    // =========================================================================
    // AC3a — cropAspectRatioWidth default = 11
    // =========================================================================

    @Test
    @DisplayName("AC3a: GET /api/settings returns cropAspectRatioWidth=11 by default")
    void settingsReturnsCropAspectRatioWidthDefault() {
        ResponseEntity<SettingsController.SettingsResponse> response = getSettings();

        assertThat(response.getStatusCode())
                .as("GET /api/settings must return HTTP 200")
                .isEqualTo(HttpStatus.OK);

        assertThat(response.getBody()).as("Response body must not be null").isNotNull();
        assertThat(response.getBody().cropAspectRatioWidth())
                .as("cropAspectRatioWidth must equal 11 (default, E71S01 AC3)")
                .isEqualTo(11);
    }

    // =========================================================================
    // AC3b — cropAspectRatioHeight default = 5
    // =========================================================================

    @Test
    @DisplayName("AC3b: GET /api/settings returns cropAspectRatioHeight=5 by default")
    void settingsReturnsCropAspectRatioHeightDefault() {
        ResponseEntity<SettingsController.SettingsResponse> response = getSettings();

        assertThat(response.getStatusCode())
                .as("GET /api/settings must return HTTP 200")
                .isEqualTo(HttpStatus.OK);

        assertThat(response.getBody()).as("Response body must not be null").isNotNull();
        assertThat(response.getBody().cropAspectRatioHeight())
                .as("cropAspectRatioHeight must equal 5 (default, E71S01 AC3)")
                .isEqualTo(5);
    }

    // =========================================================================
    // AC3c — cropMaxLongEdge default = 2200
    // =========================================================================

    @Test
    @DisplayName("AC3c: GET /api/settings returns cropMaxLongEdge=2200 by default")
    void settingsReturnsCropMaxLongEdgeDefault() {
        ResponseEntity<SettingsController.SettingsResponse> response = getSettings();

        assertThat(response.getStatusCode())
                .as("GET /api/settings must return HTTP 200")
                .isEqualTo(HttpStatus.OK);

        assertThat(response.getBody()).as("Response body must not be null").isNotNull();
        assertThat(response.getBody().cropMaxLongEdge())
                .as("cropMaxLongEdge must equal 2200 (default, E71S01 AC3)")
                .isEqualTo(2200);
    }

    // =========================================================================
    // TestAdminCredentials — per DEC-44 D2 pattern
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Autowired private PasswordEncoder passwordEncoder;

        @Bean("testAdminCredentialsProvider")
        @Primary
        public AdminCredentialsProvider adminCredentialsProvider() {
            return () -> passwordEncoder.encode(TEST_PASSWORD);
        }
    }
}
