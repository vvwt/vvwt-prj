package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.PhaseLifecycleOrchestrator;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Placeholder implementation of {@link PhaseLifecycleOrchestrator} (DEC-35, DEC-58, DEC-64 D-14).
 *
 * <p>This class serves as the DEC-58 universal-interface-mandate compliance placeholder: a Spring
 * bean in the {@code .internal} package that implements the public interface declared in the module
 * root. All method bodies throw {@link UnsupportedOperationException} citing the implementing Story
 * (E55S04). Any accidental production-time invocation fails fast with an operator-actionable error
 * message.
 *
 * <p>Full implementation of the Saga-Orchestrator drain logic (MatchGen → L1+L2 → SlotOpt →
 * write-back) lands in E55S04.
 *
 * <p>Authorizing decisions: DEC-35 (Default* impl in .internal), DEC-58 (universal interface
 * mandate), DEC-64 D-1 + D-14 (Saga-Orchestrator + bean enumeration).
 *
 * @since E55S01
 */
@Service("phaseLifecycleOrchestrator")
public class DefaultPhaseLifecycleOrchestrator implements PhaseLifecycleOrchestrator {

    /** {@inheritDoc} */
    @Override
    public void tick(UUID tournamentId) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S04 for the implementing Story");
    }
}
