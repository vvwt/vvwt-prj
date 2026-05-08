package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.events.SlotOptJobScheduledEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes the Match-Gen job for a single phase in a dedicated {@code REQUIRES_NEW} transaction.
 *
 * <p>Extracted from {@link MatchGenJobListener} to avoid Spring AOP transaction-commit semantics
 * from leaking {@link org.springframework.transaction.UnexpectedRollbackException} into the
 * {@code @Async} caller: when a {@code @Transactional(REQUIRES_NEW)} method catches its own
 * exception and returns normally, the TX is still rollback-only — Spring will throw {@code
 * UnexpectedRollbackException} during commit. By extracting the transactional work into a separate
 * component:
 *
 * <ol>
 *   <li>The {@code @Async} listener ({@link MatchGenJobListener}) is NOT {@code @Transactional}.
 *   <li>This executor's {@code execute()} is {@code @Transactional(REQUIRES_NEW)} — its TX is
 *       isolated and any exception propagates cleanly to the listener's catch block WITHOUT leaving
 *       a rollback-only marker on the listener method's stack frame.
 *   <li>The listener's catch block calls {@link MatchGenFailureWriter#writeFailedState} in yet
 *       another {@code REQUIRES_NEW} TX to commit the {@code 'failed'} state.
 * </ol>
 *
 * @see MatchGenJobListener
 * @see MatchGenFailureWriter
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="E51S03">E51S03 — Background-Job-Pipeline foundation</a>
 */
@Component
class MatchGenJobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(MatchGenJobExecutor.class);

    private final PhasePreparationService phasePreparationService;
    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final ApplicationEventPublisher eventPublisher;

    MatchGenJobExecutor(
            PhasePreparationService phasePreparationService,
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            ApplicationEventPublisher eventPublisher) {
        this.phasePreparationService = phasePreparationService;
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Executes the match-gen job for the given phase in a fresh transaction.
     *
     * <p>Steps (per DEC-55 D-3):
     *
     * <ol>
     *   <li>Acquire per-tournament row-lock (DEC-37 Clause B).
     *   <li>Load phase; idempotency check ({@code last_job_state='idle'} → skip).
     *   <li>Invoke {@link PhasePreparationService#generateMatches(UUID, String)}.
     *   <li>Set {@code phase.last_job_state='idle'}.
     *   <li>If {@code tournament.optimize=true}: publish {@link SlotOptJobScheduledEvent}.
     * </ol>
     *
     * <p>On success the transaction commits. On exception the transaction rolls back and the
     * exception propagates to the caller ({@link MatchGenJobListener}) for failure handling.
     *
     * @param tournamentId the tournament UUID (for row-lock and optimize flag)
     * @param phaseId the phase UUID (for match-generation)
     * @throws RuntimeException if match-generation fails; propagates to listener for failure write
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(UUID tournamentId, UUID phaseId) {
        // Step 1: DEC-37 Clause B — acquire per-tournament row-lock as the FIRST read
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);

        // Step 2: Load phase
        Phase phase =
                phaseRepository
                        .findById(phaseId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Phase not found: " + phaseId));

        // Step 3: Idempotency check — skip if already 'idle'
        if ("idle".equals(phase.getLastJobState())) {
            LOG.info(
                    "MatchGenJobExecutor: phase={} last_job_state='idle' — skipping (idempotent)",
                    phaseId);
            return;
        }

        // Step 4: Invoke match-generation
        // Generator key = tournament.matchGeneratorId (stored on the tournament aggregate)
        String generatorKey = tournament.getMatchGeneratorId();
        phasePreparationService.generateMatches(phaseId, generatorKey);

        // Step 5: Set last_job_state = 'idle' on success
        phase.setLastJobState("idle");
        phaseRepository.save(phase);

        LOG.info(
                "MatchGenJobExecutor: SUCCESS tournamentId={}, phaseId={}, generatorKey={}",
                tournamentId,
                phaseId,
                generatorKey);

        // Step 6: Publish SlotOptJobScheduledEvent if tournament.optimize=true (DEC-55 D-3)
        if (tournament.isOptimize()) {
            SlotOptJobScheduledEvent slotOptEvent =
                    new SlotOptJobScheduledEvent(tournamentId, phaseId);
            eventPublisher.publishEvent(slotOptEvent);
            LOG.info(
                    "MatchGenJobExecutor: published SlotOptJobScheduledEvent"
                            + " tournamentId={}, phaseId={}",
                    tournamentId,
                    phaseId);
        }
    }
}
