package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.JobDrainService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Placeholder implementation of {@link JobDrainService} (DEC-35, DEC-58, DEC-64 D-14).
 *
 * <p>This class serves as the DEC-58 universal-interface-mandate compliance placeholder. All method
 * bodies throw {@link UnsupportedOperationException} citing the implementing Story (E55S04). Any
 * accidental production-time invocation fails fast with an operator-actionable error message.
 *
 * <p>Full implementation of the drain-tick logic (claim next pending job via CAS → delegate to
 * {@link de.vvwt.tm.phaselifecycle.PhaseLifecycleOrchestrator#tick(UUID)}) lands in E55S04.
 *
 * <p>Authorizing decisions: DEC-35 (Default* impl in .internal), DEC-58 (universal interface
 * mandate), DEC-64 D-3 (worker tick loop), DEC-64 D-12 (TX granularity), DEC-64 D-14 (bean
 * enumeration).
 *
 * @since E55S01
 */
@Service("jobDrainService")
public class DefaultJobDrainService implements JobDrainService {

    /** {@inheritDoc} */
    @Override
    public void drainNext(UUID tournamentId) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S04 for the implementing Story");
    }
}
