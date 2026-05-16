package de.vvwt.info.ratelimit;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.info.ratelimit.internal.RateLimitFilter;
import de.vvwt.info.ratelimit.internal.RateLimitType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <p>DEC-22 RED-first. Verifies routing to correct limit type, 429 response, audit emission, and
 * pass-through on allowed requests.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC2, AC3,
 *     AC9, AC10, AC11</a>
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock private SourceIpExtractor sourceIpExtractor;
    @Mock private IpRateLimiter ipRateLimiter;
    @Mock private TournamentConcurrencyLimiter concurrencyLimiter;
    @Mock private RateLimitAuditService auditService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter =
                new RateLimitFilter(
                        sourceIpExtractor, ipRateLimiter, concurrencyLimiter, auditService);
    }

    @Test
    void publisherEndpoint_withinLimit_passesThroughChain() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/publish/t1/loc/tour1");
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Upgrade")).thenReturn(null);
        when(sourceIpExtractor.extract(request)).thenReturn("1.2.3.4");
        when(ipRateLimiter.tryConsume("1.2.3.4", RateLimitType.PUBLISHER)).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void publisherEndpoint_overLimit_returns429WithScope() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/publish/t1/loc/tour1");
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Upgrade")).thenReturn(null);
        when(sourceIpExtractor.extract(request)).thenReturn("1.2.3.4");
        when(ipRateLimiter.tryConsume("1.2.3.4", RateLimitType.PUBLISHER)).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(429);
        verify(response)
                .setHeader(
                        org.mockito.ArgumentMatchers.eq("Retry-After"),
                        org.mockito.ArgumentMatchers.any());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void wsUpgradeRequest_overPerIpWsLimit_returns429PrecedesSlotCheck() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/stream/tok1/team1");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Upgrade")).thenReturn("websocket");
        when(sourceIpExtractor.extract(request)).thenReturn("1.2.3.4");
        when(ipRateLimiter.tryConsume("1.2.3.4", RateLimitType.READER_WS)).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(429);
        // per-IP fires first — concurrency limiter should NOT be consulted
        verify(concurrencyLimiter, never()).tryAcquireSlot(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wsUpgradeRequest_overPerTournamentLimit_returns429() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/stream/tok1/team1");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Upgrade")).thenReturn("websocket");
        when(sourceIpExtractor.extract(request)).thenReturn("1.2.3.4");
        when(ipRateLimiter.tryConsume("1.2.3.4", RateLimitType.READER_WS)).thenReturn(true);
        when(concurrencyLimiter.tryAcquireSlot("tok1")).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(429);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void wsUpgradeRequest_withinBothLimits_passesThroughChain() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/stream/tok1/team1");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Upgrade")).thenReturn("websocket");
        when(sourceIpExtractor.extract(request)).thenReturn("1.2.3.4");
        when(ipRateLimiter.tryConsume("1.2.3.4", RateLimitType.READER_WS)).thenReturn(true);
        when(concurrencyLimiter.tryAcquireSlot("tok1")).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void pollEndpoint_overLimit_returns429() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/poll/tok1/team1");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Upgrade")).thenReturn(null);
        when(sourceIpExtractor.extract(request)).thenReturn("1.2.3.4");
        when(ipRateLimiter.tryConsume("1.2.3.4", RateLimitType.READER_POLL)).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(429);
    }

    @Test
    void rateLimitedRequest_emitsAuditRow() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/publish/t1/loc/tour1");
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Upgrade")).thenReturn(null);
        when(sourceIpExtractor.extract(request)).thenReturn("1.2.3.4");
        when(ipRateLimiter.tryConsume("1.2.3.4", RateLimitType.PUBLISHER)).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        verify(auditService)
                .emitRateLimitedAuditRow(
                        org.mockito.ArgumentMatchers.eq("1.2.3.4"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void allowedRequest_doesNotEmitAuditRow() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/poll/tok1/team1");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Upgrade")).thenReturn(null);
        when(sourceIpExtractor.extract(request)).thenReturn("1.2.3.4");
        when(ipRateLimiter.tryConsume("1.2.3.4", RateLimitType.READER_POLL)).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        verify(auditService, never())
                .emitRateLimitedAuditRow(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }
}
