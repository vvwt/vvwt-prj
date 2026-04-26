package de.vvwt.slotopt.worker.types;

import java.util.List;

/**
 * The canonical form of a phase definition, passed to workers and to the scorer. Contains dense
 * integer avatar indices in canonical order.
 *
 * <p>NO {@code phaseId}, NO {@code rowIndex}. The scorer (E01S02) accesses rows by their list
 * position only.
 *
 * <p>Field shape (per AC1 of E01S09):
 *
 * <pre>
 * {
 *   rowCount:    int,
 *   avatarCount: int,
 *   rows: [
 *     [int, int, ...],  // each row = sorted-ascending list of dense IDs (set semantics)
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <p>Instances are produced exclusively by {@link StructuralFingerprint#canonicalize(RawPhaseDef)}.
 *
 * @param rowCount number of rows; equals {@code rows.size()}
 * @param avatarCount number of distinct {@link PositionTuple}s found across all rows
 * @param rows canonically ordered list of rows; each row is a sorted-ascending list of dense
 *     integer avatar IDs
 */
public record CanonicalPhaseDef(int rowCount, int avatarCount, List<List<Integer>> rows) {

    /** Compact canonical constructor — validates invariants and enforces immutability. */
    public CanonicalPhaseDef {
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must be >= 0 but was: " + rowCount);
        }
        if (avatarCount < 0) {
            throw new IllegalArgumentException("avatarCount must be >= 0 but was: " + avatarCount);
        }
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
        if (rows.size() != rowCount) {
            throw new IllegalArgumentException(
                    "rowCount=" + rowCount + " does not match rows.size()=" + rows.size());
        }
        // Deep defensive copy — all inner lists must also be immutable
        rows =
                rows.stream()
                        .map(List::copyOf)
                        .collect(java.util.stream.Collectors.toUnmodifiableList());
    }
}
