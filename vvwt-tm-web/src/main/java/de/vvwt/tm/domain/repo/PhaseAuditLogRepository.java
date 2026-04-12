package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.PhaseAuditLogEntry;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Append-only, tenant-scoped repository for {@link PhaseAuditLogEntry} entities (E05S07, AC5).
 *
 * <h2>Append-only guarantee (AC5)</h2>
 * <p>This repository overrides {@link #deleteById(UUID)} to throw
 * {@link UnsupportedOperationException}. The only write method is {@link #save(PhaseAuditLogEntry)}.
 *
 * @see TenantScopedRepository
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S07.story.md">Story E05S07</a>
 */
@Repository
public class PhaseAuditLogRepository extends TenantScopedRepository<PhaseAuditLogEntry, UUID> {

    private final PhaseAuditLogCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public PhaseAuditLogRepository(PhaseAuditLogCrudRepository delegate,
                                    TenantContext tenantContext,
                                    JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<PhaseAuditLogEntry, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(PhaseAuditLogEntry e) { return e.getTenantId(); }
    @Override protected void setTenantId(PhaseAuditLogEntry e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(PhaseAuditLogEntry e) { return e.getId(); }

    /**
     * Returns all phase audit log entries for the given phase, ordered by {@code changed_at} ASC.
     *
     * @param phaseId the phase whose audit entries to retrieve
     * @return list of entries in chronological order; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<PhaseAuditLogEntry> findByPhaseId(UUID phaseId) {
        UUID tenantId = activeTenantId();
        List<PhaseAuditLogEntry> result = new ArrayList<>();
        for (PhaseAuditLogEntry entry : delegate.findByPhaseIdOrderByChangedAtRaw(phaseId)) {
            if (tenantId.equals(entry.getTenantId())) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Deleting phase audit log entries is FORBIDDEN (AC5 — append-only).
     *
     * @param id the ID (ignored)
     * @throws UnsupportedOperationException always
     */
    @Override
    public void deleteById(UUID id) {
        throw new UnsupportedOperationException(
                "PhaseAuditLogRepository is append-only — deleteById is forbidden. "
                + "Story E05S07 AC5.");
    }
}
