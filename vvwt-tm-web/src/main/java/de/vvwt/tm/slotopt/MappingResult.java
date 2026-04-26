package de.vvwt.tm.slotopt;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.tm.tournament.Match;
import java.util.List;

/**
 * Value object combining the forward-mapping outputs from {@link PhaseToRawPhaseDefMapper}.
 *
 * <p>Carries everything needed by the result applicator ({@link SlotResultApplicator}) to apply an
 * optimizer result back to the Tournament Manager domain:
 *
 * <ul>
 *   <li>The {@link RawPhaseDef} submitted to the optimizer.
 *   <li>The {@link CanonicalPhaseDef} produced by {@link
 *       de.vvwt.worker.types.StructuralFingerprint#canonicalize(RawPhaseDef)}.
 *   <li>{@code N}: the avatar count, as specified in AC2 of story E04S02.
 *   <li>{@code matchOrder}: the ordered list of {@link Match} entities aligned by row index in
 *       {@code raw.rows()} — needed by the applicator to write slot coordinates onto the correct
 *       match (AC3).
 *   <li>{@code denseIdsByRawRow}: for each raw row index, the two dense avatar IDs occupying that
 *       row. Needed by the applicator to look up avatar positions under the permutation without
 *       re-running canonicalization.
 * </ul>
 *
 * @param raw the raw phase definition built from TM domain objects (AC1)
 * @param canonical the canonical phase definition
 * @param avatarCount number of distinct TeamAvatars in the phase (AC2, called "N" in the story)
 * @param matchOrder ordered list of Match entities aligned by row index in {@code raw.rows()} (AC3)
 * @param denseIdsByRawRow for each raw row index {@code i}, a 2-element array {@code [denseId0,
 *     denseId1]} corresponding to the two PositionTuples in {@code raw.rows().get(i)}
 * @see PhaseToRawPhaseDefMapper
 * @see SlotResultApplicator
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E04S02.story.md">Story
 *     E04S02</a>
 */
public record MappingResult(
        RawPhaseDef raw,
        CanonicalPhaseDef canonical,
        int avatarCount,
        List<Match> matchOrder,
        int[][] denseIdsByRawRow) {

    /** Compact canonical constructor — validates all fields. */
    public MappingResult {
        if (raw == null) {
            throw new IllegalArgumentException("raw must not be null");
        }
        if (canonical == null) {
            throw new IllegalArgumentException("canonical must not be null");
        }
        if (avatarCount < 0) {
            throw new IllegalArgumentException("avatarCount must be >= 0 but was: " + avatarCount);
        }
        if (matchOrder == null) {
            throw new IllegalArgumentException("matchOrder must not be null");
        }
        if (matchOrder.size() != raw.rowCount()) {
            throw new IllegalArgumentException(
                    "matchOrder.size()="
                            + matchOrder.size()
                            + " does not match raw.rowCount()="
                            + raw.rowCount());
        }
        if (denseIdsByRawRow == null) {
            throw new IllegalArgumentException("denseIdsByRawRow must not be null");
        }
        if (denseIdsByRawRow.length != raw.rowCount()) {
            throw new IllegalArgumentException(
                    "denseIdsByRawRow.length="
                            + denseIdsByRawRow.length
                            + " does not match raw.rowCount()="
                            + raw.rowCount());
        }
        matchOrder = List.copyOf(matchOrder);
        // Defensive deep copy of 2D array
        int[][] copy = new int[denseIdsByRawRow.length][];
        for (int i = 0; i < denseIdsByRawRow.length; i++) {
            copy[i] = denseIdsByRawRow[i].clone();
        }
        denseIdsByRawRow = copy;
    }
}
