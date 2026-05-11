package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Placeholder implementation of {@link PhaseLifecycleJobRepository} (DEC-35, DEC-58, DEC-64 D-14).
 *
 * <p>This class serves as the DEC-58 universal-interface-mandate compliance placeholder for the
 * hand-authored {@code @Repository} bean. All method bodies throw {@link
 * UnsupportedOperationException} citing the implementing Story (E55S02). Any accidental
 * production-time invocation fails fast with an operator-actionable error message.
 *
 * <p>Full implementation of the {@code phase_lifecycle_job} DAO with portable CAS claim mechanism
 * lands in E55S02.
 *
 * <p>Authorizing decisions: DEC-35 (Default* impl in .internal; hand-authored @Repository
 * interface), DEC-58 (universal interface mandate — hand-authored @Repository covered), DEC-64 D-4
 * (DB-durable queue schema + CAS claim), DEC-64 D-14 (bean enumeration).
 *
 * @since E55S01
 */
@Repository("phaseLifecycleJobRepository")
public class DefaultPhaseLifecycleJobRepository implements PhaseLifecycleJobRepository {

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> findNextPendingJobIdForTournament(UUID tournamentId) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S02 for the implementing Story");
    }

    /** {@inheritDoc} */
    @Override
    public boolean tryClaim(UUID jobId, String claimedBy) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S02 for the implementing Story");
    }

    /** {@inheritDoc} */
    @Override
    public void markCompleted(UUID jobId) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S02 for the implementing Story");
    }

    /** {@inheritDoc} */
    @Override
    public void markCancelled(UUID jobId) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S02 for the implementing Story");
    }

    /** {@inheritDoc} */
    @Override
    public void enqueueJob(PhaseLifecycleJob job) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S02 for the implementing Story");
    }
}
