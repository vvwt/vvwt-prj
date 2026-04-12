package de.vvwt.tm;

import de.vvwt.worker.codec.LehmerCodec;
import de.vvwt.worker.score.VarietyScorer;
import de.vvwt.worker.solver.PacketSolver;
import de.vvwt.worker.types.CanonicalPhaseDef;
import de.vvwt.worker.types.RawPhaseDef;
import de.vvwt.worker.types.StructuralFingerprint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compilation smoke test — E04S01 AC4.
 *
 * Verifies that vvwt-tm-web can import and reference all six
 * vvwt-worker-lib types required by DEC-4 V1 amendment and
 * subsequent E04 stories (E04S02–E04S04).
 *
 * This test does NOT exercise runtime behaviour — it confirms
 * the dependency is correctly wired at compile time by
 * referencing each class literal.
 */
class WorkerLibImportTest {

    @Test
    void workerLibTypesAreAccessible() {
        // AC4: all six types must be resolvable at compile time.
        // Using Class.getSimpleName() to reference each class without
        // instantiation (some types require constructor arguments).
        assertThat(RawPhaseDef.class.getSimpleName()).isEqualTo("RawPhaseDef");
        assertThat(CanonicalPhaseDef.class.getSimpleName()).isEqualTo("CanonicalPhaseDef");
        assertThat(StructuralFingerprint.class.getSimpleName()).isEqualTo("StructuralFingerprint");
        assertThat(PacketSolver.class.getSimpleName()).isEqualTo("PacketSolver");
        assertThat(LehmerCodec.class.getSimpleName()).isEqualTo("LehmerCodec");
        assertThat(VarietyScorer.class.getSimpleName()).isEqualTo("VarietyScorer");
    }
}
