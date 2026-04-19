package de.vvwt.tm.infrastructure.timer;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link TimerViewController} (E11S03 AC1).
 *
 * <h2>Scope</h2>
 *
 * <p>Verifies that the Timer SPA HTML shell is served correctly:
 *
 * <ol>
 *   <li>HTTP 200 at {@code GET /timer/{uuid}} without authentication (AC1)
 *   <li>Content-Type text/html (AC1)
 *   <li>Response body contains the Svelte mount point {@code <div id="app">} (AC1)
 *   <li>No external CDN links in the response — DEC-15 local assets check
 * </ol>
 *
 * <h2>Timer SPA assets</h2>
 *
 * <p>The timer SPA assets are built by {@code npm run build} in the {@code generate-resources}
 * Maven phase and copied to {@code target/classes/static/timer/} by {@code maven-resources-plugin}.
 * These assets are available on the test classpath when this IT runs in the {@code test} phase.
 *
 * @see TimerViewController
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S03.story.md">Story
 *     E11S03</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            TimerViewControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e11s03viewdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class TimerViewControllerIT {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    /** A fixed valid-looking tournament UUID to use as the path variable. */
    private static final String VALID_UUID = UUID.randomUUID().toString();

    /**
     * Provides a fixed, known admin password hash for the test context.
     *
     * <p>The production {@link de.vvwt.tm.auth.internal.AdminCredentialsBootstrap} generates a
     * random password at startup. In the test context we override with a known BCrypt hash so the
     * Spring context can start without I/O. (Timer route tests do not require authentication, but
     * the context needs a valid {@link AdminCredentialsProvider} to boot.)
     */
    @TestConfiguration
    static class TestAdminCredentials {
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider() {
            // BCrypt hash of "testpassword" (cost 10)
            return () -> "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
        }
    }

    // -------------------------------------------------------------------------
    // AC1 — GET /timer/{tournamentId} returns 200 without authentication
    // -------------------------------------------------------------------------

    /**
     * AC1: {@code GET /timer/{tournamentId}} must return HTTP 200 without any credentials.
     *
     * <p>The timer route is permit-all — no authentication is required. Venue staff open this URL
     * on a device without admin credentials.
     */
    @Test
    void timerPageReturns200WithoutAuthentication() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/timer/" + VALID_UUID, String.class);

        assertThat(response.getStatusCode())
                .as("GET /timer/{uuid} must return 200 without auth (AC1)")
                .isEqualTo(HttpStatus.OK);
    }

    /** AC1: {@code GET /timer/{tournamentId}} must return an HTML response. */
    @Test
    void timerPageReturnsHtmlContentType() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/timer/" + VALID_UUID, String.class);

        assertThat(response.getHeaders().getContentType())
                .as("GET /timer/{uuid} content-type must be text/html (AC1)")
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
    }

    /**
     * AC1: The HTML shell must contain the Svelte mount point {@code <div id="app">}.
     *
     * <p>This verifies that the Vite-built {@code index.html} is served correctly and includes the
     * DOM element the Svelte app mounts into.
     */
    @Test
    void timerPageContainsSvelteAppMountPoint() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/timer/" + VALID_UUID, String.class);

        assertThat(response.getBody())
                .as("Response body must contain Svelte app mount point <div id=\"app\"> (AC1)")
                .contains("<div id=\"app\">");
    }

    /**
     * DEC-15: The HTML shell must NOT contain external {@code https://} URLs in {@code <script>} or
     * {@code <link>} tags.
     *
     * <p>All Vite-compiled assets are served from {@code /timer/assets/} (local, bundled in the
     * JAR). Any external CDN reference would prevent offline use (DEC-15 self-host).
     */
    @Test
    void timerPageContainsNoExternalCdnScriptOrLinkTags() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/timer/" + VALID_UUID, String.class);

        String body = response.getBody();
        assertThat(body).isNotNull();

        assertThat(body)
                .as("HTML shell must not contain external <script src=\"https://...\"> (DEC-15)")
                .doesNotContainPattern("<script[^>]+src=[\"']https://");

        assertThat(body)
                .as("HTML shell must not contain external <link href=\"https://...\"> (DEC-15)")
                .doesNotContainPattern("<link[^>]+href=[\"']https://");
    }
}
