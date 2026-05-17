package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.vvwt.tm.slotopt.DispatcherStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultDispatcherReachabilityService}.
 *
 * <p>TDD RED-first per DEC-22 Iron Law (E27S03, E63S07). Uses JDK 21 built-in {@link
 * com.sun.net.httpserver.HttpServer} as the HTTP test-double (no third-party dependency — DEC-3
 * minimal-dependency; pattern established at E41S06 DispatcherStub).
 *
 * <p>Per DEC-36: this class is in the {@code slotopt.internal} package (same package as the
 * subject), so white-box reference to {@link DefaultDispatcherReachabilityService} is permitted.
 *
 * @see DefaultDispatcherReachabilityService
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S03.story.md">Story E27S03 —
 *     AC-REACHABILITY-SERVICE-AUTHORED, AC-REACHABILITY-NULL-URL-RETURNS-FALSE,
 *     AC-REACHABILITY-TIMEOUT-OBSERVED</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E63S07.story.md">Story E63S07 —
 *     AC-GOV-REACHABILITY-SERVICE-INTERFACE-CHANGE-RED-FIRST,
 *     AC-TEST-STATUS-DISTINGUISHES-NOT-CONFIGURED</a>
 */
class DefaultDispatcherReachabilityServiceTest {

    private HttpServer stubServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress(0), 0);
        stubServer.setExecutor(Executors.newCachedThreadPool());
        stubServer.start();
        baseUrl = "http://localhost:" + stubServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    // =========================================================================
    // AC-REACHABILITY-NULL-URL-RETURNS-FALSE
    // =========================================================================

    /**
     * AC-REACHABILITY-NULL-URL-RETURNS-FALSE: when url is null, isReachable() returns false
     * immediately without making any HTTP call.
     */
    @Test
    void isReachable_nullUrl_returnsFalseImmediately() {
        // Use a fresh server to count requests
        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService(null, 2000);

        boolean result = service.isReachable();

        assertThat(result).isFalse();
    }

    /** AC-REACHABILITY-NULL-URL-RETURNS-FALSE: empty string URL also returns false immediately. */
    @Test
    void isReachable_emptyUrl_returnsFalseImmediately() {
        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService("", 2000);

        boolean result = service.isReachable();

        assertThat(result).isFalse();
    }

    // =========================================================================
    // AC-REACHABILITY-SERVICE-AUTHORED: returns true on HTTP 200
    // =========================================================================

    /**
     * AC-REACHABILITY-SERVICE-AUTHORED: when dispatcher responds HTTP 200, isReachable() returns
     * true.
     */
    @Test
    void isReachable_http200_returnsTrue() {
        // Register a handler that returns 200
        stubServer.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().close();
                });

        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService(baseUrl, 2000);

        boolean result = service.isReachable();

        assertThat(result).isTrue();
    }

    /**
     * AC-REACHABILITY-SERVICE-AUTHORED: when dispatcher responds non-200, isReachable() returns
     * false.
     */
    @Test
    void isReachable_http503_returnsFalse() {
        stubServer.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(503, 0);
                    exchange.getResponseBody().close();
                });

        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService(baseUrl, 2000);

        boolean result = service.isReachable();

        assertThat(result).isFalse();
    }

    // =========================================================================
    // AC-REACHABILITY-TIMEOUT-OBSERVED: returns false within timeout + epsilon
    // =========================================================================

    /**
     * AC-REACHABILITY-TIMEOUT-OBSERVED: when the endpoint is unresponsive (connection refused),
     * isReachable() returns false without hanging indefinitely.
     *
     * <p>Shuts down the stub server before calling isReachable() to simulate connection refused.
     */
    @Test
    void isReachable_connectionRefused_returnsFalseWithinTimeout() {
        // Stop the server to force connection refused
        stubServer.stop(0);
        stubServer = null; // prevent double-stop in @AfterEach

        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService(baseUrl, 500);

        Instant start = Instant.now();
        boolean result = service.isReachable();
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(result).isFalse();
        // Must return within 500ms timeout + 500ms epsilon = 1000ms
        assertThat(elapsed.toMillis()).isLessThan(1000);
    }

    /**
     * AC-REACHABILITY-TIMEOUT-OBSERVED: when endpoint delays beyond timeout, isReachable() returns
     * false within timeout + epsilon.
     */
    @Test
    void isReachable_serverHangs_returnsFalseWithinTimeout() {
        // Register a handler that hangs (sleeps longer than timeout)
        stubServer.createContext(
                "/",
                exchange -> {
                    try {
                        // Sleep longer than the 500ms timeout
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().close();
                });

        // 500ms timeout for the test
        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService(baseUrl, 500);

        Instant start = Instant.now();
        boolean result = service.isReachable();
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(result).isFalse();
        // Must return within 500ms timeout + 500ms epsilon = 1000ms
        assertThat(elapsed.toMillis()).isLessThan(1000);
    }

    // =========================================================================
    // E63S07: getStatus() — AC-GOV-REACHABILITY-SERVICE-INTERFACE-CHANGE-RED-FIRST
    //         AC-TEST-STATUS-DISTINGUISHES-NOT-CONFIGURED
    // =========================================================================

    /**
     * AC-TEST-STATUS-DISTINGUISHES-NOT-CONFIGURED: when url is null, getStatus() returns
     * NOT_CONFIGURED (not UNREACHABLE).
     */
    @Test
    void getStatus_nullUrl_returnsNotConfigured() {
        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService(null, 2000);

        DispatcherStatus status = service.getStatus();

        assertThat(status).isEqualTo(DispatcherStatus.NOT_CONFIGURED);
    }

    /**
     * AC-TEST-STATUS-DISTINGUISHES-NOT-CONFIGURED: when url is empty, getStatus() returns
     * NOT_CONFIGURED.
     */
    @Test
    void getStatus_emptyUrl_returnsNotConfigured() {
        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService("", 2000);

        DispatcherStatus status = service.getStatus();

        assertThat(status).isEqualTo(DispatcherStatus.NOT_CONFIGURED);
    }

    /**
     * AC-GOV-EXTENDS-REACHABILITY-SERVICE: when dispatcher responds HTTP 200, getStatus() returns
     * REACHABLE.
     */
    @Test
    void getStatus_http200_returnsReachable() {
        stubServer.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().close();
                });

        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService(baseUrl, 2000);

        DispatcherStatus status = service.getStatus();

        assertThat(status).isEqualTo(DispatcherStatus.REACHABLE);
    }

    /**
     * AC-ERR-RECHECK-TIMEOUT-SHOWS-OFFLINE: when dispatcher responds non-2xx, getStatus() returns
     * UNREACHABLE (not NOT_CONFIGURED).
     */
    @Test
    void getStatus_http503_returnsUnreachable() {
        stubServer.createContext(
                "/",
                exchange -> {
                    exchange.sendResponseHeaders(503, 0);
                    exchange.getResponseBody().close();
                });

        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService(baseUrl, 2000);

        DispatcherStatus status = service.getStatus();

        assertThat(status).isEqualTo(DispatcherStatus.UNREACHABLE);
    }

    /**
     * AC-ERR-RECHECK-TIMEOUT-SHOWS-OFFLINE: when connection is refused (unreachable host),
     * getStatus() returns UNREACHABLE within the timeout bound.
     */
    @Test
    void getStatus_connectionRefused_returnsUnreachableWithinTimeout() {
        stubServer.stop(0);
        stubServer = null;

        DefaultDispatcherReachabilityService service =
                new DefaultDispatcherReachabilityService(baseUrl, 500);

        Instant start = Instant.now();
        DispatcherStatus status = service.getStatus();
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(status).isEqualTo(DispatcherStatus.UNREACHABLE);
        assertThat(elapsed.toMillis()).isLessThan(1000);
    }
}
