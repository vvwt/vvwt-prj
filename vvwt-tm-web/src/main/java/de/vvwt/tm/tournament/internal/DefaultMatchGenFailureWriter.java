package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.MatchGenFailureWriter;
import de.vvwt.tm.tournament.PhaseRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code phase.last_job_state = 'failed'} in a fresh {@code REQUIRES_NEW} transaction.
 *
 * <p>Extracted from {@link MatchGenJobListener} to avoid Spring AOP self-invocation bypass: calling
 * a {@code @Transactional(REQUIRES_NEW)} method on {@code this} from within the same bean skips the
 * proxy and inherits the caller's (rolling-back) transaction. By delegating to a separate
 * Spring-managed bean, the REQUIRES_NEW propagation is honoured correctly.
 *
 * <h2>Usage</h2>
 *
 * <p>Called exclusively from {@link DefaultMatchGenJobListener#onMatchGenJobScheduled} catch block
 * after the main TX is marked for rollback (or has rolled back). Commits {@code
 * last_job_state='failed'} independently of the rolled-back main TX.
 *
 * @see DefaultMatchGenJobListener
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 * @see <a href="E51S03">E51S03 — Background-Job-Pipeline foundation</a>
 */
@Component
public class DefaultMatchGenFailureWriter implements MatchGenFailureWriter {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultMatchGenFailureWriter.class);

    private final PhaseRepository phaseRepository;

    DefaultMatchGenFailureWriter(PhaseRepository phaseRepository) {
        this.phaseRepository = phaseRepository;
    }

    /**
     * Writes {@code phase.last_job_state = 'failed'} in a new independent transaction.
     *
     * <p>Uses {@code REQUIRES_NEW} propagation to open a fresh TX regardless of any enclosing TX
     * state (which is rolling back). If the phase is not found (e.g., already deleted), the method
     * silently returns — the absence is logged instead of thrown so the executor thread is not
     * crashed.
     *
     * @param phaseId the phase UUID to mark as failed
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeFailedState(UUID phaseId) {
        phaseRepository
                .findById(phaseId)
                .ifPresentOrElse(
                        phase -> {
                            phase.setLastJobState("failed");
                            phaseRepository.save(phase);
                            LOG.info(
                                    "DefaultMatchGenFailureWriter: phase={}"
                                            + " last_job_state='failed' committed",
                                    phaseId);
                        },
                        () ->
                                LOG.warn(
                                        "DefaultMatchGenFailureWriter: phase={} not found"
                                                + " — cannot write 'failed' state",
                                        phaseId));
    }
}
