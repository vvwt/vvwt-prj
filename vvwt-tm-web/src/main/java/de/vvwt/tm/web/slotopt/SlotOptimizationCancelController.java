package de.vvwt.tm.web.slotopt;

import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for slot-optimization admin-cancel and status (E27S02,
 * AC-CANCEL-CONTROLLER-AUTHORED, DEC-40 Clause A, DEC-49 D-11).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>{@code POST /api/slotopt/tournaments/{tournamentId}/cancel} — cancels the active
 *       optimization for the given tournament; returns HTTP 200 with the Best-So-Far {@link
 *       OptimizationResult} on success, HTTP 409 Conflict if no optimization is active.
 *   <li>{@code GET /api/slotopt/tournaments/{tournamentId}/status} — returns the current
 *       optimization state ({@code running}, {@code idle}) with optional best-so-far score.
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
 * @see SlotOptimizationJobRegistry
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-11</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 */
@RestController
@RequestMapping("/api/slotopt/tournaments")
public class SlotOptimizationCancelController {

    private static final Logger LOG =
            LoggerFactory.getLogger(SlotOptimizationCancelController.class);

    private final SlotOptimizationJobRegistry jobRegistry;

    /**
     * Constructs the controller with the required job registry.
     *
     * @param jobRegistry the per-tournament job handle registry
     */
    public SlotOptimizationCancelController(SlotOptimizationJobRegistry jobRegistry) {
        this.jobRegistry = jobRegistry;
    }

    /**
     * {@code POST /api/slotopt/tournaments/{tournamentId}/cancel}
     *
     * <p>Cancels the active slot optimization for the given tournament. Sets the cancellation flag
     * on the active {@link CancellationToken}; the compute thread observes the flag and applies the
     * Best-So-Far result via {@link de.vvwt.tm.slotopt.SlotResultApplicator}.
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

        JobHandle handle = handleOpt.get();
        handle.getCancellationToken().cancel();

        LOG.info(
                "SlotOptimizationCancelController: cancel signal sent for tournament={}",
                tournamentId);

        // Return the best-so-far result (the compute thread will apply it to matches
        // asynchronously)
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
     * <p>Returns the current optimization state for the given tournament.
     *
     * @param tournamentId the tournament UUID
     * @return HTTP 200 with state ({@code "running"} | {@code "idle"} | {@code "cancelled"}) +
     *     optional startedAt and bestSoFarVarietyScore
     */
    @GetMapping("/{tournamentId}/status")
    public ResponseEntity<SlotOptimizationStatusResponse> getStatus(
            @PathVariable UUID tournamentId) {
        Optional<JobHandle> handleOpt = jobRegistry.getHandle(tournamentId);
        if (handleOpt.isEmpty()) {
            return ResponseEntity.ok(SlotOptimizationStatusResponse.idle());
        }

        JobHandle handle = handleOpt.get();
        OptimizationResult bestSoFar = handle.getBestSoFar();
        Double score = (bestSoFar != null) ? bestSoFar.bestScore() : null;

        if (handle.getCancellationToken().isCancelled()) {
            return ResponseEntity.ok(
                    SlotOptimizationStatusResponse.cancelled(handle.getStartedAt(), score));
        }
        return ResponseEntity.ok(
                SlotOptimizationStatusResponse.running(handle.getStartedAt(), score));
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
