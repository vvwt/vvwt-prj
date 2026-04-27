package de.vvwt.info.ratelimit.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.error.ErrorResponse;
import de.vvwt.info.ratelimit.SourceIpExtractor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that enforces per-IP token-bucket and per-tournament-token WS concurrency limits
 * (E38S07 AC2, AC3, AC9, AC10, AC11).
 *
 * <p>Routing logic:
 *
 * <ol>
 *   <li>WebSocket upgrade ({@code Upgrade: websocket}) on {@code /api/v1/stream/...}: checks per-IP
 *       WS limit first (AC10 ordering), then per-tournament-token concurrency limit.
 *   <li>Publisher ({@code POST /api/v1/publish/...} or registration): checks per-IP publisher
 *       limit.
 *   <li>Poll ({@code GET /api/v1/poll/...}): checks per-IP poll limit.
 *   <li>All other paths: pass through unchecked.
 * </ol>
 *
 * <p>On rejection: HTTP 429 is returned with a {@code Retry-After} header (AC9) and a JSON body
 * conforming to {@code Envelope<ErrorResponse.RateLimited>} (AC11). An audit row is emitted via
 * {@link RateLimitAuditService} (AC4).
 *
 * @see <a href="../../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC2,
 *     AC3, AC9, AC10, AC11</a>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int RETRY_AFTER_SECONDS = 60;
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    private final SourceIpExtractor sourceIpExtractor;
    private final IpRateLimiter ipRateLimiter;
    private final TournamentConcurrencyLimiter concurrencyLimiter;
    private final RateLimitAuditService auditService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            SourceIpExtractor sourceIpExtractor,
            IpRateLimiter ipRateLimiter,
            TournamentConcurrencyLimiter concurrencyLimiter,
            RateLimitAuditService auditService) {
        this(
                sourceIpExtractor,
                ipRateLimiter,
                concurrencyLimiter,
                auditService,
                new ObjectMapper());
    }

    RateLimitFilter(
            SourceIpExtractor sourceIpExtractor,
            IpRateLimiter ipRateLimiter,
            TournamentConcurrencyLimiter concurrencyLimiter,
            RateLimitAuditService auditService,
            ObjectMapper objectMapper) {
        this.sourceIpExtractor = sourceIpExtractor;
        this.ipRateLimiter = ipRateLimiter;
        this.concurrencyLimiter = concurrencyLimiter;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        String method = request.getMethod();
        boolean isWsUpgrade = "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));

        if (isWsUpgrade && uri.startsWith("/api/v1/stream/")) {
            handleWsUpgrade(request, response, chain, uri);
        } else if ("POST".equalsIgnoreCase(method) && uri.startsWith("/api/v1/publish/")) {
            handlePublisher(request, response, chain, uri);
        } else if ("POST".equalsIgnoreCase(method) && uri.startsWith("/api/v1/tournaments/")) {
            handlePublisher(request, response, chain, uri);
        } else if ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/v1/poll/")) {
            handlePoll(request, response, chain, uri);
        } else {
            chain.doFilter(request, response);
        }
    }

    // ── WS upgrade: per-IP check FIRST, then per-tournament concurrency ──────────────────────────

    private void handleWsUpgrade(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain, String uri)
            throws ServletException, IOException {

        String sourceIp = sourceIpExtractor.extract(request);

        // AC10: per-IP fires first
        if (!ipRateLimiter.tryConsume(sourceIp, RateLimitType.READER_WS)) {
            reject(request, response, sourceIp, uri, "PER_IP");
            return;
        }

        // Per-tournament-token concurrency (AC3)
        String tournamentToken = extractSegment(uri, 4); // /api/v1/stream/{token}/{teamToken}
        if (!concurrencyLimiter.tryAcquireSlot(tournamentToken)) {
            reject(request, response, sourceIp, uri, "PER_TOURNAMENT_TOKEN");
            return;
        }

        chain.doFilter(request, response);
    }

    // ── Publisher ────────────────────────────────────────────────────────────────────────────────

    private void handlePublisher(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain, String uri)
            throws ServletException, IOException {

        String sourceIp = sourceIpExtractor.extract(request);
        if (!ipRateLimiter.tryConsume(sourceIp, RateLimitType.PUBLISHER)) {
            reject(request, response, sourceIp, uri, "PER_IP");
            return;
        }
        chain.doFilter(request, response);
    }

    // ── Poll ─────────────────────────────────────────────────────────────────────────────────────

    private void handlePoll(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain, String uri)
            throws ServletException, IOException {

        String sourceIp = sourceIpExtractor.extract(request);
        if (!ipRateLimiter.tryConsume(sourceIp, RateLimitType.READER_POLL)) {
            reject(request, response, sourceIp, uri, "PER_IP");
            return;
        }
        chain.doFilter(request, response);
    }

    // ── Rejection helper ─────────────────────────────────────────────────────────────────────────

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            String sourceIp,
            String uri,
            String scope)
            throws IOException {

        // Emit audit row (AC4)
        String tenantId = extractTenantId(uri);
        String tournamentId = extractTournamentId(uri);
        auditService.emitRateLimitedAuditRow(sourceIp, tenantId, tournamentId, uri);

        // AC9: 429 + Retry-After header
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));

        // AC11: Envelope<ErrorResponse.RateLimited> response body
        Envelope<ErrorResponse> envelope =
                new Envelope<>(
                        Envelope.SCHEMA_VERSION,
                        new ErrorResponse.RateLimited(RETRY_AFTER_SECONDS, scope));
        response.setContentType(CONTENT_TYPE_JSON);
        java.io.PrintWriter writer = response.getWriter();
        if (writer != null) {
            objectMapper.writeValue(writer, envelope);
        }
    }

    // ── URI segment helpers ──────────────────────────────────────────────────────────────────────

    /**
     * Extracts the path segment at the given 0-based index (splitting on {@code "/"}, where index 0
     * is always the empty string before the leading slash).
     *
     * <p>Example: {@code /api/v1/stream/tok1/team1} → index 4 = {@code "tok1"}.
     *
     * @return the segment string, or {@code null} if the URI has fewer segments
     */
    private static String extractSegment(String uri, int index) {
        String[] parts = uri.split("/", -1);
        return (index < parts.length) ? parts[index] : null;
    }

    /**
     * Extracts tenantId from publish URIs: {@code
     * /api/v1/publish/{tenantId}/{locationId}/{tournamentId}}. Returns {@code null} for non-publish
     * URIs or short URIs.
     */
    private static String extractTenantId(String uri) {
        if (uri.startsWith("/api/v1/publish/")) {
            return extractSegment(uri, 4); // segments: ["", "api", "v1", "publish", tenantId, ...]
        }
        if (uri.startsWith("/api/v1/tournaments/")) {
            return extractSegment(uri, 4);
        }
        return null;
    }

    /**
     * Extracts tournamentId / tournament-token from URIs.
     *
     * <ul>
     *   <li>Publish: {@code /api/v1/publish/{tenantId}/{locationId}/{tournamentId}} → index 6
     *   <li>Poll: {@code /api/v1/poll/{tournamentToken}/{teamToken}} → index 4
     *   <li>Stream: {@code /api/v1/stream/{tournamentToken}/{teamToken}} → index 4
     * </ul>
     */
    private static String extractTournamentId(String uri) {
        if (uri.startsWith("/api/v1/publish/")) {
            return extractSegment(uri, 6);
        }
        if (uri.startsWith("/api/v1/poll/") || uri.startsWith("/api/v1/stream/")) {
            return extractSegment(uri, 4);
        }
        return null;
    }
}
