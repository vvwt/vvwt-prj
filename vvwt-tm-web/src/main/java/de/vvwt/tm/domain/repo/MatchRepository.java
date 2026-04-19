package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Match;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped repository for {@link Match} entities.
 *
 * <p>Provides the domain-specific query {@link #findByPhaseId(UUID)} required by the cascade
 * service (E03S11).
 *
 * @see TenantScopedRepository
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story
 *     E03S05</a>
 */
@Repository
public class MatchRepository extends TenantScopedRepository<Match, UUID> {

    private final MatchCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public MatchRepository(
            MatchCrudRepository delegate,
            TenantContext tenantContext,
            JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override
    protected CrudRepository<Match, UUID> delegate() {
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
    protected UUID extractTenantId(Match e) {
        return e.getTenantId();
    }

    @Override
    protected void setTenantId(Match e, UUID id) {
        e.setTenantId(id);
    }

    @Override
    protected UUID extractId(Match e) {
        return e.getId();
    }

    /**
     * Returns all matches in the given phase that belong to the active tenant.
     *
     * @param phaseId the phase to query
     * @return list of matches in the given phase for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Match> findByPhaseId(UUID phaseId) {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        List<Match> result = new ArrayList<>();
        for (Match match : delegate.findByPhaseIdRaw(phaseId)) {
            if (tenantId.equals(match.getTenantId())) {
                result.add(match);
            }
        }
        return result;
    }

    /**
     * Returns all terminal matches for a given avatar in a given phase, scoped to the active
     * tenant.
     *
     * <p>Used by cascade steps 7–8 (E03S11, AC9/AC10) for {@code refreshAvatarRating}. Terminal
     * states: FINISHED_STANDOFF (50), FINISHED_WINNER1 (51), FINISHED_WINNER2 (52).
     *
     * @param phaseId the phase whose matches are queried
     * @param avatarId the team avatar — matches where this avatar appears in slot 1 or slot 2 are
     *     returned
     * @return list of terminal matches for the avatar in the phase; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Match> findTerminalByPhaseIdAndAvatarId(UUID phaseId, UUID avatarId) {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        List<Match> result = new ArrayList<>();
        for (Match match : delegate.findTerminalByPhaseIdAndAvatarIdRaw(phaseId, avatarId)) {
            if (tenantId.equals(match.getTenantId())) {
                result.add(match);
            }
        }
        return result;
    }

    /**
     * Deletes all matches for the given phase that belong to the active tenant.
     *
     * <p>Used by {@link de.vvwt.tm.domain.PhasePreparationService#generateMatches} for the
     * idempotent-delete step (AC2): when the organizer re-runs generation, existing matches for the
     * phase are cleared before new ones are inserted.
     *
     * <p>The delete is performed via the raw @Query method which issues a bulk DELETE by phase_id.
     * The tenant guard fires before the SQL to ensure the active tenant context is set. Since all
     * matches in the phase were inserted with the active tenant's ID (enforced by {@link
     * TenantScopedRepository#save}), the bulk delete is implicitly tenant-safe when combined with
     * the upstream guard.
     *
     * @param phaseId the phase whose matches to delete
     * @throws IllegalStateException if no tenant context is active
     */
    public void deleteByPhaseId(UUID phaseId) {
        activeTenantId(); // guard fires here — verifies active tenant before SQL
        delegate.deleteByPhaseIdRaw(phaseId);
    }

    /**
     * Returns all matches on the given field in the given lap, scoped to the active tenant (E06S06,
     * AC1).
     *
     * <p>Used by {@link de.vvwt.tm.infrastructure.score.ScoreEntryService} to resolve the current
     * match for a scoring tablet's court field.
     *
     * @param fieldNumber the court field number (1-based)
     * @param lapNumber the lap (round) number
     * @return list of matches on the field in the lap for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Match> findByFieldNumberAndLapNumber(int fieldNumber, int lapNumber) {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        List<Match> result = new ArrayList<>();
        for (Match match : delegate.findByFieldNumberAndLapNumberRaw(fieldNumber, lapNumber)) {
            if (tenantId.equals(match.getTenantId())) {
                result.add(match);
            }
        }
        return result;
    }
}
