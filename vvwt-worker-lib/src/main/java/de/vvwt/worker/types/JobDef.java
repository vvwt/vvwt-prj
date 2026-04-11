package de.vvwt.worker.types;

import java.util.UUID;

/**
 * Immutable descriptor for a slot-optimization job, passed to the compute kernel.
 *
 * <p>Carries the job identity ({@link #jobId}), the dimension ({@link #n} — the number of
 * elements being permuted, must be in [1, 17]), and the canonical phase definition
 * ({@link #canonicalPhaseDef}) from which the active-matrix is built once per solve call.
 *
 * <h2>Normative type model</h2>
 * <p>This type belongs to the public shared type model defined in E01S09 AC1 and lives in
 * {@code de.vvwt.worker.types} per DEC-11.
 *
 * <h2>Validation</h2>
 * <p>The compact canonical constructor validates all invariants at construction time.
 * No further defensive checks are needed by callers.
 *
 * @param jobId            unique identifier of the optimization job; must not be {@code null}
 * @param n                number of elements to permute; must be in [{@value #MIN_N}, {@value #MAX_N}]
 * @param canonicalPhaseDef the canonical phase definition to score against; must not be {@code null}
 */
public record JobDef(UUID jobId, int n, CanonicalPhaseDef canonicalPhaseDef) {

    /** Minimum supported permutation size. */
    public static final int MIN_N = 1;

    /**
     * Maximum supported permutation size.
     * Constrained by {@code long}: 18! overflows; 17! = 355_687_428_096_000 is safe.
     */
    public static final int MAX_N = 17;

    /** Compact canonical constructor — validates all invariants. */
    public JobDef {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId must not be null");
        }
        if (n < MIN_N || n > MAX_N) {
            throw new IllegalArgumentException(
                    "n must be in [" + MIN_N + ", " + MAX_N + "], got: " + n);
        }
        if (canonicalPhaseDef == null) {
            throw new IllegalArgumentException("canonicalPhaseDef must not be null");
        }
    }
}
