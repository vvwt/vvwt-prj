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
 *   <li>The {@link RawPhaseDef} submitted to the optimizer. After E54S02 (DEC-61 Clause B), {@code
 *       raw.rowCount() = lapCount} — one {@code RawRow} per lap (not per match). Before E54S02,
 *       {@code raw.rowCount() = matchCount}; the invariant has been relaxed accordingly.
 *   <li>The {@link CanonicalPhaseDef} produced by {@link
 *       de.vvwt.slotopt.worker.types.StructuralFingerprint#transform(RawPhaseDef)}.
 *   <li>{@code N}: the avatar count, as specified in AC2 of story E04S02.
 *   <li>{@code matchOrder}: the complete ordered list of {@link Match} entities for the phase (or
 *       group subset). {@code matchOrder.size() = matchCount} (all matches, not lap-count). The
 *       applicator ({@link SlotResultApplicator}) uses this list to assign lap/field coordinates.
 *   <li>{@code denseIdsByRawRow}: for each raw <em>lap</em>-row index {@code i}, the dense avatar
 *       IDs occupying that lap. After E54S02, a lap-row may carry more than 2 PositionTuples (union
 *       of all active avatars in the lap). Array length = {@code raw.rowCount() = lapCount}.
 * </ul>
 *
 * <h2>Post-E54S02 shape change (DEC-61 Clause B)</h2>
 *
 * <p>Before E54S02: {@code raw.rowCount() = matchCount}; {@code matchOrder.size() = raw.rowCount()}
 * (invariant enforced in constructor).
 *
 * <p>After E54S02: {@code raw.rowCount() = lapCount}; {@code matchOrder.size() = matchCount} (may
 * differ from {@code raw.rowCount()}). The size-equality invariant is removed. The constructor only
 * validates that {@code denseIdsByRawRow.length == raw.rowCount()} (lap-row alignment preserved).
 *
 * @param raw the raw phase definition built from TM domain objects — post-E54S02 rows are lap-rows
 * @param canonical the canonical phase definition
 * @param avatarCount number of distinct TeamAvatars in the phase (AC2, called "N" in the story)
 * @param matchOrder ordered list of all Match entities; size = matchCount (all matches in
 *     phase/group)
 * @param denseIdsByRawRow for each lap-row index {@code i}, the dense avatar IDs in that lap
 * @see PhaseToRawPhaseDefMapper
 * @see SlotResultApplicator
 * @see <a href="../../../../../../../../docs/governance/stories/E04S02.story.md">Story E04S02</a>
 */
public record MappingResult(
        RawPhaseDef raw,
        CanonicalPhaseDef canonical,
        int avatarCount,
        List<Match> matchOrder,
        int[][] denseIdsByRawRow) {

    /**
     * Compact canonical constructor — validates all fields.
     *
     * <p>Post-E54S02 (DEC-61 Clause B): {@code matchOrder.size() == raw.rowCount()} invariant is
     * REMOVED because after Mapper refactor {@code raw.rowCount() = lapCount} while {@code
     * matchOrder.size() = matchCount} (which may differ). {@code denseIdsByRawRow.length ==
     * raw.rowCount()} is retained (lap-row alignment).
     */
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
        // E54S02 (DEC-61 Clause B): matchOrder.size() == raw.rowCount() invariant removed.
        // Post-refactor: raw.rowCount() = lapCount, matchOrder.size() = matchCount.
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
