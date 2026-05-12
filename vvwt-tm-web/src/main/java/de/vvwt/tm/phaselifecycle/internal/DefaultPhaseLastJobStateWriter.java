package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.PhaseLastJobStateWriter;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link PhaseLastJobStateWriter} (DEC-58, DEC-66 D-2).
 *
 * <p>Writes {@code phase.last_job_state='failed'} in a fresh {@code REQUIRES_NEW} transaction so
 * that the failure record persists even when the caller's outer transaction is rolling back (e.g.,
 * step-A or step-B TX rollback after an exception). This is the structural equivalent of the
 * pre-E55 {@code DefaultMatchGenFailureWriter} pattern cited in DEC-66 D-2.
 *
 * <p>DEC-58 mandate: public interface in module-root ({@link PhaseLastJobStateWriter}), this
 * implementation in {@code .internal} per DEC-35 naming canon ({@code Default*}).
 *
 * <p>Authorizing decisions: DEC-35 (naming canon), DEC-58 (universal interface mandate), DEC-64
 * D-12 (TX granularity: failure write survives step rollback), DEC-66 D-2 (REQUIRES_NEW failure
 * writer), AC-IMPL-LAST-JOB-STATE-STEP-FAILURE-FAILED,
 * AC-ERROR-HANDLING-FAILURE-WRITER-REQUIRES-NEW.
 *
 * @since E55S08
 */
@Service
public class DefaultPhaseLastJobStateWriter implements PhaseLastJobStateWriter {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultPhaseLastJobStateWriter.class);

    static final String FAILED_STATE = "failed";

    private final PhaseRepository phaseRepository;

    DefaultPhaseLastJobStateWriter(
            @Qualifier("tmPhaseRepository") PhaseRepository phaseRepository) {
        this.phaseRepository = phaseRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Loads the phase by {@code phaseId}, sets {@code last_job_state='failed'}, and saves. The
     * {@code REQUIRES_NEW} propagation ensures this TX commits independently of any outer TX that
     * is rolling back due to a step-A or step-B failure.
     *
     * <p>If the phase is not found (defensive case), logs a WARN and returns without error — the
     * failure record is irrelevant if the phase no longer exists.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeFailedState(UUID phaseId) {
        Phase phase = phaseRepository.findById(phaseId).orElse(null);
        if (phase == null) {
            LOG.warn(
                    "DefaultPhaseLastJobStateWriter.writeFailedState: phaseId={} not found"
                            + " — no-op (phase may have been deleted concurrently)",
                    phaseId);
            return;
        }
        phase.setLastJobState(FAILED_STATE);
        phaseRepository.save(phase);
        LOG.info(
                "DefaultPhaseLastJobStateWriter.writeFailedState: set last_job_state='failed'"
                        + " for phaseId={}",
                phaseId);
    }
}
