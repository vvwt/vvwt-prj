package de.vvwt.slotopt.worker.types;

import java.util.List;

/**
 * One row of a {@link RawPhaseDef}: the SET of position tuples that play together in this row
 * (round).
 *
 * <p>Row semantics are SET semantics — order within a row carries no information. The {@link
 * de.vvwt.worker.types.StructuralFingerprint} canonicalizer will sort the positions before hashing.
 *
 * @param positions non-null, non-empty list of position tuples in this row; order is irrelevant
 *     (the canonicalizer sorts them)
 */
public record RawRow(List<PositionTuple> positions) {

    /** Compact canonical constructor — validates invariants. */
    public RawRow {
        if (positions == null) {
            throw new IllegalArgumentException("positions must not be null");
        }
        if (positions.isEmpty()) {
            throw new IllegalArgumentException("positions must not be empty");
        }
        // defensive copy for immutability
        positions = List.copyOf(positions);
    }
}
