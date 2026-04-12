package de.vvwt.tm.infrastructure.web.dto;

/**
 * Request body for {@code POST /api/phases/{phaseId}/advance-lap} (AC5 — E05S07).
 *
 * <p>{@code force} defaults to {@code false}. Set to {@code true} to override the
 * unfinished-match check and force lap advancement with an audit log entry.
 */
public record AdvanceLapRequest(boolean force) {

    /**
     * Default constructor: {@code force = false}.
     */
    public AdvanceLapRequest() {
        this(false);
    }
}
