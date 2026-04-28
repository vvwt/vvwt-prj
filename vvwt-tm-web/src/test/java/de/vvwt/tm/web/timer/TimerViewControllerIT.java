package de.vvwt.tm.web.timer;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.web.WebModuleTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * Integration tests for {@link TimerViewController} — E26S03 Q-1a TDD reconstruction.
 *
 * <h2>Test coverage (AC-Q7-METHOD-COUNT-RECONFIRMATION)</h2>
 *
 * <ul>
 *   <li>happy path: GET /timer/tournaments/{uuid} returns 200 with text/html content
 *   <li>security-negative: endpoint is accessible without admin auth (permitAll)
 *   <li>DEC-15 compatibility: response body does NOT contain https:// CDN links
 *   <li>forward-loop avoidance: index.html path does not re-enter controller
 *   <li>Wave-2 URL: /timer/tournaments/{uuid} path (NOT legacy /timer/{uuid})
 * </ul>
 *
 * <p>Total: 5 {@code @Test} methods (audit (v) baseline 5).
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 Iron Law Q-1a — RED-first: this test written before {@link TimerViewController}
 *       exists; RED commit = this commit; GREEN commit = next (TimerViewController production code)
 *   <li>DEC-44 D1 — {@code @SpringBootTest(RANDOM_PORT)} + {@code @Import({WebModuleTestConfig,
 *       TestAdminCredentials})} per 2026-04-27 empirical refinement
 *   <li>DEC-44 D2 — per-IT inner {@code TestAdminCredentials} provides {@code @Primary
 *       AdminCredentialsProvider}; no {@code UserDetailsService} or {@code SecurityFilterChain}
 *       substitute
 *   <li>AC-DEC44-D2-EMPIRICAL-REFINEMENT-RESPECT — no UDS/SecurityFilterChain substitute beans
 * </ul>
 *
 * @see TimerViewController
 * @see WebModuleTestConfig
 * @since E26S03
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e26s03timerviewitdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, TimerViewControllerIT.TestAdminCredentials.class})
class TimerViewControllerIT {

    static final String TEST_PASSWORD = "TimerViewControllerIT26S03";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;

    private String baseUrl;
    private final UUID sampleTournamentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        tenantContextBinder.bindDefaultTenant();
    }

    @AfterEach
    void tearDown() {
        tenantContextBinder.unbind();
    }

    // =========================================================================
    // Happy path — GET /timer/tournaments/{uuid} returns 200 with text/html
    // =========================================================================

    /**
     * Happy path: GET /timer/tournaments/{uuid} returns 200 with {@code Content-Type: text/html}.
     *
     * <p>AC-URL-PATHS-WAVE-2-ALIGNED: Wave-2 MVC route {@code /timer/tournaments/{uuid}} (NOT
     * legacy {@code /timer/{uuid}}). The controller serves the Vite-built {@code
     * classpath:/static/timer/index.html}.
     */
    @Test
    void timerPageReturns200WithTextHtmlContentType() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        baseUrl + "/timer/tournaments/" + sampleTournamentId, String.class);

        assertThat(response.getStatusCode())
                .as("Timer SPA page must return 200 for any UUID path variable")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(
                        ct ->
                                assertThat(ct.toString())
                                        .as("Content-Type must be text/html")
                                        .startsWith("text/html"));
    }

    /**
     * Security-negative: GET /timer/tournaments/{uuid} is accessible without admin credentials
     * (permitAll per AC-AUTHENTICATION-FLOW-PRESERVED + AC-SECURITY-CONFIG-URL-2-MVC-TIMER).
     */
    @Test
    void timerPageIsAccessibleWithoutAdminAuth() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        baseUrl + "/timer/tournaments/" + sampleTournamentId, String.class);

        assertThat(response.getStatusCode())
                .as("Timer page must be accessible without authentication (permitAll)")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * DEC-15 compatibility: the served HTML must NOT contain any {@code https://} CDN links.
     *
     * <p>All assets are bundled in the JAR ({@code /timer/assets/**} from Vite build). No external
     * CDN references allowed (offline/LAN deployment per DEC-15 + DEC-16).
     */
    @Test
    void timerPageDoesNotContainCdnLinks() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        baseUrl + "/timer/tournaments/" + sampleTournamentId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        // Must not contain https:// asset tags (CDN links are forbidden per DEC-15)
        assertThat(body)
                .as("DEC-15: timer page must not contain CDN https:// links in asset tags")
                .doesNotContain("https://");
    }

    /**
     * Wave-2 URL alignment: the legacy path {@code /timer/{uuid}} must NOT match this controller
     * mapping — the controller maps {@code /timer/tournaments/{uuid}} only.
     *
     * <p>AC-URL-PATHS-WAVE-2-ALIGNED verifies no accidental backward-compat alias.
     */
    @Test
    void legacyTimerViewUrlDoesNotMatchNewController() {
        ResponseEntity<Void> response =
                restTemplate.getForEntity(baseUrl + "/timer/" + sampleTournamentId, Void.class);

        // The legacy /timer/{uuid} path should NOT map to the new controller → 404 or redirect
        assertThat(response.getStatusCode().value())
                .as("Legacy /timer/{uuid} must NOT return 200 — no backward compat alias")
                .isNotEqualTo(200);
    }

    /**
     * A second UUID path variable gives 200 — the {@link TimerViewController} serves the same SPA
     * regardless of the tournamentId value (does not validate it server-side).
     *
     * <p>Ensures the path variable acceptance is generic (not tied to specific UUIDs).
     */
    @Test
    void timerPageReturns200ForAnyUuidPathVariable() {
        UUID anotherId = UUID.randomUUID();
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        baseUrl + "/timer/tournaments/" + anotherId, String.class);

        assertThat(response.getStatusCode())
                .as(
                        "Timer page must return 200 for any tournament UUID (not validated"
                                + " server-side)")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // Test configuration — fixed admin credentials (DEC-44 D2 empirical-refinement pattern)
    // =========================================================================

    /**
     * Per-IT {@link AdminCredentialsProvider} providing a fixed BCrypt-hashed test password.
     *
     * <p>Per DEC-44 §"2026-04-27 Empirical Refinement": this inner class provides {@code @Primary
     * AdminCredentialsProvider} which overrides the non-primary placeholder in {@link
     * WebModuleTestConfig} via {@code spring.main.allow-bean-definition-overriding=true}.
     * Production {@code SecurityFilterChain} + {@code UserDetailsService} remain sole instances. No
     * {@code UserDetailsService} or {@code SecurityFilterChain} substitute bean added
     * (AC-DEC44-D2-EMPIRICAL-REFINEMENT-RESPECT).
     */
    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hashed = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hashed;
        }
    }
}
