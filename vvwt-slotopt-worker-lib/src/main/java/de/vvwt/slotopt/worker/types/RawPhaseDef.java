package de.vvwt.slotopt.worker.types;

import java.util.List;

/**
 * The API surface used at submit-job time. Contains the row&rarr;position-set topology with NO team
 * UUIDs, names, or other identity-bearing attributes per DEC-9.
 *
 * <p>{@code phaseId} is included as <em>audit-only metadata</em>: it is echoed back in job and
 * result records and dispatcher logs for human correlation, but MUST NOT participate in the
 * fingerprint computation or in any scoring.
 *
 * <p>Field shape (per AC1 of E01S09):
 *
 * <pre>
 * {
 *   phaseId:  int,        // audit-only; NOT in fingerprint
 *   rowCount: int,        // must equal rows.size()
 *   rows: [
 *     { positions: [{group: int, pos: int}, ...] },  // each row is a SET
 *     ...
 *   ]
 * }
 * </pre>
 *
 * @param phaseId audit-only phase identifier; never used in fingerprint or scoring
 * @param rowCount number of rows; must equal {@code rows.size()}
 * @param rows list of rows; size must equal {@code rowCount}
 */
public record RawPhaseDef(int phaseId, int rowCount, List<RawRow> rows) {

    /** Compact canonical constructor — validates invariants. */
    public RawPhaseDef {
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must be >= 0 but was: " + rowCount);
        }
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
        if (rows.size() != rowCount) {
            throw new IllegalArgumentException(
                    "rowCount=" + rowCount + " does not match rows.size()=" + rows.size());
        }
        // defensive copy for immutability
        rows = List.copyOf(rows);
    }
}
