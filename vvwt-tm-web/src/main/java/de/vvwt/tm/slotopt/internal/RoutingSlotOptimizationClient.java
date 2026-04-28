package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.CancelableInProcessSlotOptimizationService;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.DirectSlotOptimizationClient;
import de.vvwt.tm.slotopt.DispatcherAlgorithmMismatchException;
import de.vvwt.tm.slotopt.DispatcherReachabilityService;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.slotopt.SlotOptimizationDispatcherClient;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.tournament.Match;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Canonical {@link SlotOptimizationClient} entry point for Tournament Manager business code (E27S01
 * + E27S02 + E27S03 — Leg 1, Leg 3, and Leg 2 routing).
 *
 * <p>This class is the {@code @Primary @Service} bean registered as the project-wide canonical
 * {@link SlotOptimizationClient} implementation from E27S01 forward.
 *
 * <h2>Three-leg routing (DEC-49 D-3)</h2>
 *
 * <ul>
 *   <li><strong>Leg 1 (E27S01):</strong> N &le; {@code tm.slotopt.exhaustive-max-n} (default 10) →
 *       delegate to {@link DirectSlotOptimizationClient} (exhaustive in-process).
 *   <li><strong>Leg 2 (E27S03):</strong> N &gt; threshold AND dispatcher reachable → HTTP submit to
 *       vvwt-slotopt-dispatcher via {@link SlotOptimizationDispatcherClient}. On any wire error
 *       (algorithm mismatch, network error, poll timeout), falls through to Leg 3
 *       (AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR).
 *   <li><strong>Leg 3 (E27S02):</strong> N &gt; threshold AND dispatcher NOT reachable (or Leg 2
 *       fails) → register job in {@link SlotOptimizationJobRegistry} + call {@link
 *       CancelableInProcessSlotOptimizationService#optimize} (cancelable in-process; offline-first
 *       per DEC-15).
 * </ul>
 *
 * <h2>Bean wiring</h2>
 *
 * <p>{@code @Primary} makes this bean the preferred {@link SlotOptimizationClient} for any
 * {@code @Autowired} injection point. {@link DirectSlotOptimizationClient} is injected by its
 * concrete class (NOT by {@link SlotOptimizationClient} interface) to avoid an
 * autowiring-resolution cycle (DEC-49, Brief O-14).
 *
 * @see SlotOptimizationClient
 * @see DirectSlotOptimizationClient
 * @see CancelableInProcessSlotOptimizationService
 * @see SlotOptimizationJobRegistry
 * @see SlotOptimizationDispatcherClient
 * @see DispatcherReachabilityService
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S01.story.md">Story
 *     E27S01</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S03.story.md">Story
 *     E27S03</a>
 */
