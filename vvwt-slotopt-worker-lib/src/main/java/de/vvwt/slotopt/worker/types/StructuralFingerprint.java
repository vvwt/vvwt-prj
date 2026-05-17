// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.types;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Structural fingerprint construction for the slot-optimization service.
 *
 * <p>Transforms a {@link RawPhaseDef} into a deterministic, order-invariant 32-byte SHA-256
 * fingerprint and the corresponding {@link CanonicalPhaseDef}, following the 5-step
 * canonicalization rule defined in AC3 of story E01S09.
 *
 * <h2>Canonicalization Rule (5 steps, verbatim per AC3)</h2>
 *
 * <ol>
 *   <li><strong>Collect avatars.</strong> Walk every row of {@code raw}. Collect all DISTINCT
 *       {@code (group, pos)} tuples encountered (a tuple may appear in many rows — set semantics
 *       dedupe). Sort the resulting tuple set lexicographically (by {@code group} ascending, then
 *       {@code pos} ascending). Assign dense integer ID = sorted position. Define {@code
 *       avatarCount := number of distinct tuples = size of the dense ID assignment from this step}.
 *   <li><strong>Translate rows.</strong> For each row of {@code raw} in input order, replace each
 *       {@code (group, pos)} with its dense ID, then sort the resulting integer set ascending (the
 *       row is a SET — order within a row carries no information). This produces an intermediate
 *       list of {@code rowCount} sorted-ascending integer lists.
 *   <li><strong>Canonicalize row order.</strong> Sort the intermediate list lexicographically
 *       (treating each row as a sequence of ascending integers; standard list-of-ints lex
 *       comparison). This produces the canonical row sequence — independent of {@code raw}'s input
 *       row ordering. The result is the {@code rows} field of {@link CanonicalPhaseDef}. Define
 *       {@code rowCount := length of this list = length of raw.rows()} (the two MUST agree).
 *   <li><strong>Serialize.</strong> Produce a byte sequence as: {@code avatarCount} (4 bytes BE int
 *       from step 1) || {@code rowCount} (4 bytes BE int from step 3) || for each row in canonical
 *       order: {@code len} (4 bytes BE int = length of this row's denseId list) || {@code
 *       denseIds[]} ({@code len × 4} bytes, each a BE int, in the sorted-ascending order from step
 *       2). NOTE: {@code phaseId} is NOT serialized. {@code rowIndex} does not exist in the
 *       canonical form. There is no other field.
 *   <li><strong>Hash.</strong> SHA-256 over the serialized bytes → {@code fingerprint}.
 * </ol>
 *
 * <p>Two honest Java implementations following this rule MUST produce bit-identical bytes for any
 * given {@link RawPhaseDef}.
 *
 * <p>This class is stateless and thread-safe. All methods are pure functions: no I/O, no static
 * mutable state, referentially transparent.
 */
public final class StructuralFingerprint {

    /**
     * Version of the canonicalization rule. Increment this constant if the 5-step rule ever
     * changes; the new value becomes part of the composite cache key {@code (fingerprint,
     * score_fn_version, canonicalization_version)}, protecting future changes from silent cache
     * fragmentation.
     *
     * <p>Current value: {@value} (initial definition, E01S09).
     */
    public static final int CANONICALIZATION_VERSION = 1;

