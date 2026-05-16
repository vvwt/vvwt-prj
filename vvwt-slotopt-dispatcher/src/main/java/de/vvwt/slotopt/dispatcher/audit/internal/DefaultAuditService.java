// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.audit.internal;

import de.vvwt.slotopt.dispatcher.audit.AuditEntry;
import de.vvwt.slotopt.dispatcher.audit.AuditRepository;
import de.vvwt.slotopt.dispatcher.audit.AuditService;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link AuditService}.
 *
 * <p>Per DEC-35: this implementation lives in {@code audit.internal}. All consumers reference
 * {@link AuditService} (the public interface), never this class directly (DEC-36).
 *
 * <p>Named {@code DefaultAuditService} per DEC-35 naming canon (no {@code I}-prefix on the
 * interface; {@code Default*} prefix on the implementation).
 *
 * <p>Failure semantics (AC-AUDIT-FAILURE-MODE):
 *
 * <ol>
 *   <li>Null/empty/blank {@code eventType} → throws {@link IllegalArgumentException} before any
 *       persistence is attempted.
 *   <li>Database persist failure ({@link DataAccessException}) → logs at WARN with structured
 *       payload (eventType, workerId, sourceIp, exception class) and does NOT rethrow. Audit
 *       failures MUST NEVER block the originating registration/operation.
 *   <li>Oversize {@code detailJson} (configurable via {@code #DETAIL_JSON_MAX_CHARS}, default 64
 *       KB) → truncated with {@code "[TRUNCATED]"} marker to preserve partial forensic evidence.
 * </ol>
 *
 * <p>Story: E37S06; AC-AUDIT-SERVICE, AC-AUDIT-FAILURE-MODE; DEC-6, DEC-35
 */
@Service
public class DefaultAuditService implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuditService.class);

    /** Maximum length of {@code detailJson} in characters. Default: 64 KB. */
    static final int DETAIL_JSON_MAX_CHARS = 65_536;

    /** Marker appended to truncated {@code detailJson}. */
    static final String TRUNCATION_MARKER = "[TRUNCATED]";

    private final AuditRepository repository;

    public DefaultAuditService(AuditRepository repository) {
        this.repository = repository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Failure semantics are described in the class javadoc.
     */
    @Override
    public void recordEvent(String eventType, UUID workerId, String sourceIp, String detailJson) {
        // Behavior (1): validate eventType — null/empty/blank → fast-fail (programmer error)
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException(
                    "eventType must not be null, empty, or blank; received: "
                            + (eventType == null ? "null" : "'" + eventType + "'"));
        }

        // Behavior (3): truncate oversize detailJson
        String persistedDetail = truncateIfNeeded(detailJson);

        // Build the AuditEntry
        AuditEntry entry = new AuditEntry();
        entry.setOccurredAt(Instant.now());
        entry.setEventType(eventType);
        entry.setWorkerId(workerId);
        entry.setSourceIp(sourceIp);
        entry.setDetailJson(persistedDetail);

        // Behavior (2): persist — DB failures are absorbed; audit must not block callers
        try {
            repository.save(entry);
        } catch (DataAccessException ex) {
            log.warn(
                    "Audit persist failure — event NOT recorded. "
                            + "eventType={} workerId={} sourceIp={} exceptionClass={}",
                    eventType,
                    workerId,
                    sourceIp,
                    ex.getClass().getSimpleName());
            // intentionally not re-thrown per AC-AUDIT-FAILURE-MODE
        }
    }

    /**
     * Truncates {@code detailJson} to {@link #DETAIL_JSON_MAX_CHARS} characters and appends {@link
     * #TRUNCATION_MARKER} if the input exceeds the limit. Returns the input unchanged if within the
     * limit or if null.
     */
    private static String truncateIfNeeded(String detailJson) {
        if (detailJson == null || detailJson.length() <= DETAIL_JSON_MAX_CHARS) {
            return detailJson;
        }
        return detailJson.substring(0, DETAIL_JSON_MAX_CHARS) + TRUNCATION_MARKER;
    }
}
