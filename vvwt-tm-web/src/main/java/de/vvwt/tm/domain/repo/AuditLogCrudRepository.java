package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.AuditLogEntry;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link AuditLogEntry} persistence.
 * Wired into {@link AuditLogRepository} as the low-level CRUD provider.
 */
interface AuditLogCrudRepository extends CrudRepository<AuditLogEntry, UUID> {
    @Query("SELECT * FROM audit_log WHERE match_id = :matchId AND set_index = :setIndex "
           + "ORDER BY changed_at ASC")
    List<AuditLogEntry> findByMatchIdAndSetIndexOrderByChangedAtRaw(
            @Param("matchId") UUID matchId, @Param("setIndex") int setIndex);
}
