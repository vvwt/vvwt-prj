package de.vvwt.info.ratelimit;

/**
 * Emits audit-log rows for rate-limit rejections (E38S07 AC4, AC7).
 *
 * @see de.vvwt.info.ratelimit.internal.DefaultRateLimitAuditService
 * @see <a href="../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC4, AC7</a>
 */
public interface RateLimitAuditService {

    /**
     * Emits an audit-log row for a rate-limited request.
     *
     * @param sourceIp the resolved source IP (from {@link SourceIpExtractor})
     * @param tenantId the tenant ID extracted from the request path; may be {@code null}
     * @param tournamentId the tournament ID extracted from the request path; may be {@code null}
     * @param requestPath the HTTP request path (no query string)
     */
    void emitRateLimitedAuditRow(
            String sourceIp, String tenantId, String tournamentId, String requestPath);
}
