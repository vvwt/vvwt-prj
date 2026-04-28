package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.DirectSlotOptimizationClient;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Canonical {@link SlotOptimizationClient} entry point for Tournament Manager business code
 * (E27S01, Leg 1 — foundation story).
 *
 * <p>This class is the {@code @Primary @Service} bean registered as the project-wide canonical
 * {@link SlotOptimizationClient} implementation from E27S01 forward. Future legs (E27S02 Leg 3,
 * E27S03 Leg 2) will extend {@link #optimize(UUID)} by adding routing logic based on N and
 * dispatcher reachability; at E27S01 the body is a pure delegation to {@link
 * DirectSlotOptimizationClient} for ALL N values.
 *
 * <h2>Net behavior change at E27S01</h2>
 *
 * <p>Zero. {@link DirectSlotOptimizationClient} continues to handle all N values as before:
 * N&nbsp;&lt;=&nbsp;{@code tm.slotopt.exhaustive-max-n} (default&nbsp;10) runs the exhaustive
 * search; N&nbsp;&gt;&nbsp;threshold throws {@link UnsupportedOperationException} (to be replaced
 * in E27S02/S03).
 *
 * <h2>Bean wiring</h2>
 *
 * <p>{@code @Primary} makes this bean the preferred {@link SlotOptimizationClient} for any
 * {@code @Autowired} injection point. {@link DirectSlotOptimizationClient} is NOT {@code @Primary}
 * and therefore is never injected into call sites that declare {@link SlotOptimizationClient}.
 * {@link de.vvwt.tm.slotopt.FallbackSlotOptimizationClient FallbackSlotOptimizationClient}'s
 * {@code @ConditionalOnMissingBean} evaluates to {@code false} (two {@link SlotOptimizationClient}
 * beans now exist: Direct + Routing) and the fallback stays disabled.
 *
 * <h2>Injection of concrete class (DEC-49 / Brief O-14)</h2>
 *
 * <p>{@link DirectSlotOptimizationClient} is injected by its concrete class, NOT via the {@link
 * SlotOptimizationClient} interface. Injecting the interface would cause Spring to pick THIS bean
 * ({@code @Primary}) for the parameter, creating a circular dependency. Concrete-class injection
 * avoids the cycle while keeping Direct fully functional (DEC-49, Brief O-14).
 *
 * <h2>Package placement (DEC-35)</h2>
 *
 * <p>Placed in {@code de.vvwt.tm.slotopt.internal} per DEC-35 naming canon. The {@code Routing*}
 * prefix is an accepted non-{@code Default*} variant when the implementation name expresses a
 * behavioral category beyond "the default impl" — Spring's own {@code AbstractRoutingDataSource} is
 * the canonical Spring-idiom precedent.
 *
 * @see SlotOptimizationClient
 * @see DirectSlotOptimizationClient
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S01.story.md">Story
 *     E27S01</a>
 */
@Primary
@Service
public class RoutingSlotOptimizationClient implements SlotOptimizationClient {

    private final DirectSlotOptimizationClient directClient;

    /**
     * Constructs the routing client.
     *
     * @param directClient the in-process exhaustive slot-optimization client; injected by concrete
     *     class (NOT by {@link SlotOptimizationClient} interface) to avoid an autowiring-resolution
     *     cycle — see Brief O-14 / DEC-49
     */
    public RoutingSlotOptimizationClient(DirectSlotOptimizationClient directClient) {
        this.directClient = directClient;
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Leg 1 (E27S01) — pure delegation:</strong> delegates unconditionally to {@link
     * DirectSlotOptimizationClient#optimize(UUID)}. No branching on N is introduced here; routing
     * logic for Leg 2 (dispatcher) and Leg 3 (cancelable in-process) will be added in E27S03 and
     * E27S02 respectively.
     *
     * @param phaseId the phase whose matches should receive slot assignments
     */
    @Override
    public void optimize(UUID phaseId) {
        directClient.optimize(phaseId);
    }
}
