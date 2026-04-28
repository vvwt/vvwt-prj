package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.CancelableInProcessSlotOptimizationService;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.DirectSlotOptimizationClient;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.tournament.Match;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Canonical {@link SlotOptimizationClient} entry point for Tournament Manager business code (E27S01
 * + E27S02 — Leg 1 + Leg 3 routing).
 *
 * <p>This class is the {@code @Primary @Service} bean registered as the project-wide canonical
 * {@link SlotOptimizationClient} implementation from E27S01 forward.
 *
 * <h2>Three-leg routing (DEC-49 D-3)</h2>
 *
 * <ul>
 *   <li><strong>Leg 1 (E27S01):</strong> N &le; {@code tm.slotopt.exhaustive-max-n} (default 10) →
 *       delegate to {@link DirectSlotOptimizationClient} (exhaustive in-process).
 *   <li><strong>Leg 3 (E27S02):</strong> N &gt; threshold → register job in {@link
 *       SlotOptimizationJobRegistry} + call {@link
 *       CancelableInProcessSlotOptimizationService#optimize} (cancelable in-process; offline-first
 *       per DEC-15).
 *   <li><strong>Leg 2 (E27S03 — not yet):</strong> N &gt; threshold AND dispatcher reachable → HTTP
 *       submit to vvwt-dispatcher (per DEC-11 + DEC-43). Leg 2 routing is NOT introduced in this
 *       story (AC-NO-LEG-2-YET).
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
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S01.story.md">Story
 *     E27S01</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 */
@Primary
@Service
public class RoutingSlotOptimizationClient implements SlotOptimizationClient {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingSlotOptimizationClient.class);

    private final DirectSlotOptimizationClient directClient;
    private final CancelableInProcessSlotOptimizationService cancelableService;
    private final SlotOptimizationJobRegistry jobRegistry;
    private final PhaseToRawPhaseDefMapper mapper;
    private final int exhaustiveMaxN;

    /**
     * Constructs the routing client with all dependencies for Leg 1 + Leg 3 (E27S02).
     *
     * @param directClient the exhaustive in-process client (Leg 1); injected by concrete class to
     *     avoid circular dependency (DEC-49 Brief O-14)
     * @param cancelableService the Leg 3 cancelable in-process service
     * @param jobRegistry the per-tournament job handle registry
     * @param mapper the phase-to-raw-phase-def mapper (used to derive N from phaseId)
     * @param exhaustiveMaxN maximum N for Leg 1; sourced from {@code tm.slotopt.exhaustive-max-n}
     *     (default 10) per DEC-49 D-3
     */
    public RoutingSlotOptimizationClient(
            DirectSlotOptimizationClient directClient,
            CancelableInProcessSlotOptimizationService cancelableService,
            SlotOptimizationJobRegistry jobRegistry,
            PhaseToRawPhaseDefMapper mapper,
            @Value("${tm.slotopt.exhaustive-max-n:10}") int exhaustiveMaxN) {
        this.directClient = directClient;
        this.cancelableService = cancelableService;
        this.jobRegistry = jobRegistry;
        this.mapper = mapper;
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
     *   <li>N &gt; {@code exhaustiveMaxN}: register a new {@link JobHandle} in {@link
     *       SlotOptimizationJobRegistry}, call {@link
     *       CancelableInProcessSlotOptimizationService#optimize} (Leg 3), complete registry on
     *       finish or exception.
     * </ol>
     *
     * <p><strong>No Leg 2 (E27S03):</strong> dispatcher reachability check is NOT performed in this
     * story (AC-NO-LEG-2-YET). All N &gt; threshold traffic uses Leg 3.
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

        // Leg 3: cancelable in-process (N > threshold, no dispatcher check in E27S02)
        // Derive tournamentId from the first match in the mapping result
        List<Match> matchOrder = mapping.matchOrder();
        UUID tournamentId = matchOrder.isEmpty() ? null : matchOrder.get(0).getTournamentId();

        LOG.info(
                "RoutingSlotOptimizationClient: phase={}, N={} > threshold={} → Leg 3"
                        + " (tournament={})",
                phaseId,
                n,
                exhaustiveMaxN,
                tournamentId);

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
