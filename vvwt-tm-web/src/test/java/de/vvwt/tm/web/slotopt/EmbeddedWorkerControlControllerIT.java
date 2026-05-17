// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.slotopt;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.web.WebModuleTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link EmbeddedWorkerControlController} (E63S05, AC-TEST-WEB-IT).
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>GET /status — embedded worker disabled (flag off) → 200 STOPPED/0
 *   <li>POST /pause — embedded worker disabled → 409 conflict
 *   <li>POST /resume — embedded worker disabled → 409 conflict
 *   <li>POST /disable — embedded worker disabled → 409 conflict
 *   <li>GET /status — unauthenticated → 401
 *   <li>POST /pause — unauthenticated → 401
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 Iron Law Q-1a — RED-first TDD (AC-GOV-RED-FIRST)
 *   <li>DEC-40 Clause B Pattern A — controller in {@code web.slotopt.*}; DTOs direct
 *   <li>DEC-44 2026-04-27 empirical refinement — {@code @SpringBootTest(RANDOM_PORT, classes =
 *       TournamentManagerApplication.class)} + {@code @Import({WebModuleTestConfig.class,
 *       EmbeddedWorkerControlControllerIT.TestAdminCredentials.class})}
 *   <li>AC-SEC-CONTROL-ENDPOINTS-ADMIN-AUTHENTICATED — all non-GET endpoints require admin auth
 * </ul>
 *
 * <p>The embedded worker flag is intentionally NOT set in {@code properties} — so {@code
 * tm.slotopt.embedded-worker.enabled} is absent (defaults to false). This exercises the
 * "flag-disabled" path for all control endpoints.
 *
 * @see EmbeddedWorkerControlController
 * @see WebModuleTestConfig
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e63s05controlit;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, EmbeddedWorkerControlControllerIT.TestAdminCredentials.class})
class EmbeddedWorkerControlControllerIT {

    static final String TEST_PASSWORD = "EmbeddedWorkerCtrlIT63S05";

    @Autowired TestRestTemplate restTemplate;

    // =========================================================================
    // AC-ERR-METRICS-WHEN-DISABLED: GET /status always returns 200 STOPPED
    // =========================================================================

    @Test
    void getStatus_workerDisabled_returns200WithStoppedState() {
        ResponseEntity<EmbeddedWorkerStatusResponse> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .getForEntity(
                                "/api/slotopt/embedded-worker/status",
                                EmbeddedWorkerStatusResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().state()).isEqualTo("STOPPED");
        assertThat(response.getBody().stateCode()).isZero();
        assertThat(response.getBody().packetsCompleted()).isZero();
        assertThat(response.getBody().packetsFailed()).isZero();
    }

    // =========================================================================
    // AC-ERR-CONTROL-ON-DISABLED-WORKER: control operations return 409 when flag-disabled
    // =========================================================================

    @Test
    void pause_workerDisabled_returns409() {
        ResponseEntity<String> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .postForEntity("/api/slotopt/embedded-worker/pause", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void resume_workerDisabled_returns409() {
        ResponseEntity<String> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .postForEntity("/api/slotopt/embedded-worker/resume", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void disable_workerDisabled_returns409() {
        ResponseEntity<String> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .postForEntity("/api/slotopt/embedded-worker/disable", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // =========================================================================
    // AC-SEC-CONTROL-ENDPOINTS-ADMIN-AUTHENTICATED: unauthenticated → 401
    // =========================================================================

    @Test
    void getStatus_unauthenticated_returns401() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/slotopt/embedded-worker/status", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void pause_unauthenticated_returns401() {
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/api/slotopt/embedded-worker/pause", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Inner TestConfiguration — per DEC-44 D2 pattern
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Autowired PasswordEncoder passwordEncoder;

        @Bean("testAdminCredentialsProvider")
        @Primary
        public AdminCredentialsProvider adminCredentialsProvider() {
            return () -> passwordEncoder.encode(TEST_PASSWORD);
        }
    }
}
