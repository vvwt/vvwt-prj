package de.vvwt.info.persistence.audit;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * Append-only data access object for the {@code audit_log} table (AC10).
 *
 * <p>This interface exposes ONLY read and append operations — no {@code update*}, {@code delete*},
 * or {@code remove*} methods. This is a structural enforcement of the audit-log append-only
 * invariant, verified by {@code AuditLogDaoMethodNamesTest} via reflection.
 *
 * <p>Extends {@link Repository} (NOT {@code CrudRepository}) to prevent Spring Data JDBC from
 * exposing {@code delete} and {@code update} methods automatically. Only the explicitly declared
 * methods below are available to consumers.
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC10</a>
 */
public interface AuditLogDao extends Repository<AuditLogRecord, Long> {

    /**
     * Spring Data JDBC internal save — recognized by name convention. Callers must use {@link
     * #append(AuditLogRecord)} for semantic clarity and AC10 compliance.
     */
    AuditLogRecord save(AuditLogRecord record);

    /**
     * Appends an audit log entry. The auto-generated {@code id} is assigned by the DB.
     *
     * <p>Delegates to {@link #save(AuditLogRecord)} — the AC10 API surface exposes {@code append}
     * (not {@code save}) so that consumers cannot mistake this for a mutable update operation.
     *
     * @param record the audit log entry to append; {@code id} must be {@code null} (auto-generated)
     * @return the saved record with the auto-generated {@code id} populated
     */
    default AuditLogRecord append(AuditLogRecord record) {
        return save(record);
    }

    /**
     * Returns an audit log entry by its auto-generated primary key.
     *
     * @param id the auto-generated row identifier
     * @return the audit log entry, or empty if not found
     */
    Optional<AuditLogRecord> findById(Long id);

    /**
     * Returns an audit log entry by the caller-provided request identifier.
     *
     * @param requestId the correlation / idempotency identifier
     * @return the first matching audit log entry, or empty if not found
     */
    @Query("SELECT * FROM audit_log WHERE request_id = :requestId LIMIT 1")
    Optional<AuditLogRecord> findByRequestId(String requestId);
}
