package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.DispatcherReachabilityService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link DispatcherReachabilityService}.
 *
 * <p>Performs an HTTP HEAD request to the configured dispatcher base URL with a short connect +
 * read timeout (per Brief H-5: HEAD recommended when actuator-enabled state is unknown at deploy
 * time). Returns {@code true} iff the dispatcher responds with HTTP 2xx within the timeout; {@code
 * false} on any non-2xx, network error, or timeout.
 *
 * <h2>Null-URL fast path (AC-REACHABILITY-NULL-URL-RETURNS-FALSE)</h2>
 *
 * <p>When the configured URL is null or empty, {@link #isReachable()} returns {@code false}
 * immediately without making any HTTP call. This allows TM to run in offline mode (Leg 3 only)
 * without a configured dispatcher URL.
 *
 * <h2>Timeout mechanism</h2>
 *
 * <p>Uses JDK 11+ {@link HttpClient} with a timeout equal to {@code reachabilityTimeoutMs} applied
 * as both connect and request timeout (AC-REACHABILITY-TIMEOUT-OBSERVED).
 *
 * <h2>Offline-operability</h2>
 *
 * <p>Per DEC-15 and Brief T-3, this service MUST NOT throw exceptions — it must always return
 * {@code false} on any failure. The caller (RoutingSlotOptimizationClient) treats {@code false} as
 * "fall through to Leg 3".
 *
 * @see DispatcherReachabilityService
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-15.md">DEC-15</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S03.story.md">Story E27S03 —
 *     AC-REACHABILITY-SERVICE-AUTHORED, AC-REACHABILITY-NULL-URL-RETURNS-FALSE,
 *     AC-REACHABILITY-TIMEOUT-OBSERVED</a>
 */
public class DefaultDispatcherReachabilityService implements DispatcherReachabilityService {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultDispatcherReachabilityService.class);

    private final String dispatcherBaseUrl;
    private final long reachabilityTimeoutMs;
    private final HttpClient httpClient;

    /**
     * Constructs the reachability service.
     *
     * @param dispatcherBaseUrl the dispatcher's base URL; null or empty disables Leg 2 routing
     * @param reachabilityTimeoutMs connect + read timeout in milliseconds for the HEAD probe
     */
    public DefaultDispatcherReachabilityService(
            String dispatcherBaseUrl, long reachabilityTimeoutMs) {
        this.dispatcherBaseUrl = dispatcherBaseUrl;
        this.reachabilityTimeoutMs = reachabilityTimeoutMs;
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(reachabilityTimeoutMs))
                        .build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sends a {@code HEAD} request to the dispatcher base URL. Returns {@code true} iff the
     * response status is HTTP 2xx within {@code reachabilityTimeoutMs}; {@code false} otherwise.
     *
     * <p>Per AC-REACHABILITY-NULL-URL-RETURNS-FALSE: when {@code dispatcherBaseUrl} is null or
     * empty, returns {@code false} immediately without any HTTP call.
     */
    @Override
    public boolean isReachable() {
        if (dispatcherBaseUrl == null || dispatcherBaseUrl.isBlank()) {
            LOG.debug(
                    "DefaultDispatcherReachabilityService.isReachable: url is null/empty —"
                            + " returning false (Leg 3 fallback)");
            return false;
        }

        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(dispatcherBaseUrl))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofMillis(reachabilityTimeoutMs))
                            .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            int statusCode = response.statusCode();
            boolean reachable = statusCode >= 200 && statusCode < 300;

            LOG.debug(
                    "DefaultDispatcherReachabilityService.isReachable: url={}, status={},"
                            + " reachable={}",
                    dispatcherBaseUrl,
                    statusCode,
                    reachable);

            return reachable;
        } catch (java.net.http.HttpTimeoutException e) {
            LOG.debug(
                    "DefaultDispatcherReachabilityService.isReachable: timeout after {}ms for"
                            + " url={}",
                    reachabilityTimeoutMs,
                    dispatcherBaseUrl);
            return false;
        } catch (Exception e) {
            LOG.debug(
                    "DefaultDispatcherReachabilityService.isReachable: error probing {}: {}",
                    dispatcherBaseUrl,
                    e.getMessage());
            return false;
        }
    }
}
