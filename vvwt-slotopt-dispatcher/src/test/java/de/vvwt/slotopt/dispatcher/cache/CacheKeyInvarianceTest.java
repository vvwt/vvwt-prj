// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.cache;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import de.vvwt.slotopt.worker.types.StructuralFingerprint;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Invariance test for AC-DEC9-CACHE-KEY-INVARIANCE.
 *
 * <p>Per DEC-9 § Impact: two {@link RawPhaseDef} instances with different {@code phaseId} values
 * but identical structural topology MUST produce identical structural fingerprints — and therefore
 * identical composite cache keys {@code (fingerprint, gameMode)}. This is the cross-organizer cache
 * value-prop: identical structural problems from different organizers share a cached result.
 *
 * <p>Story: E37S10; AC-DEC9-CACHE-KEY-INVARIANCE; DEC-9
 */
class CacheKeyInvarianceTest {

    /**
     * Two {@link RawPhaseDef} instances with distinct {@code phaseId} values but identical row
     * structure produce the same SHA-256 structural fingerprint.
     *
     * <p>This verifies the core DEC-9 invariant: {@code phaseId} is NOT included in the fingerprint
     * (step 4 of the canonicalization rule explicitly excludes it). Different organizers submitting
     * structurally equivalent tournaments get the same cached result.
     */
    @Test
    void structurallyIdenticalPhasesWithDifferentPhaseIdProduceIdenticalFingerprint() {
        // Two rows, each containing two distinct (group, pos) tuples — same structure
        List<RawRow> rows =
                List.of(
                        new RawRow(List.of(new PositionTuple(0, 0), new PositionTuple(0, 1))),
                        new RawRow(List.of(new PositionTuple(1, 0), new PositionTuple(1, 1))));

        // Phase A: phaseId = 1001 (organizer A)
        RawPhaseDef phaseA = new RawPhaseDef(1001, 2, rows);

        // Phase B: phaseId = 9999 (organizer B — different organizer, different phaseId)
        RawPhaseDef phaseB = new RawPhaseDef(9999, 2, rows);

        byte[] fingerprintA = StructuralFingerprint.transform(phaseA).fingerprint();
        byte[] fingerprintB = StructuralFingerprint.transform(phaseB).fingerprint();

        // DEC-9 invariant: fingerprints are byte-identical regardless of phaseId
        assertThat(fingerprintA).isEqualTo(fingerprintB);

        // The resulting CachedResultId composite keys with the same gameMode are also equal
        CachedResultId keyA = new CachedResultId(fingerprintA, "default");
        CachedResultId keyB = new CachedResultId(fingerprintB, "default");
        assertThat(keyA).isEqualTo(keyB);
    }

    /**
     * Two {@link RawPhaseDef} instances with structurally different topologies produce different
     * fingerprints — confirming that the fingerprint is a meaningful discriminator.
     */
    @Test
    void structurallyDifferentPhasesProduceDifferentFingerprints() {
        // Phase A: 2 rows × 2 positions
        RawPhaseDef phaseA =
                new RawPhaseDef(
                        1,
                        2,
                        List.of(
                                new RawRow(
                                        List.of(new PositionTuple(0, 0), new PositionTuple(0, 1))),
                                new RawRow(
                                        List.of(
                                                new PositionTuple(1, 0),
                                                new PositionTuple(1, 1)))));

        // Phase B: 1 row × 2 positions (different rowCount → different topology)
        RawPhaseDef phaseB =
                new RawPhaseDef(
                        1,
                        1,
                        List.of(
                                new RawRow(
                                        List.of(
                                                new PositionTuple(0, 0),
                                                new PositionTuple(0, 1)))));

        byte[] fingerprintA = StructuralFingerprint.transform(phaseA).fingerprint();
        byte[] fingerprintB = StructuralFingerprint.transform(phaseB).fingerprint();

        assertThat(fingerprintA).isNotEqualTo(fingerprintB);
    }
}