    // No instances — this is a utility class with only static methods.
    private StructuralFingerprint() {
        throw new UnsupportedOperationException("utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Transforms a {@link RawPhaseDef} into its canonical form AND its fingerprint in a single
     * pass.
     *
     * <p>Equivalent to:
     *
     * <pre>
     *     CanonicalPhaseDef canonical = canonicalize(raw);
     *     byte[] fp = fingerprint(canonical);
     *     return new TransformResult(fp, canonical);
     * </pre>
     *
     * @param raw the raw phase definition submitted by the Tournament Manager; must not be {@code
     *     null}
     * @return the combined fingerprint + canonical form
     * @throws IllegalArgumentException if {@code raw} contains invalid values (negative rowCount,
     *     negative group or pos, rowCount mismatch)
     */
    public static TransformResult transform(RawPhaseDef raw) {
        validateRaw(raw);
        CanonicalPhaseDef canonical = canonicalize(raw);
        byte[] fp = fingerprint(canonical);
        return new TransformResult(fp, canonical);
    }

    /**
     * Canonicalizes a {@link RawPhaseDef}: applies the 5-step rule to produce a
     * row-order-independent, position-independent representation using dense integer avatar IDs.
     *
     * @param raw the raw phase definition; must not be {@code null}
     * @return the canonical phase definition
     * @throws IllegalArgumentException if {@code raw} contains invalid values
     */
    public static CanonicalPhaseDef canonicalize(RawPhaseDef raw) {
        validateRaw(raw);

        // --- Step 1: Collect and sort distinct (group, pos) tuples ---
        TreeSet<PositionTuple> distinctTuples =
                new TreeSet<>(
                        Comparator.comparingInt(PositionTuple::group)
                                .thenComparingInt(PositionTuple::pos));
        for (RawRow row : raw.rows()) {
            distinctTuples.addAll(row.positions());
        }

        // Assign dense IDs in sorted order
        Map<PositionTuple, Integer> denseIdByTuple = new LinkedHashMap<>();
        int nextId = 0;
        for (PositionTuple tuple : distinctTuples) {
            denseIdByTuple.put(tuple, nextId++);
        }
        int avatarCount = denseIdByTuple.size();

        // --- Step 2: Translate rows — replace tuples with dense IDs, sort each row ---
        List<List<Integer>> intermediateRows = new ArrayList<>(raw.rowCount());
        for (RawRow row : raw.rows()) {
            List<Integer> denseIds = new ArrayList<>(row.positions().size());
            for (PositionTuple tuple : row.positions()) {
                denseIds.add(denseIdByTuple.get(tuple));
            }
            denseIds.sort(Integer::compareTo);
            intermediateRows.add(List.copyOf(denseIds));
        }

        // --- Step 3: Canonicalize row order — sort rows lexicographically ---
        intermediateRows.sort(StructuralFingerprint::compareRowsLex);

        return new CanonicalPhaseDef(raw.rowCount(), avatarCount, List.copyOf(intermediateRows));
    }

    /**
     * Computes the SHA-256 fingerprint of a {@link CanonicalPhaseDef} using the serialization
     * format defined in step 4 of the canonicalization rule.
     *
     * <p>Serialization:
     *
     * <pre>
     *   avatarCount (4 bytes BE int)
     *   rowCount    (4 bytes BE int)
     *   for each row in canonical order:
     *     len          (4 bytes BE int = number of dense IDs in this row)
     *     denseIds[]   (len × 4 bytes, each a BE int, sorted-ascending)
     * </pre>
     *
     * @param canonical the canonical phase definition to hash; must not be {@code null}
     * @return 32-byte SHA-256 digest
     */
    public static byte[] fingerprint(CanonicalPhaseDef canonical) {
        if (canonical == null) {
            throw new IllegalArgumentException("canonical must not be null");
        }
        byte[] serialized = serialize(canonical);
        return sha256(serialized);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void validateRaw(RawPhaseDef raw) {
        if (raw == null) {
            throw new IllegalArgumentException("raw must not be null");
        }
        // rowCount and rows consistency are already checked by the RawPhaseDef record
        // constructor. Here we additionally verify group/pos values across all rows.
        for (int rowIdx = 0; rowIdx < raw.rows().size(); rowIdx++) {
            RawRow row = raw.rows().get(rowIdx);
            for (PositionTuple tuple : row.positions()) {
                // PositionTuple constructor already validates group >= 0 and pos >= 0,
                // so no additional check is needed here. This loop is a safety net for
                // any future deserialization path that bypasses the record constructor.
                if (tuple.group() < 0) {
                    throw new IllegalArgumentException(
                            "row[" + rowIdx + "]: group must be >= 0 but was: " + tuple.group());
                }
                if (tuple.pos() < 0) {
                    throw new IllegalArgumentException(
                            "row[" + rowIdx + "]: pos must be >= 0 but was: " + tuple.pos());
                }
            }
        }
    }

    /**
     * Lexicographic comparator for two rows (lists of dense integer IDs). Standard list-of-ints lex
     * comparison per AC3 step 3.
     */
    private static int compareRowsLex(List<Integer> rowA, List<Integer> rowB) {
        int minLength = Math.min(rowA.size(), rowB.size());
        for (int index = 0; index < minLength; index++) {
            int comparison = Integer.compare(rowA.get(index), rowB.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(rowA.size(), rowB.size());
    }

    /** Serializes a {@link CanonicalPhaseDef} to bytes per step 4 of the canonicalization rule. */
    private static byte[] serialize(CanonicalPhaseDef canonical) {
        // Pre-compute total byte count to size the buffer exactly
        int totalInts = 2; // avatarCount + rowCount
        for (List<Integer> row : canonical.rows()) {
            totalInts += 1 + row.size(); // len field + denseIds
        }
        ByteBuffer buffer = ByteBuffer.allocate(totalInts * Integer.BYTES);

        buffer.putInt(canonical.avatarCount()); // step 4: avatarCount
        buffer.putInt(canonical.rowCount()); // step 4: rowCount

        for (List<Integer> row : canonical.rows()) {
            buffer.putInt(row.size()); // step 4: len
            for (int denseId : row) {
                buffer.putInt(denseId); // step 4: denseIds[]
            }
        }
        return buffer.array();
    }

    /** Computes SHA-256 using the JDK standard library (no external dependency). */
    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is required by the Java SE specification — this cannot happen
            // on any conforming JVM.
            throw new IllegalStateException(
                    "SHA-256 not available — JVM non-conformant", exception);
        }
    }
}
