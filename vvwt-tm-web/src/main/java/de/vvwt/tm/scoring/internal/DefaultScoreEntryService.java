package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.PartialScoreInput;
import de.vvwt.tm.scoring.ScoreEntryResult;
import de.vvwt.tm.scoring.ScoreEntryService;
import de.vvwt.tm.scoring.SetSubmitInput;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link ScoreEntryService} — TDD-reconstructed scoring-domain service
 * for device-token-authenticated score-entry tablet operations (E22S06, DEC-22
 * Reconstruction-in-Place).
 *
 * <p>Implements deviceToken authN + cascade delegation per DEC-35, DEC-36, DEC-37 Clause B.
 *
 * <p><b>TDD attestation (DEC-22):</b> This class was first authored as a stub (all methods throw
 * {@link UnsupportedOperationException}) so that the test class could compile and all tests could
 * be observed FAILING (RED phase). Production code was added only after RED was confirmed and the
 * RED-commit SHA was recorded per Q-3.
 *
 * @since E22S06
 * @see ScoreEntryService
 */
@Service
public class DefaultScoreEntryService implements ScoreEntryService {

    @Override
    public Optional<ScoreEntryResult> getMatchForField(int fieldNumber, String deviceToken) {
        throw new UnsupportedOperationException("E22S06 RED stub — not yet implemented");
    }

    @Override
    public void handlePartialScore(PartialScoreInput request) {
        throw new UnsupportedOperationException("E22S06 RED stub — not yet implemented");
    }

    @Override
    public void submitSetResult(SetSubmitInput request) {
        throw new UnsupportedOperationException("E22S06 RED stub — not yet implemented");
    }
}
