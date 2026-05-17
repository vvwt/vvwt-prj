// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.slotopt;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.slotopt.DispatcherStatus;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.web.WebModuleTestConfig;
import org.junit.jupiter.api.BeforeEach;
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
 * Integration tests for {@link DispatcherStatusController} (E63S07, AC-TEST-WEB-IT,
 * AC-TEST-STATUS-DISPLAYED, AC-TEST-MANUAL-RECHECK, AC-TEST-STATUS-DISTINGUISHES-NOT-CONFIGURED,
 * AC-SEC-RECHECK-ENDPOINT-AUTHENTICATED, AC-ERR-NOT-CONFIGURED-IS-NOT-AN-ERROR).
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>GET /api/slotopt/dispatcher/status — returns status response
 *       (REACHABLE/UNREACHABLE/NOT_CONFIGURED)
 *   <li>POST /api/slotopt/dispatcher/recheck — re-probes and returns fresh status
 *   <li>NOT_CONFIGURED is distinct from UNREACHABLE in response
 *   <li>Unauthenticated request returns 401
 *   <li>Response carries only status and optional URL (no credentials, no stack details)
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 Iron Law — RED-first TDD (E63S07
 *       AC-GOV-REACHABILITY-SERVICE-INTERFACE-CHANGE-RED-FIRST)
 *   <li>DEC-40 Clause A — controller in {@code web.slotopt.*} sub-package
 *   <li>DEC-44 D1 — {@code @AutoConfigureTestRestTemplate @SpringBootTest(RANDOM_PORT)} per
 *       retro-correction
 *   <li>DEC-44 D2 — per-IT inner {@code TestAdminCredentials} provides {@code @Primary
 *       AdminCredentialsProvider}
 * </ul>
 *
 * @see DispatcherStatusController
 * @see WebModuleTestConfig
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e63s07statusitdb;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, DispatcherStatusControllerIT.TestAdminCredentials.class})
class DispatcherStatusControllerIT {

    static final String TEST_PASSWORD = "DispatcherStatusCtrlIT63S07";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        tenantContextBinder.bindDefaultTenant();
    }

    // =========================================================================
    // GET /api/slotopt/dispatcher/status — NOT_CONFIGURED (default: no URL set)
    // =========================================================================

    /**
     * AC-ERR-NOT-CONFIGURED-IS-NOT-AN-ERROR: when no dispatcher URL is configured, status returns
     * HTTP 200 with NOT_CONFIGURED state (not an error response).
     *
     * <p>The test environment has no {@code tm.slotopt.dispatcher.url} set, so the default
     * implementation returns NOT_CONFIGURED.
     */
    @Test
    void getStatus_notConfigured_returns200WithNotConfiguredState() {
        ResponseEntity<DispatcherStatusController.DispatcherStatusResponse> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .getForEntity(
                                baseUrl + "/api/slotopt/dispatcher/status",
                                DispatcherStatusController.DispatcherStatusResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(DispatcherStatus.NOT_CONFIGURED);
    }

    // =========================================================================
    // POST /api/slotopt/dispatcher/recheck — re-probe
    // =========================================================================

    /**
     * AC-TEST-MANUAL-RECHECK: POST /recheck returns HTTP 200 with fresh status. In the test
     * environment (no dispatcher URL), result is NOT_CONFIGURED.
     */
    @Test
    void recheck_notConfigured_returns200WithNotConfiguredState() {
        ResponseEntity<DispatcherStatusController.DispatcherStatusResponse> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .postForEntity(
                                baseUrl + "/api/slotopt/dispatcher/recheck",
                                null,
                                DispatcherStatusController.DispatcherStatusResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(DispatcherStatus.NOT_CONFIGURED);
    }

    // =========================================================================
    // AC-SEC-RECHECK-ENDPOINT-AUTHENTICATED
    // =========================================================================

    /** AC-SEC-RECHECK-ENDPOINT-AUTHENTICATED: GET /status without credentials returns 401. */
    @Test
    void getStatus_unauthenticated_returns401() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(baseUrl + "/api/slotopt/dispatcher/status", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** AC-SEC-RECHECK-ENDPOINT-AUTHENTICATED: POST /recheck without credentials returns 401. */
    @Test
    void recheck_unauthenticated_returns401() {
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        baseUrl + "/api/slotopt/dispatcher/recheck", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC-SEC-STATUS-NO-INTERNAL-LEAK: response shape verification
    // =========================================================================

    /**
     * AC-SEC-STATUS-NO-INTERNAL-LEAK: response contains only status and optional dispatcherUrl — no
     * credentials, no stack details.
     */
    @Test
    void getStatus_responseShapeContainsOnlyStatusAndUrl() {
        ResponseEntity<DispatcherStatusController.DispatcherStatusResponse> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .getForEntity(
                                baseUrl + "/api/slotopt/dispatcher/status",
                                DispatcherStatusController.DispatcherStatusResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DispatcherStatusController.DispatcherStatusResponse body = response.getBody();
        assertThat(body).isNotNull();
        // status must be a known enum value
        assertThat(body.status())
                .isIn(
                        DispatcherStatus.REACHABLE,
                        DispatcherStatus.UNREACHABLE,
                        DispatcherStatus.NOT_CONFIGURED);
        // dispatcherUrl is null when NOT_CONFIGURED (no URL in test env)
        assertThat(body.dispatcherUrl()).isNull();
    }

    // =========================================================================
    // Inner TestAdminCredentials (DEC-44 D2 pattern)
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("dispatcherStatusItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider dispatcherStatusItAdminCredentialsProvider(
                PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