@Primary
@Service
public class RoutingSlotOptimizationClient implements SlotOptimizationClient {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingSlotOptimizationClient.class);

    private final DirectSlotOptimizationClient directClient;
    private final CancelableInProcessSlotOptimizationService cancelableService;
    private final SlotOptimizationJobRegistry jobRegistry;
    private final PhaseToRawPhaseDefMapper mapper;
    private final DispatcherReachabilityService reachabilityService;
    private final SlotOptimizationDispatcherClient dispatcherClient;
    private final int exhaustiveMaxN;

    /**
     * Constructs the routing client with all dependencies for Leg 1, Leg 2, and Leg 3 (E27S01,
     * E27S03, E27S02).
     *
     * @param directClient the exhaustive in-process client (Leg 1); injected by concrete class to
     *     avoid circular dependency (DEC-49 Brief O-14)
     * @param cancelableService the Leg 3 cancelable in-process service
     * @param jobRegistry the per-tournament job handle registry
     * @param mapper the phase-to-raw-phase-def mapper (used to derive N from phaseId)
     * @param reachabilityService checks if the dispatcher is reachable before attempting Leg 2
     * @param dispatcherClient the HTTP client for Leg 2 dispatcher submission (E27S03)
     * @param exhaustiveMaxN maximum N for Leg 1; sourced from {@code tm.slotopt.exhaustive-max-n}
     *     (default 10) per DEC-49 D-3
     */
    public RoutingSlotOptimizationClient(
            DirectSlotOptimizationClient directClient,
            CancelableInProcessSlotOptimizationService cancelableService,
            SlotOptimizationJobRegistry jobRegistry,
            PhaseToRawPhaseDefMapper mapper,
            DispatcherReachabilityService reachabilityService,
            SlotOptimizationDispatcherClient dispatcherClient,
            @Value("${tm.slotopt.exhaustive-max-n:10}") int exhaustiveMaxN) {
        this.directClient = directClient;
        this.cancelableService = cancelableService;
        this.jobRegistry = jobRegistry;
        this.mapper = mapper;
        this.reachabilityService = reachabilityService;
        this.dispatcherClient = dispatcherClient;
        this.exhaustiveMaxN = exhaustiveMaxN;
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Routing (DEC-49 D-3):</strong>
     *
     * <ol>
     *   <li>Derive N = {@code canonicalPhaseDef.rowCount()} via {@link PhaseToRawPhaseDefMapper}.
     *   <li>N &le; {@code exhaustiveMaxN}: delegate to {@link DirectSlotOptimizationClient} (Leg
     *       1).
     *   <li>N &gt; {@code exhaustiveMaxN} AND dispatcher reachable: attempt Leg 2 (HTTP dispatcher
     *       submission). On any wire error (algorithm mismatch, network error, poll timeout), falls
     *       through to Leg 3 (AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR).
     *   <li>N &gt; {@code exhaustiveMaxN} AND dispatcher NOT reachable (or Leg 2 failed): register
     *       a new {@link JobHandle} in {@link SlotOptimizationJobRegistry}, call {@link
     *       CancelableInProcessSlotOptimizationService#optimize} (Leg 3), complete registry on
     *       finish or exception.
     * </ol>
     *
     * @param phaseId the phase whose matches should receive slot assignments
     */
    @Override
    public void optimize(UUID phaseId) {
        // Derive N by mapping the phase (same mapper used by DirectSlotOptimizationClient)
        MappingResult mapping = mapper.map(phaseId);
        int n = mapping.canonical().rowCount();

        if (n <= exhaustiveMaxN) {
            // Leg 1: exhaustive in-process (N <= threshold)
            LOG.debug(
                    "RoutingSlotOptimizationClient: phase={}, N={} <= threshold={} → Leg 1",
                    phaseId,
                    n,
                    exhaustiveMaxN);
            directClient.optimize(phaseId);
            return;
        }

        // Derive tournamentId from the first match in the mapping result
        List<Match> matchOrder = mapping.matchOrder();
        UUID tournamentId = matchOrder.isEmpty() ? null : matchOrder.get(0).getTournamentId();

        // Leg 2: attempt dispatcher routing when reachable (E27S03 — AC-ROUTING-EXTENDED-LEG-2)
        if (reachabilityService.isReachable()) {
            LOG.info(
                    "RoutingSlotOptimizationClient: phase={}, N={} > threshold={}, dispatcher"
                            + " reachable → attempting Leg 2 (tournament={})",
                    phaseId,
                    n,
                    exhaustiveMaxN,
                    tournamentId);
            boolean leg2Succeeded = tryLeg2(phaseId, mapping);
            if (leg2Succeeded) {
                return;
            }
            LOG.info(
                    "RoutingSlotOptimizationClient: Leg 2 failed for phase={} → falling through to"
                            + " Leg 3",
                    phaseId);
        } else {
            LOG.info(
                    "RoutingSlotOptimizationClient: phase={}, N={} > threshold={}, dispatcher NOT"
                            + " reachable → Leg 3 (tournament={})",
                    phaseId,
                    n,
                    exhaustiveMaxN,
                    tournamentId);
        }

        // Leg 3: cancelable in-process fallback
        executeLeg3(phaseId, tournamentId);
    }

    /**
     * Attempts Leg 2: submits the job to the dispatcher and polls for result.
     *
     * <p>Per AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR: on any error (algorithm mismatch, network error,
     * poll timeout), returns {@code false} so the caller falls through to Leg 3.
     *
     * @param phaseId the phase being optimized
     * @param mapping the mapping result providing the raw phase def for submission
     * @return {@code true} if Leg 2 succeeded and produced a result; {@code false} to fall through
     *     to Leg 3
     */
    private boolean tryLeg2(UUID phaseId, MappingResult mapping) {
        try {
            UUID jobId = dispatcherClient.submitJob(mapping.raw());
            Optional<int[]> result = dispatcherClient.pollResult(jobId);
            if (result.isEmpty()) {
                // Poll timeout — fall through to Leg 3
                LOG.warn(
                        "RoutingSlotOptimizationClient.tryLeg2: poll timeout for phase={}, job={}",
                        phaseId,
                        jobId);
                return false;
            }
            LOG.info(
                    "RoutingSlotOptimizationClient.tryLeg2: success for phase={}, job={}",
                    phaseId,
                    jobId);
            return true;
        } catch (DispatcherAlgorithmMismatchException e) {
            // AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR: algorithm mismatch → fall through to Leg 3
            LOG.warn(
                    "RoutingSlotOptimizationClient.tryLeg2: algorithm mismatch for phase={},"
                            + " algorithmId={}, httpStatus={} → falling through to Leg 3",
                    phaseId,
                    e.getAlgorithmId(),
                    e.getHttpStatus());
            return false;
        } catch (RuntimeException e) {
            // AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR: wire error (network, HTTP 5xx) → fall through
            LOG.warn(
                    "RoutingSlotOptimizationClient.tryLeg2: wire error for phase={}: {} → falling"
                            + " through to Leg 3",
                    phaseId,
                    e.getMessage());
            return false;
        }
    }

    /**
     * Executes Leg 3: cancelable in-process optimization with job handle registration.
     *
     * <p>The registry is always completed (in {@code finally}) to prevent handle leaks, even if the
     * optimization throws (AC-ROUTING-EXTENDED-LEG-3).
     *
     * @param phaseId the phase being optimized
     * @param tournamentId the tournament owning the phase (used for registry keying)
     */
    private void executeLeg3(UUID phaseId, UUID tournamentId) {
        CancellationToken token = CancellationToken.create();
        JobHandle handle = new JobHandle(token, Instant.now());
        jobRegistry.register(tournamentId, handle);
        try {
            cancelableService.optimize(phaseId, tournamentId, token);
        } finally {
            // Always complete the registry (removes handle regardless of success or exception)
            jobRegistry.complete(tournamentId);
        }
    }
}
