package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.PhaseAuditLogEntry;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link PhaseAuditLogEntry} persistence.
 * Wired into {@link PhaseAuditLogRepository} as the low-level CRUD provider.
 */
interface PhaseAuditLogCrudRepository extends CrudRepository<PhaseAuditLogEntry, UUID> {

    @Query("SELECT * FROM phase_audit_log WHERE phase_id = :phaseId ORDER BY changed_at ASC")
    List<PhaseAuditLogEntry> findByPhaseIdOrderByChangedAtRaw(@Param("phaseId") UUID phaseId);
}
