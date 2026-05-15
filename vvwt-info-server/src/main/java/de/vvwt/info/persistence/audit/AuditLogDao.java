package de.vvwt.info.persistence.audit;

import org.springframework.data.repository.Repository;

/**
 * Append-only data access object for the {@code audit_log} table (AC10).
 *
 * <p>This interface exposes ONLY append operations — no {@code update*}, {@code delete*}, {@code
 * remove*}, or read methods (DEC-69 E18S04). This is a structural enforcement of the audit-log
 * append-only invariant, verified by {@code AuditLogDaoMethodNamesTest} via reflection.
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
}
