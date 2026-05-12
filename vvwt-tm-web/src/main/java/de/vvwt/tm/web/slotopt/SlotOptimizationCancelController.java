package de.vvwt.tm.web.slotopt;

import de.vvwt.tm.phaselifecycle.CancelFlagRegistry;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for slot-optimization admin-cancel and status (E27S02,
 * AC-CANCEL-CONTROLLER-AUTHORED, DEC-40 Clause A, DEC-49 D-11; E51S07
 * AC-IMPL-STATUS-ENDPOINT-EXTENSION).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>{@code POST /api/slotopt/tournaments/{tournamentId}/cancel} — cancels the active
 *       optimization for the given tournament; returns HTTP 200 with the Best-So-Far {@link
 *       OptimizationResult} on success, HTTP 409 Conflict if no optimization is active.
 *   <li>{@code GET /api/slotopt/tournaments/{tournamentId}/status} — returns the current
 *       optimization state ({@code running}, {@code idle}) with optional best-so-far score and
 *       {@code lastJobState} from {@code phase.last_job_state} (E51S07 extension).
 * </ul>
 *
 * <h2>Placement (DEC-40 Clause A)</h2>
 *
 * <p>Placed in {@code de.vvwt.tm.web.slotopt} sub-package of the {@code web} Modulith module.
 * Sub-package placement is DEC-40 Clause A-compliant (consistent with {@code web.internal.dto.*}
 * sub-package precedent; forward-compatible with future slotopt web-layer additions). No
 * {@code @ApplicationModule} declaration needed — sub-packages inherit from the parent package's
 * {@code @ApplicationModule} declaration.
 *
 * <h2>Cancel-applies-Best-So-Far (DEC-49 D-11a)</h2>
 *
 * <p>The actual Best-So-Far application to match entities happens synchronously inside {@link
 * de.vvwt.tm.slotopt.internal.DefaultCancelableInProcessSlotOptimizationService#optimize} when it
 * detects the cancellation flag. This controller triggers cancellation and returns the handle's
 * best-so-far result; the match writes occur in the compute thread's transactional scope.
 *
 * <h2>lastJobState read path (E51S07 AC-IMPL-STATUS-ENDPOINT-EXTENSION)</h2>
 *
 * <p>{@code lastJobState} is read from {@code phase.last_job_state} via {@link JdbcTemplate} using
 * the FIFO-head {@code phaseId} from the job registry (or {@code null} if no phase is queued). A
 * direct JDBC read is used instead of going through the tournament bounded-context service to avoid
 * introducing a compile-time dependency from the {@code web.slotopt} sub-package on the tournament
 * context service (DEC-21 Modulith edge avoidance).
 *
 * <h2>E55S05 cooperative cancel wiring (DEC-64 D-10)</h2>
 *
 * <p>E55S05 extends the cancel handler with two additional operations: (1) sets {@code
 * phase_lifecycle_job.cancelled=TRUE} for the currently-RUNNING row of the tournament via {@link
 * PhaseLifecycleJobRepository#markCancelled(UUID)} (DB persistence — survives JVM restart per
 * DEC-64 D-10), and (2) signals the in-memory {@link CancelFlagRegistry#requestCancel(UUID)} so the
 * orchestrator's step-B can check whether the job was cancelled (for the {@link
 * de.vvwt.tm.phaselifecycle.internal.OrchestratorStepBExecutor} clear-on-completion path). The
 * existing {@link CancellationToken#cancel()} signal on the active {@link JobHandle} continues to
 * drive the L3 permutation loop directly.
 *
 * @see SlotOptimizationJobRegistry
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-11</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-55.md">DEC-55 D-9</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-64.md">DEC-64 D-10 +
 *     D-16</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E51S07.story.md">Story
 *     E51S07</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E55S05.story.md">Story
 *     E55S05</a>
 */
@RestController
@RequestMapping("/api/slotopt/tournaments")
public class SlotOptimizationCancelController {

    private static final Logger LOG =
            LoggerFactory.getLogger(SlotOptimizationCancelController.class);

    private static final String SELECT_LAST_JOB_STATE =
            "SELECT last_job_state FROM phase WHERE id = ?";

    /**
     * E55S06: replaces peekQueue(tournamentId) (FIFO-queue removed, DEC-64 D-5). Reads the phase_id
     * for the currently-RUNNING or oldest-PENDING job for the given tournament, which serves as the
     * "FIFO head" for last_job_state reads.
     */
    private static final String SELECT_FIFO_HEAD_PHASE_ID =
            "SELECT phase_id FROM phase_lifecycle_job"
                    + " WHERE tournament_id = ? AND status IN ('RUNNING', 'PENDING')"
                    + " ORDER BY CASE status WHEN 'RUNNING' THEN 0 ELSE 1 END ASC,"
                    + " sequence ASC LIMIT 1";

    private final SlotOptimizationJobRegistry jobRegistry;
    private final JdbcTemplate jdbc;
    private final PhaseLifecycleJobRepository phaseLifecycleJobRepository;
    private final CancelFlagRegistry cancelFlagRegistry;

    /**
     * Constructs the controller with the required collaborators.
     *
     * @param jobRegistry the per-tournament job handle registry (E27S02 — Leg 3 CancellationToken
     *     signal)
     * @param jdbc the JDBC template for reading {@code phase.last_job_state}
     * @param phaseLifecycleJobRepository the phase-lifecycle job DAO for setting {@code
     *     cancelled=TRUE} on the RUNNING row (E55S05 / DEC-64 D-10)
     * @param cancelFlagRegistry the in-memory cancel flag registry for signalling the orchestrator
     *     (E55S05 / DEC-64 D-10)
     */
    public SlotOptimizationCancelController(
            SlotOptimizationJobRegistry jobRegistry,
            JdbcTemplate jdbc,
            PhaseLifecycleJobRepository phaseLifecycleJobRepository,
            CancelFlagRegistry cancelFlagRegistry) {
        this.jobRegistry = jobRegistry;
        this.jdbc = jdbc;
        this.phaseLifecycleJobRepository = phaseLifecycleJobRepository;
        this.cancelFlagRegistry = cancelFlagRegistry;
    }

    /**
     * {@code POST /api/slotopt/tournaments/{tournamentId}/cancel}
     *
     * <p>Cancels the active slot optimization for the given tournament.
     *
     * <h3>Cancel actions (in order)</h3>
     *
     * <ol>
     *   <li>Locate the active {@link JobHandle} from {@link SlotOptimizationJobRegistry} — returns
     *       409 if none (existing E27S02 contract).
     *   <li>Set {@code phase_lifecycle_job.cancelled=TRUE} for the currently-RUNNING row (DB
     *       persistence — survives JVM restart per DEC-64 D-10). No-op if no RUNNING row exists
     *       (cancel arrived after job completion — safe).
     *   <li>Set in-memory cancel flag via {@link CancelFlagRegistry#requestCancel(UUID)} — signals
     *       the orchestrator's step-B clear-on-completion path (E55S05 / DEC-64 D-10).
     *   <li>Set {@link CancellationToken#cancel()} on the active handle — signals the L3
     *       permutation loop directly (existing E27S02 mechanism, preserved verbatim).
     * </ol>
     *
     * <p>The response shape (200 with {@link OptimizationResult} on success; 409 with {@link
     * ErrorResponse}) is unchanged per DEC-64 D-13 (REST contract preservation).
     *
     * @param tournamentId the tournament whose optimization should be cancelled
     * @return HTTP 200 with the Best-So-Far result if successful; HTTP 409 if no optimization is
     *     active
     */
    @PostMapping("/{tournamentId}/cancel")
    public ResponseEntity<?> cancelOptimization(@PathVariable UUID tournamentId) {
        Optional<JobHandle> handleOpt = jobRegistry.getHandle(tournamentId);
        if (handleOpt.isEmpty()) {
            LOG.warn(
                    "SlotOptimizationCancelController: cancel requested for tournament={} but no"
                            + " active optimization found",
                    tournamentId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            new ErrorResponse(
                                    "NO_ACTIVE_OPTIMIZATION",
                                    "No active slot optimization for tournament " + tournamentId));
        }

        // Step 2 (E55S05 / DEC-64 D-10): DB persistence — set cancelled=TRUE on the RUNNING row.
        // No-op (Optional.empty()) if the row doesn't exist or isn't RUNNING (cancel arrived late).
        phaseLifecycleJobRepository
                .findRunningJobIdForTournament(tournamentId)
                .ifPresent(
                        jobId -> {
                            phaseLifecycleJobRepository.markCancelled(jobId);
                            LOG.debug(
                                    "SlotOptimizationCancelController: marked jobId={} cancelled"
                                            + " in DB (tournament={})",
                                    jobId,
                                    tournamentId);
                        });

        // Step 3 (E55S05 / DEC-64 D-10): in-memory flag — signals orchestrator's step-B
        // clear-on-completion path.
        cancelFlagRegistry.requestCancel(tournamentId);

        // Step 4: signal the L3 permutation loop via existing CancellationToken (E27S02).
        JobHandle handle = handleOpt.get();
        handle.getCancellationToken().cancel();

        LOG.info(
                "SlotOptimizationCancelController: cancel signal sent for tournament={}",
                tournamentId);

        // Return the best-so-far result (response shape unchanged per DEC-64 D-13).
        OptimizationResult bestSoFar = handle.getBestSoFar();
        if (bestSoFar == null) {
            // No permutation evaluated yet — compute thread will apply trivial coordinates
            return ResponseEntity.ok(OptimizationResult.cancelled(0L, Double.MAX_VALUE));
        }
        return ResponseEntity.ok(bestSoFar);
    }

    /**
     * {@code GET /api/slotopt/tournaments/{tournamentId}/status}
     *
     * <p>Returns the current optimization state for the given tournament, including {@code
     * lastJobState} from {@code phase.last_job_state} of the FIFO-head phase (E51S07
     * AC-IMPL-STATUS-ENDPOINT-EXTENSION).
     *
     * @param tournamentId the tournament UUID
     * @return HTTP 200 with state ({@code "running"} | {@code "idle"} | {@code "cancelled"}) +
     *     optional startedAt, bestSoFarVarietyScore, and lastJobState
     */
    @GetMapping("/{tournamentId}/status")
    public ResponseEntity<SlotOptimizationStatusResponse> getStatus(
            @PathVariable UUID tournamentId) {
        // Read lastJobState from DB for the FIFO-head phase (E51S07
        // AC-IMPL-STATUS-ENDPOINT-EXTENSION)
        String lastJobState = readLastJobState(tournamentId);

        Optional<JobHandle> handleOpt = jobRegistry.getHandle(tournamentId);
        if (handleOpt.isEmpty()) {
            return ResponseEntity.ok(SlotOptimizationStatusResponse.withLastJobState(lastJobState));
        }

        JobHandle handle = handleOpt.get();
        OptimizationResult bestSoFar = handle.getBestSoFar();
        Double score = (bestSoFar != null) ? bestSoFar.bestScore() : null;

        if (handle.getCancellationToken().isCancelled()) {
            return ResponseEntity.ok(
                    SlotOptimizationStatusResponse.cancelledWithJobState(
                            handle.getStartedAt(), score, lastJobState));
        }
        return ResponseEntity.ok(
                SlotOptimizationStatusResponse.runningWithJobState(
                        handle.getStartedAt(), score, lastJobState));
    }

    /**
     * Reads {@code phase.last_job_state} for the FIFO-head phase of the given tournament.
     *
     * <p>E55S06 (DEC-64 D-5): replaces {@code jobRegistry.peekQueue(tournamentId)} (FIFO-queue
     * in-memory removed). Uses a direct DB query against {@code phase_lifecycle_job} to find the
     * currently-RUNNING or oldest-PENDING phase (the "FIFO head"), then reads {@code
     * phase.last_job_state} for that phaseId.
     *
     * <p>Returns {@code null} if no active phase exists in the queue or if the column is null.
     *
     * @param tournamentId the tournament UUID
     * @return the last_job_state string, or {@code null}
     */
    private String readLastJobState(UUID tournamentId) {
        // E55S06: read FIFO head from DB (replaces jobRegistry.peekQueue())
        List<UUID> phaseIds =
                jdbc.query(
                        SELECT_FIFO_HEAD_PHASE_ID,
                        (rs, rowNum) -> rs.getObject(1, UUID.class),
                        tournamentId);
        if (phaseIds.isEmpty()) {
            return null;
        }
        UUID phaseId = phaseIds.get(0);
        try {
            return jdbc.queryForObject(SELECT_LAST_JOB_STATE, String.class, phaseId);
        } catch (Exception e) {
            LOG.warn(
                    "SlotOptimizationCancelController: could not read"
                            + " last_job_state for phaseId={}: {}",
                    phaseId,
                    e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Inner error response record
    // -------------------------------------------------------------------------

    /**
     * Simple error response body for 409 Conflict responses.
     *
     * @param messageKey machine-readable error key
     * @param message human-readable error message
     */
    public record ErrorResponse(String messageKey, String message) {}
}
