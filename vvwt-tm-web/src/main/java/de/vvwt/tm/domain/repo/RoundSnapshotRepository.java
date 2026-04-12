package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.RoundSnapshot;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link RoundSnapshot} entities.
 *
 * @see TenantScopedRepository
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@Repository
public class RoundSnapshotRepository extends TenantScopedRepository<RoundSnapshot, UUID> {

    private final RoundSnapshotCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public RoundSnapshotRepository(RoundSnapshotCrudRepository delegate,
                                    TenantContext tenantContext,
                                    JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<RoundSnapshot, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(RoundSnapshot e) { return e.getTenantId(); }
    @Override protected void setTenantId(RoundSnapshot e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(RoundSnapshot e) { return e.getId(); }

    /**
     * Finds the snapshot for a given (tournament, phase, lap) triple, scoped to the active tenant.
     *
     * <p>Used by the round-end snapshot service (E03S13, AC5) to detect existing snapshots before
     * an INSERT, implementing the "first snapshot wins" duplicate guard.
     *
     * @param tournamentId the tournament
     * @param phaseId      the phase
     * @param lapNumber    the lap number
     * @return the existing snapshot for this (tournament, phase, lap) under the active tenant,
     *         or {@link Optional#empty()} if none
     * @throws IllegalStateException if no tenant context is active
     */
    public Optional<RoundSnapshot> findByTournamentPhaseAndLap(UUID tournamentId, UUID phaseId,
                                                                int lapNumber) {
        UUID tenantId = activeTenantId();  // guard fires here — before any SQL
        return delegate.findByTournamentPhaseAndLapRaw(tournamentId, phaseId, lapNumber)
                .filter(s -> tenantId.equals(s.getTenantId()));
    }
}
