package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.OptimizationAlreadyInProgressException;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory implementation of {@link SlotOptimizationJobRegistry} backed by a {@link
 * ConcurrentHashMap} keyed by tournament UUID (E27S02, AC-JOB-REGISTRY-AUTHORED, DEC-49 D-11 +
 * T-6).
 *
 * <p>Per DEC-35 naming canon: implementation lives in {@code de.vvwt.tm.slotopt.internal},
 * interface in {@code de.vvwt.tm.slotopt} root package.
 *
 * <p>Thread-safety: {@link ConcurrentHashMap#putIfAbsent} provides atomic check-and-register;
 * {@link ConcurrentHashMap#remove} provides atomic completion. All public methods are thread-safe.
 *
 * <h2>Durability (DEC-49 T-6)</h2>
 *
 * <p>The registry is scoped to the JVM lifetime. TM restart loses all in-flight job handles.
 *
 * @see SlotOptimizationJobRegistry
 * @see JobHandle
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 T-6</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 */
@Service
public class DefaultSlotOptimizationJobRegistry implements SlotOptimizationJobRegistry {

    private final ConcurrentHashMap<UUID, JobHandle> activeJobs = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public void register(UUID tournamentId, JobHandle handle) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        if (handle == null) {
            throw new IllegalArgumentException("handle must not be null");
        }
        JobHandle existing = activeJobs.putIfAbsent(tournamentId, handle);
        if (existing != null) {
            throw new OptimizationAlreadyInProgressException(tournamentId);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<JobHandle> getHandle(UUID tournamentId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        return Optional.ofNullable(activeJobs.get(tournamentId));
    }

    /** {@inheritDoc} */
    @Override
    public void complete(UUID tournamentId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        activeJobs.remove(tournamentId);
    }
}
