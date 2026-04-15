package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.PhaseBreak;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link PhaseBreak} entities.
 *
 * <p>All read and write operations enforce the active tenant context via
 * {@link TenantScopedRepository}. A call without an active tenant context throws
 * {@link IllegalStateException} before any SQL is executed (AC7).
 *
 * @see TenantScopedRepository
 * @see de.vvwt.tm.domain.PhaseBreakService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S01.story.md">Story E08S01</a>
 */
@Repository
public class PhaseBreakRepository extends TenantScopedRepository<PhaseBreak, UUID> {

    private final PhaseBreakCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public PhaseBreakRepository(PhaseBreakCrudRepository delegate,
                                 TenantContext tenantContext,
                                 JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<PhaseBreak, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(PhaseBreak e) { return e.getTenantId(); }
    @Override protected void setTenantId(PhaseBreak e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(PhaseBreak e) { return e.getId(); }

    /**
     * Returns all phase breaks for the given phase that belong to the active tenant.
     *
     * <p>Used by the timeline calculation service (E08S03) and the print output (E08S08).
     *
     * @param phaseId the phase to query
     * @return list of phase breaks for the given phase scoped to the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active (AC7)
     */
    public List<PhaseBreak> findByPhaseId(UUID phaseId) {
        UUID tenantId = activeTenantId();  // guard fires here — before any SQL (AC7)
        List<PhaseBreak> result = new ArrayList<>();
        for (PhaseBreak phaseBreak : delegate.findByPhaseIdRaw(phaseId)) {
            if (tenantId.equals(phaseBreak.getTenantId())) {
                result.add(phaseBreak);
            }
        }
        return result;
    }

    /**
     * Returns the phase break at the given lap boundary within the given phase, scoped to
     * the active tenant, or {@link Optional#empty()} if no break exists at that position.
     *
     * <p>Used by {@link de.vvwt.tm.domain.PhaseBreakService} to detect duplicate entries
     * before persisting (AC6).
     *
     * @param phaseId        the phase to query
     * @param afterLapNumber the lap boundary position to check
     * @return the phase break at the given position for the active tenant, or empty
     * @throws IllegalStateException if no tenant context is active (AC7)
     */
    public Optional<PhaseBreak> findByPhaseIdAndAfterLapNumber(UUID phaseId, int afterLapNumber) {
        UUID tenantId = activeTenantId();  // guard fires here — before any SQL (AC7)
        return delegate.findByPhaseIdAndLapNumberRaw(phaseId, afterLapNumber)
                .filter(phaseBreak -> tenantId.equals(phaseBreak.getTenantId()));
    }
}
