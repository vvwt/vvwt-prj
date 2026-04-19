package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.AuditLogEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Append-only, tenant-scoped repository for {@link AuditLogEntry} entities (AC5).
 *
 * <h2>Append-only guarantee (AC5)</h2>
 *
 * <p>This repository overrides {@link #deleteById(UUID)} to throw {@link
 * UnsupportedOperationException}. The only write method is {@link #save(AuditLogEntry)}.
 *
 * @see TenantScopedRepository
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story
 *     E03S05</a>
 */
@Repository
public class AuditLogRepository extends TenantScopedRepository<AuditLogEntry, UUID> {

    private final AuditLogCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public AuditLogRepository(
            AuditLogCrudRepository delegate,
            TenantContext tenantContext,
            JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override
    protected CrudRepository<AuditLogEntry, UUID> delegate() {
        return delegate;
    }

    @Override
    protected TenantContext tenantContext() {
        return tenantContext;
    }

    @Override
    protected JdbcAggregateOperations jdbcOperations() {
        return jdbcOps;
    }

    @Override
    protected UUID extractTenantId(AuditLogEntry e) {
        return e.getTenantId();
    }

    @Override
    protected void setTenantId(AuditLogEntry e, UUID id) {
        e.setTenantId(id);
    }

    @Override
    protected UUID extractId(AuditLogEntry e) {
        return e.getId();
    }

    /**
     * Returns audit entries for a given match ID and set index, ordered by {@code changed_at} ASC.
     *
     * @param matchId the match whose audit entries to retrieve
     * @param setIndex the set index within the match
     * @return list of audit entries in chronological order; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<AuditLogEntry> findByMatchIdAndSetIndexOrderByChangedAt(
            UUID matchId, int setIndex) {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        List<AuditLogEntry> result = new ArrayList<>();
        for (AuditLogEntry entry :
                delegate.findByMatchIdAndSetIndexOrderByChangedAtRaw(matchId, setIndex)) {
            if (tenantId.equals(entry.getTenantId())) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Deleting audit log entries is FORBIDDEN (AC5 — append-only).
     *
     * @param id the ID (ignored)
     * @throws UnsupportedOperationException always
     */
    @Override
    public void deleteById(UUID id) {
        throw new UnsupportedOperationException(
                "AuditLogRepository is append-only — deleteById is forbidden. "
                        + "Story E03S05 AC5.");
    }
}
