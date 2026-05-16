package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.DraftApplicationOrchestrator;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.phaselifecycle.WorkerRegistry;
import de.vvwt.tm.tournament.DraftService;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Default implementation of {@link DraftApplicationOrchestrator} (E55S06, Option C, DEC-64 D-11).
 *
 * <p>Executes the apply-and-orchestrate workflow in a single {@code @Transactional} boundary:
 *
 * <ol>
 *   <li>Delegates phase + avatar persistence to {@link DraftService#apply(UUID, DraftConfig)}. The
 *       inner {@code @Transactional(REQUIRED)} on {@code DraftService.apply()} joins this outer TX
 *       — all operations commit atomically.
 *   <li>For each created phase ID: inserts a PENDING {@code phase_lifecycle_job} row via {@link
 *       PhaseLifecycleJobRepository#enqueueJob(PhaseLifecycleJob)} (sequence = 1-indexed per
 *       DraftSection ordering; game_mode from {@link
 *       de.vvwt.tm.tournament.draft.DraftSection#getGameMode()} — a String key after E58S01
 *       GameMode enum removal).
 *   <li>Registers an {@code afterCommit} callback via {@link TransactionSynchronizationManager}
 *       that <em>submits</em> {@link JobDrainService#drainNext(UUID)} to the per-tournament {@link
 *       WorkerRegistry} executor thread. The drain runs asynchronously in the background — the HTTP
 *       request thread returns immediately without waiting for slot-optimization. The {@code
 *       afterCommit} timing avoids H2/PostgreSQL lock-timeout conflicts: {@code drainNext()} calls
 *       {@code OrchestratorStepAExecutor.executeStepA()} which issues {@code SELECT FOR UPDATE} on
 *       the tournament table; if called inside the outer TX the DB raises a lock-timeout because
 *       the outer TX already holds a conflicting lock. If the TX rolls back, {@code afterCommit} is
 *       never fired — zero drain hints on failure.
 * </ol>
 *
 * <h2>Modulith boundary (DEC-21)</h2>
 *
 * <p>This class lives in {@code phaselifecycle.internal}. The {@code phaselifecycle} module has
 * {@code allowedDependencies = {"tournament", "slotopt", "tenant"}} — it may import {@link
 * DraftService} from {@code tournament}. The controller in {@code de.vvwt.tm.web} (DEC-40
 * primary-adapter module) injects the {@link DraftApplicationOrchestrator} interface, preserving
 * the {@code web → phaselifecycle → tournament} topology. No Modulith cycle is introduced.
 *
 * <h2>Error-handling (AC-ERROR-HANDLING-APPLY-ROLLBACK)</h2>
 *
 * <p>Any exception thrown by {@code draftService.apply()} or {@code enqueueJob()} propagates out of
 * {@code applyDraft()}, triggering the {@code @Transactional} rollback. Zero phases, zero avatars,
 * and zero job rows persist on failure. The {@code drainNext()} callback is registered after commit
 * registration succeeds (inside the TX scope) but executes only if the TX commits — so a rollback
 * also cancels the drain hint.
 *
 * <h2>Background drain via WorkerRegistry (DEC-64 D-3)</h2>
 *
 * <p>The {@code afterCommit} drain hint submits {@link JobDrainService#drainNext(UUID)} to the
 * per-tournament {@link WorkerRegistry} executor thread (not the HTTP request thread). This
 * ensures:
 *
 * <ol>
 *   <li>The HTTP response is returned immediately after TX commits — the HTTP thread is never
 *       blocked by slot-optimization (which can take minutes for large tournaments).
 *   <li>All N enqueued phase jobs are processed sequentially in the background ({@code drainNext()}
 *       loops until the queue is exhausted — see {@link JobDrainService}).
 *   <li>The per-tournament single-thread executor (WorkerRegistry) prevents concurrent drain races
 *       for the same tournament.
 * </ol>
 *
 * <h2>DEC-58 enumeration</h2>
 *
 * <p>This bean is the 6th {@code phaselifecycle} bean satisfying DEC-58
 * (universal-interface-mandate). The full set: {@code PhaseLifecycleOrchestrator}, {@code
 * PhaseLifecycleJobRepository}, {@code WorkerRegistry}, {@code JobDrainService}, {@code
 * CancelFlagRegistry}, {@code DraftApplicationOrchestrator}.
 *
 * @see DraftApplicationOrchestrator
 * @see DraftService
 * @see PhaseLifecycleJobRepository
 * @see JobDrainService
 * @see WorkerRegistry
 * @since E55S06
 */
@Service
class DefaultDraftApplicationOrchestrator implements DraftApplicationOrchestrator {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultDraftApplicationOrchestrator.class);

    private final DraftService draftService;
    private final PhaseLifecycleJobRepository jobRepository;
    private final JobDrainService jobDrainService;
    private final WorkerRegistry workerRegistry;

    DefaultDraftApplicationOrchestrator(
            @Qualifier("tmDraftService") DraftService draftService,
            PhaseLifecycleJobRepository jobRepository,
            JobDrainService jobDrainService,
            WorkerRegistry workerRegistry) {
        this.draftService = draftService;
        this.jobRepository = jobRepository;
        this.jobDrainService = jobDrainService;
        this.workerRegistry = workerRegistry;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates phase + avatar persistence to {@link DraftService#apply}, then inserts PENDING
     * {@code phase_lifecycle_job} rows for each phase, then submits a background drain task to the
     * {@link WorkerRegistry} per-tournament executor (via {@code afterCommit}). The phase + job
     * insertions execute atomically in a single {@code @Transactional} boundary; the drain runs
     * asynchronously after the TX commits.
     */
    @Override
    @Transactional
    public List<UUID> applyDraft(UUID tournamentId, DraftConfig config) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        // Step 1: delegate phase + TeamAvatar creation to tournament module (allowed direction).
        List<UUID> phaseIds = draftService.apply(tournamentId, config);

        // Step 2: enqueue one PENDING phase_lifecycle_job row per phase (DEC-64 D-11).
        // Sequence is 1-indexed to match DraftSection ordering (FIFO drain order per DEC-64 D-4).
        List<DraftSection> sections = config.getSections();
        for (int i = 0; i < sections.size(); i++) {
            UUID phaseId = phaseIds.get(i);
            // E58S01 DEC-73 D-5: getGameMode() now returns String directly (GameMode enum removed)
            String gameMode = sections.get(i).getGameMode();
            int sequence = i + 1;
            jobRepository.enqueueJob(
                    new PhaseLifecycleJob(tournamentId, phaseId, gameMode, sequence));
            LOG.debug(
                    "[phaselifecycle] DraftApplicationOrchestrator: enqueued job for"
                            + " tournament={} phase={} gameMode={} sequence={} (E55S06)",
                    tournamentId,
                    phaseId,
                    gameMode,
                    sequence);
        }

        // Step 3: best-effort immediate drain hint (DEC-64 D-11) — registered as an afterCommit
        // callback to ensure it runs AFTER the outer TX commits and all locks are released.
        //
        // Design (E55S06): the drain is submitted to the per-tournament WorkerRegistry executor
        // thread (NOT called synchronously on the HTTP request thread). This ensures:
        //   a) The HTTP response is returned immediately after TX commits — slot-optimization
        //      (which can take minutes for large tournaments) does not block the client.
        //   b) drainNext() loops until the queue is exhausted (processing all N phases) in the
        //      per-tournament background thread.
        //   c) The WorkerRegistry's single-thread-per-tournament executor prevents concurrent
        //      drain races for the same tournament.
        //
        // Rationale for afterCommit (not inline): drainNext() → OrchestratorStepAExecutor
        // issues SELECT FOR UPDATE on the tournament table. If called inside the outer TX,
        // H2/PostgreSQL raise a lock-timeout because the outer TX already holds a conflicting
        // lock. afterCommit() fires after the outer TX commits and all locks are released.
        // If the TX rolls back, afterCommit is never fired — zero drain hints on failure.
        final UUID tid = tournamentId; // effectively final for lambda
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        LOG.debug(
                                "[phaselifecycle] DraftApplicationOrchestrator: submitting"
                                        + " drainNext to WorkerRegistry (afterCommit) for"
                                        + " tournament={}",
                                tid);
                        // Submit drain to per-tournament background executor (non-blocking for
                        // HTTP thread). The executor is a single-thread-per-tournament pool
                        // managed by WorkerRegistry (DEC-64 D-3).
                        ExecutorService executor = workerRegistry.getOrCreate(tid);
                        executor.submit(() -> jobDrainService.drainNext(tid));
                    }
                });

        LOG.info(
                "[phaselifecycle] DraftApplicationOrchestrator: apply completed for"
                        + " tournament={} — {} phases enqueued (E55S06)",
                tournamentId,
                phaseIds.size());

        return phaseIds;
    }
}
