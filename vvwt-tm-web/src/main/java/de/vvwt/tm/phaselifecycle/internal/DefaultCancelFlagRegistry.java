package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.CancelFlagRegistry;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Placeholder implementation of {@link CancelFlagRegistry} (DEC-35, DEC-58, DEC-64 D-14).
 *
 * <p>This class serves as the DEC-58 universal-interface-mandate compliance placeholder. All method
 * bodies throw {@link UnsupportedOperationException} citing the implementing Story (E55S05). Any
 * accidental production-time invocation fails fast with an operator-actionable error message.
 *
 * <p>Full implementation of the cooperative cancel-flag in-memory mirror (per DEC-64 D-10) with
 * thread-safe {@code ConcurrentHashMap<UUID, Boolean>} backing lands in E55S05.
 *
 * <p>Authorizing decisions: DEC-35 (Default* impl in .internal), DEC-58 (universal interface
 * mandate), DEC-49 D-11a (Best-So-Far on cancel), DEC-64 D-10 + D-16 (cooperative cancel +
 * cancel-completion invariant), DEC-64 D-14 (bean enumeration).
 *
 * @since E55S01
 */
@Service("cancelFlagRegistry")
public class DefaultCancelFlagRegistry implements CancelFlagRegistry {

    /** {@inheritDoc} */
    @Override
    public void requestCancel(UUID tournamentId) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S05 for the implementing Story");
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCancelled(UUID tournamentId) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S05 for the implementing Story");
    }

    /** {@inheritDoc} */
    @Override
    public void clear(UUID tournamentId) {
        throw new UnsupportedOperationException(
                "Not yet implemented — see E55S05 for the implementing Story");
    }
}
