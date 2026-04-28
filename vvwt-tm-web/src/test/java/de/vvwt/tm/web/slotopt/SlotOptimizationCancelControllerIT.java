package de.vvwt.tm.web.slotopt;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.web.WebModuleTestConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link SlotOptimizationCancelController} (E27S02,
 * AC-CANCEL-CONTROLLER-AUTHORED, DEC-44 D1).
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>POST /cancel — no active optimization → 409 Conflict
 *   <li>POST /cancel — active optimization → 200 with cancellation token set
 *   <li>GET /status — no active optimization → 200 {"state":"idle"}
 *   <li>GET /status — active optimization → 200 {"state":"running"}
 *   <li>GET /status — cancelled optimization → 200 {"state":"cancelled"}
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 Iron Law Q-1a — RED-first TDD
 *   <li>DEC-40 Clause A — controller in {@code web.slotopt.*} sub-package
 *   <li>DEC-44 D1 — {@code @SpringBootTest(RANDOM_PORT)} + {@code @Import(WebModuleTestConfig +
 *       TestAdminCredentials)}
 *   <li>DEC-44 D2 — per-IT inner {@code TestAdminCredentials} provides {@code @Primary
 *       AdminCredentialsProvider}
 * </ul>
 *
 * @see SlotOptimizationCancelController
 * @see WebModuleTestConfig
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e27s02cancelitdb;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, SlotOptimizationCancelControllerIT.TestAdminCredentials.class})
class SlotOptimizationCancelControllerIT {

    static final String TEST_PASSWORD = "SlotOptCancelCtrlIT27S02";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;

    @Autowired private SlotOptimizationJobRegistry jobRegistry;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    // =========================================================================
    // POST /cancel — no active optimization
    // =========================================================================

    /**
     * POST /cancel with no active optimization returns 409 Conflict with NO_ACTIVE_OPTIMIZATION
     * error key.
     */
    @Test
    void cancel_noActiveOptimization_returns409() {
        UUID tournamentId = UUID.randomUUID();

        ResponseEntity<SlotOptimizationCancelController.ErrorResponse> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .postForEntity(
                                baseUrl + "/api/slotopt/tournaments/" + tournamentId + "/cancel",
                                null,
                                SlotOptimizationCancelController.ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().messageKey()).isEqualTo("NO_ACTIVE_OPTIMIZATION");
    }

    // =========================================================================
    // POST /cancel — active optimization present
    // =========================================================================

    /**
     * POST /cancel with an active optimization sets the cancellation flag and returns 200 with
     * best-so-far result.
     */
    @Test
    void cancel_activeOptimization_returns200AndSetsCancelFlag() {
        UUID tournamentId = UUID.randomUUID();
        CancellationToken token = CancellationToken.create();
        JobHandle handle = new JobHandle(token, Instant.now());
        // Pre-seed a best-so-far so the response body is non-trivial
        handle.updateBestSoFar(OptimizationResult.cancelled(3L, 0.5));
        jobRegistry.register(tournamentId, handle);

        try {
            ResponseEntity<OptimizationResult> response =
                    restTemplate
                            .withBasicAuth("admin", TEST_PASSWORD)
                            .postForEntity(
                                    baseUrl
                                            + "/api/slotopt/tournaments/"
                                            + tournamentId
                                            + "/cancel",
                                    null,
                                    OptimizationResult.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(token.isCancelled()).isTrue();
        } finally {
            jobRegistry.complete(tournamentId);
        }
    }

    // =========================================================================
    // GET /status — no active optimization
    // =========================================================================

    /** GET /status with no active optimization returns 200 with state="idle". */
    @Test
    void status_noActiveOptimization_returnsIdle() {
        UUID tournamentId = UUID.randomUUID();

        ResponseEntity<SlotOptimizationStatusResponse> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .getForEntity(
                                baseUrl + "/api/slotopt/tournaments/" + tournamentId + "/status",
                                SlotOptimizationStatusResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().state()).isEqualTo("idle");
    }

    // =========================================================================
    // GET /status — running optimization
    // =========================================================================

    /** GET /status with an active (non-cancelled) optimization returns state="running". */
    @Test
    void status_activeOptimization_returnsRunning() {
        UUID tournamentId = UUID.randomUUID();
        CancellationToken token = CancellationToken.create();
        JobHandle handle = new JobHandle(token, Instant.now());
        jobRegistry.register(tournamentId, handle);

        try {
            ResponseEntity<SlotOptimizationStatusResponse> response =
                    restTemplate
                            .withBasicAuth("admin", TEST_PASSWORD)
                            .getForEntity(
                                    baseUrl
                                            + "/api/slotopt/tournaments/"
                                            + tournamentId
                                            + "/status",
                                    SlotOptimizationStatusResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().state()).isEqualTo("running");
        } finally {
            jobRegistry.complete(tournamentId);
        }
    }

    // =========================================================================
    // GET /status — cancelled optimization (handle still in registry)
    // =========================================================================

    /** GET /status after cancel returns state="cancelled". */
    @Test
    void status_cancelledOptimization_returnsCancelled() {
        UUID tournamentId = UUID.randomUUID();
        CancellationToken token = CancellationToken.create();
        token.cancel(); // already cancelled
        JobHandle handle = new JobHandle(token, Instant.now());
        jobRegistry.register(tournamentId, handle);

        try {
            ResponseEntity<SlotOptimizationStatusResponse> response =
                    restTemplate
                            .withBasicAuth("admin", TEST_PASSWORD)
                            .getForEntity(
                                    baseUrl
                                            + "/api/slotopt/tournaments/"
                                            + tournamentId
                                            + "/status",
                                    SlotOptimizationStatusResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().state()).isEqualTo("cancelled");
        } finally {
            jobRegistry.complete(tournamentId);
        }
    }

    // =========================================================================
    // Inner TestAdminCredentials (DEC-44 D2)
    // =========================================================================

    /**
     * Per-IT {@link AdminCredentialsProvider} providing a fixed BCrypt-hashed test password (DEC-44
     * D2, AC-DEC44-D2-EMPIRICAL-REFINEMENT-RESPECT).
     */
    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("slotOptCancelItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hashed = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hashed;
        }
    }
}
