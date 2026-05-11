package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.WorkerRegistry;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;

/**
 * Placeholder implementation of {@link WorkerRegistry} (DEC-35, DEC-58, DEC-64 D-14).
 *
 * <p>This class serves as the DEC-58 universal-interface-mandate compliance placeholder. All method
 * bodies throw {@link UnsupportedOperationException} citing the implementing Story (E55S03). Any
 * accidental production-time invocation fails fast with an operator-actionable error message.
 *
 * <p>Full implementation of the per-tournament {@code ConcurrentHashMap<UUID, ExecutorService>}
 * registry with lazy-create, idle-timeout shutdown, and JVM-shutdown {@code awaitTermination} lands
 * in E55S03.
 *
 * <p>Authorizing decisions: DEC-35 (Default* impl in .internal), DEC-58 (universal interface
 * mandate), DEC-64 D-3 (per-tournament single-thread executor lifecycle), DEC-64 D-14 (bean
 * enumeration).
 *
 * @since E55S01
 */
@Service("workerRegistry")
public class DefaultWorkerRegistry implements WorkerRegistry {

    /** {@inheritDoc} */
    @Override
    public ExecutorService getOrCreate(UUID tournamentId) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S03 for the implementing Story");
    }

    /** {@inheritDoc} */
    @Override
    public void shutdownWorker(UUID tournamentId) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S03 for the implementing Story");
    }

    /** {@inheritDoc} */
    @Override
    public void shutdownAll() {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S03 for the implementing Story");
    }
}
