package de.vvwt.info.ratelimit.internal;

/**
 * Identifies the endpoint category for per-IP rate limiting (E38S07 AC5).
 *
 * <p>Each type maps to a separate token bucket per IP, allowing different limits for publisher,
 * reader-poll, and reader-WS endpoints.
 *
 * @see <a href="../../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC5</a>
 */
public enum RateLimitType {
    /** Publisher endpoints ({@code POST /api/v1/publish/...} and registration). */
    PUBLISHER,
    /** HTTP short-poll reader endpoint ({@code GET /api/v1/poll/...}). */
    READER_POLL,
    /** WebSocket reader connection attempt ({@code GET /api/v1/stream/...} upgrade). */
    READER_WS
}
