package de.vvwt.worker.types;

/**
 * Structural identity of a team avatar's slot in a tournament phase. Captures the intrinsic
 * position as {@code (group, pos)} per DEC-9.
 *
 * <p>{@code group} is the group number within the phase; {@code pos} is the seat number within that
 * group. Both values must be non-negative.
 *
 * <p>This type intentionally carries NO team UUID, name, or any identity-bearing attribute. Per
 * DEC-9, such attributes do not cross the optimizer service boundary.
 *
 * @param group zero-based group number within the phase (must be &ge; 0)
 * @param pos zero-based seat number within the group (must be &ge; 0)
 */
public record PositionTuple(int group, int pos) {

    /**
     * Compact canonical constructor — validates invariants.
     *
     * @throws IllegalArgumentException if {@code group} or {@code pos} is negative
     */
    public PositionTuple {
        if (group < 0) {
            throw new IllegalArgumentException("group must be >= 0 but was: " + group);
        }
        if (pos < 0) {
            throw new IllegalArgumentException("pos must be >= 0 but was: " + pos);
        }
    }
}
